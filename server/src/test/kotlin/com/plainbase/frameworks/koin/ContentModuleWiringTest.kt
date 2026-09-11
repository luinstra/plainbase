package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.git.GitRepoLocks
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.error.InstanceCreationException
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Files

/**
 * DI wiring smoke test for the content + history adapters (C4 REV 3's HistoryModule boot trap).
 *
 * Regression guard 1: the content module was DECLARED but never installed in the production
 * `startKoin { modules(...) }` set, so `ContentStore` could not be resolved at runtime even though
 * the module existed.
 *
 * Regression guard 2 (C4): history selection is prepared before module registration, while the
 * object provider's path callback remains deferred until the actual mirror is armed. Both tests
 * below install the production module set with prepared inputs, so an eager object path remains
 * visible at graph resolution rather than failing silently in production.
 *
 * Mirrors how the app wires Koin ([com.plainbase.Application] `serve()`) but does NOT start a real
 * server: resolution is lazy, so only what each test actually resolves gets constructed. No SQL
 * driver is touched in the LOCAL-mode test; the OBJECT-mode test uses a temp `DATA_DIR` because
 * resolving [ObjectContentStore] eagerly resolves `DirtyPageRepository` (a real SQLite driver open).
 */
class ContentModuleWiringTest : FunSpec({

    test("the production module set resolves ContentStore to LocalContentStore in LOCAL mode") {
        val config = PlainbaseConfig.fromEnv(emptyMap())
        val owner = ServerResourceOwner()
        val app = createOwnedTestKoinApplication(owner, preparedModules(config, owner).toList())
        try {
            app.koin.get<ContentStore>().shouldBeInstanceOf<LocalContentStore>()
            app.koin.get<HistoryProvider>() // resolves cleanly - the pre-C4 baseline this guard protects
        } finally {
            owner.close()
        }
    }

    test("prepared LOCAL store, history, and availability retain identity after Koin registration") {
        withTempDataDir { dataDir ->
            val config = PlainbaseConfig.fromEnv(emptyMap()).copy(dataDir = dataDir)
            val openers = ServerOpeners()
            val inputs = prepareRootBootInputs(config, openers.openLocal)
            val owner = ServerResourceOwner()
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { config } },
                    createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
                    createHistoryModule(config, inputs.history, owner),
                ),
            )
            try {
                app.koin.get<RootAvailability>() shouldBeSameInstanceAs inputs.availability
                app.koin.get<LocalContentStore>() shouldBeSameInstanceAs inputs.localStores.getValue(RootName.PRIMARY)
                app.koin.get<HistoryProviders>().primary shouldBeSameInstanceAs inputs.history.byRoot.getValue(RootName.PRIMARY)
            } finally {
                owner.close()
            }
        }
    }

    test(
        "storage.backend=object resolves ContentStore to the hybrid; HistoryProvider resolves WITHOUT " +
            "constructing the dead contentDir LocalContentStore (the R9 boot trap this test guards)",
    ) {
            withTempDataDir { dataDir ->
                val config = objectConfig(dataDir)
            val owner = ServerResourceOwner()
            val app = createOwnedTestKoinApplication(owner, preparedModules(config, owner).toList())
            try {
                app.koin.get<ContentStore>().shouldBeInstanceOf<ObjectContentStore>()
                // The prepared `repoPath` lambda must be lazy + backend-conditional: resolving
                // HistoryProvider here must NOT force `get<LocalContentStore>()` (which would construct
                // the dead contentDir store against a CONTENT_DIR object mode never touches).
                val history = app.koin.get<HistoryProvider>()
                history.enabled shouldBe false // NoOp: git.enabled defaults to null in object mode (C4)
            } finally {
                owner.close()
            }
        }
    }

    test(
        "object mode + explicit git.enabled=true resolves a real GitCliHistoryProvider over the mirror " +
            "whose gateCheck() PASSES when git is present (C5 BOUND decision 1 - replaces the C4 refusal)",
    ) {
            withTempDataDir { dataDir ->
                val config = objectConfig(dataDir, gitEnabled = true)
            val owner = ServerResourceOwner()
            val app = createOwnedTestKoinApplication(owner, preparedModules(config, owner).toList())
            try {
                val history = app.koin.get<HistoryProvider>()
                history.shouldBeInstanceOf<GitCliHistoryProvider>()
                history.enabled shouldBe true
                history.gateCheck() // does not throw - the object-mode git binary/version probe passes pre-lock
            } finally {
                owner.close()
            }
        }
    }

    test("object history provider and bundle DR share the prepared locks and repo-write monitor") {
        withTempDataDir { dataDir ->
            val config = objectConfig(dataDir, gitEnabled = true)
            val openers = ServerOpeners()
            val inputs = prepareRootBootInputs(config, openers.openLocal)
            val owner = ServerResourceOwner()
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { config } },
                    createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
                    repositoryModule(owner),
                    securityModule,
                    createHistoryModule(config, inputs.history, owner),
                ),
            )
            try {
                app.koin.get<ObjectContentStore>()
                app.koin.get<SqlDriver>()
                val provider = app.koin.get<HistoryProvider>().shouldBeInstanceOf<GitCliHistoryProvider>()
                val acquiredBundleDr = app.koin.get<GitBundleDr>()
                val registeredLocks = app.koin.get<GitRepoLocks>()
                provider.repoWriteMonitor shouldBeSameInstanceAs inputs.history.objectLocks.value.repoWrite
                acquiredBundleDr.locks shouldBeSameInstanceAs inputs.history.objectLocks.value
                registeredLocks shouldBeSameInstanceAs inputs.history.objectLocks.value
            } finally {
                owner.close()
            }
        }
    }

    test("an omitted required LOCAL extra fails by root instead of aliasing the primary") {
        withTempDataDir { dataDir ->
            val primaryDir = Files.createDirectory(dataDir.resolve("primary"))
            val extraDir = Files.createDirectory(dataDir.resolve("extra"))
            val extra = RootName.require("extra")
            val config = PlainbaseConfig.fromEnv(emptyMap()).copy(
                contentDir = primaryDir,
                dataDir = dataDir,
                roots = RootsConfig.of(
                    listOf(
                        Root(RootName.PRIMARY, RootBackend.Local(primaryDir), editable = true, history = HistoryMode.OFF),
                        Root(extra, RootBackend.Local(extraDir), editable = true, history = HistoryMode.OFF),
                    ),
                    origin = RootsOrigin.EXPLICIT,
                ),
            )
            val fullInputs = prepareRootBootInputs(config, ServerOpeners().openLocal)
            val omittedInputs = fullInputs.copy(localStores = fullInputs.localStores - extra)
            val owner = ServerResourceOwner()
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { config } },
                    createContentModule(config, omittedInputs, ServerOpeners().openObject, { it.close() }, owner),
                ),
            )
            try {
                val failure = shouldThrow<InstanceCreationException> { app.koin.get<RootStores>()[extra] }
                failure.cause?.message shouldContain "root 'extra'"
            } finally {
                owner.close()
            }
        }
    }

    test("object mode resolves the lazy DR bundle and the no-op write-history adapter through production wiring") {
        withTempDataDir { dataDir ->
            val config = objectConfig(dataDir)
            val owner = ServerResourceOwner()
            val app = createOwnedTestKoinApplication(owner, preparedModules(config, owner).toList())
            val store = app.koin.get<ObjectContentStore>()
            val bundleDr = app.koin.get<GitBundleDr>()
            try {
                bundleDr.shouldBeInstanceOf<GitBundleDr>()

                val hook = app.koin.get<WriteHistoryHook>()
                hook.commit(RootName.PRIMARY, TreePath.require("wiring.md"), "content".toByteArray(), null, null) shouldBe null
            } finally {
                owner.close()
            }
        }
    }

    test("object mode snapshots the real durable rows and binding epoch at the LIST boundary") {
        val listXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
            </ListBucketResult>
        """.trimIndent()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                route("{path...}") {
                    handle {
                        call.respondText(listXml, ContentType.Application.Xml, HttpStatusCode.OK)
                    }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        try {
            withTempDataDir { dataDir ->
                val config = objectConfig(dataDir, endpoint = "http://127.0.0.1:$port")
                val owner = ServerResourceOwner()
                val app = createOwnedTestKoinApplication(owner, preparedModules(config, owner).toList())
                val store = app.koin.get<ObjectContentStore>()
                try {
                    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
                    val path = TreePath.require("guides/wiring.md")
                    val idMap = app.koin.get<IdMapRepository>()
                    val retirements = app.koin.get<RetirementRepository>()

                    retirements.observation(RootName.PRIMARY)
                    idMap.bind(RootedPath(RootName.PRIMARY, path), id, materialized = false)
                    val expectedEpoch = retirements.bindingEpoch(RootName.PRIMARY)
                    expectedEpoch shouldBe BindingEpoch(1)

                    store.pollOnce()

                    val manifest = checkNotNull(store.latestManifest())
                    manifest.rowsAtStart shouldBe setOf(BindingRef(path, id))
                    manifest.bindingEpoch shouldBe expectedEpoch
                } finally {
                    owner.close()
                }
            }
        } finally {
            // EmbeddedServer.stop() blocks internally; keep that runBlocking bridge off Kotest's test coroutine.
            withContext(Dispatchers.IO) {
                server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
            }
        }
    }
})

private fun objectConfig(
    dataDir: java.nio.file.Path,
    gitEnabled: Boolean? = null,
    endpoint: String = "https://acct.example.com",
): PlainbaseConfig =
    PlainbaseConfig.fromEnv(emptyMap()).copy(
        dataDir = dataDir,
        storage = StorageConfig(
            backend = StorageBackend.OBJECT,
            endpoint = endpoint,
            bucket = "docs",
            accessKeyId = "k",
            secretAccessKey = "s",
        ),
        git = GitConfig(enabled = gitEnabled),
    )

private fun preparedModules(
    config: PlainbaseConfig,
    owner: ServerResourceOwner,
    openers: ServerOpeners = ServerOpeners(),
): Array<Module> {
    val inputs = prepareRootBootInputs(config, openers.openLocal)
    return arrayOf(
        module { single { config } },
        createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
        repositoryModule(owner),
        securityModule,
        createHistoryModule(config, inputs.history, owner),
    )
}

private fun withTempDataDir(block: (java.nio.file.Path) -> Unit) {
    val dir = Files.createTempDirectory("plainbase-content-module-wiring")
    try {
        block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
