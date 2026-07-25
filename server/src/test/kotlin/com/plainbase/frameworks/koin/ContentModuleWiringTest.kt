package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.objectstore.ObjectContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files

/**
 * DI wiring smoke test for the content + history adapters (C4 REV 3's HistoryModule boot trap).
 *
 * Regression guard 1: [contentModule] was DECLARED but never installed in the production
 * `startKoin { modules(...) }` set, so `ContentStore` could not be resolved at runtime even though
 * the module existed.
 *
 * Regression guard 2 (C4): `historyModule`'s `HistoryProvider` single used to bind an EAGERLY
 * evaluated `get<LocalContentStore>()::resolveRepoRelativePath` reference - forcing the contentDir
 * store to construct at graph-resolution time even in OBJECT mode (where it must stay a dead
 * provider, R9). Both tests below install the PRODUCTION module set INCLUDING `historyModule`, so
 * a reintroduced eager reference fails this test rather than failing silently in production.
 *
 * Mirrors how the app wires Koin ([com.plainbase.Application] `serve()`) but does NOT start a real
 * server: resolution is lazy, so only what each test actually resolves gets constructed. No SQL
 * driver is touched in the LOCAL-mode test; the OBJECT-mode test uses a temp `DATA_DIR` because
 * resolving [ObjectContentStore] eagerly resolves `DirtyPageRepository` (a real SQLite driver open).
 */
class ContentModuleWiringTest : FunSpec({

    test("the production module set (incl. historyModule) resolves ContentStore to LocalContentStore in LOCAL mode") {
        val app = koinApplication {
            modules(configModule, contentModule, repositoryModule, securityModule, historyModule)
        }
        try {
            app.koin.get<ContentStore>().shouldBeInstanceOf<LocalContentStore>()
            app.koin.get<HistoryProvider>() // resolves cleanly - the pre-C4 baseline this guard protects
        } finally {
            app.close()
        }
    }

    test(
        "storage.backend=object resolves ContentStore to the hybrid; HistoryProvider resolves WITHOUT " +
            "constructing the dead contentDir LocalContentStore (the R9 boot trap this test guards)",
    ) {
        withTempDataDir { dataDir ->
            val app = koinApplication {
                modules(objectConfigModule(dataDir), contentModule, repositoryModule, securityModule, historyModule)
            }
            try {
                app.koin.get<ContentStore>().shouldBeInstanceOf<ObjectContentStore>()
                // The historyModule `repoPath` lambda must be lazy + backend-conditional: resolving
                // HistoryProvider here must NOT force `get<LocalContentStore>()` (which would construct
                // the dead contentDir store against a CONTENT_DIR object mode never touches).
                val history = app.koin.get<HistoryProvider>()
                history.enabled shouldBe false // NoOp: git.enabled defaults to null in object mode (C4)
            } finally {
                // Koin does not auto-close AutoCloseable singles: close the resolved ObjectContentStore so
                // its ktor CIO S3ObjectClient transport does not leak.
                app.koin.get<ObjectContentStore>().close()
                app.close()
            }
        }
    }

    test(
        "object mode + explicit git.enabled=true resolves a real GitCliHistoryProvider over the mirror " +
            "whose gateCheck() PASSES when git is present (C5 BOUND decision 1 - replaces the C4 refusal)",
    ) {
        withTempDataDir { dataDir ->
            val app = koinApplication {
                modules(objectConfigModule(dataDir, gitEnabled = true), contentModule, repositoryModule, securityModule, historyModule)
            }
            try {
                val history = app.koin.get<HistoryProvider>()
                history.shouldBeInstanceOf<GitCliHistoryProvider>()
                history.enabled shouldBe true
                history.gateCheck() // does not throw - the object-mode git binary/version probe passes pre-lock
            } finally {
                app.close()
            }
        }
    }

    test("object mode resolves the lazy DR bundle and the no-op write-history adapter through production wiring") {
        withTempDataDir { dataDir ->
            val app = koinApplication {
                modules(objectConfigModule(dataDir), contentModule, repositoryModule, securityModule, historyModule)
            }
            val store = app.koin.get<ObjectContentStore>()
            val bundleDr = app.koin.get<GitBundleDr>()
            try {
                bundleDr.shouldBeInstanceOf<GitBundleDr>()

                val hook = app.koin.get<WriteHistoryHook>()
                hook.commit(RootName.MAIN, TreePath.require("wiring.md"), "content".toByteArray(), null, null) shouldBe null
            } finally {
                bundleDr.close()
                store.close()
                app.close()
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
                val app = koinApplication {
                    modules(
                        objectConfigModule(dataDir, endpoint = "http://127.0.0.1:$port"),
                        contentModule,
                        repositoryModule,
                        securityModule,
                        historyModule,
                    )
                }
                val store = app.koin.get<ObjectContentStore>()
                try {
                    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
                    val path = TreePath.require("guides/wiring.md")
                    val idMap = app.koin.get<IdMapRepository>()
                    val retirements = app.koin.get<RetirementRepository>()

                    retirements.observation(RootName.MAIN)
                    idMap.bind(RootedPath(RootName.MAIN, path), id, materialized = false)
                    val expectedEpoch = retirements.bindingEpoch(RootName.MAIN)
                    expectedEpoch shouldBe BindingEpoch(1)

                    store.pollOnce()

                    val manifest = checkNotNull(store.latestManifest())
                    manifest.rowsAtStart shouldBe setOf(BindingRef(path, id))
                    manifest.bindingEpoch shouldBe expectedEpoch
                } finally {
                    store.close()
                    app.close()
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

private fun objectConfigModule(
    dataDir: java.nio.file.Path,
    gitEnabled: Boolean? = null,
    endpoint: String = "https://acct.example.com",
) = module {
    single {
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
    }
}

private fun withTempDataDir(block: (java.nio.file.Path) -> Unit) {
    val dir = Files.createTempDirectory("plainbase-content-module-wiring")
    try {
        block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
