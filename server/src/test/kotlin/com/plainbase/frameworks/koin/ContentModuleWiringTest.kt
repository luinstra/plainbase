package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.RootName
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
})

private fun objectConfigModule(dataDir: java.nio.file.Path, gitEnabled: Boolean? = null) = module {
    single {
        PlainbaseConfig.fromEnv(emptyMap()).copy(
            dataDir = dataDir,
            storage = StorageConfig(
                backend = StorageBackend.OBJECT,
                endpoint = "https://acct.example.com",
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
