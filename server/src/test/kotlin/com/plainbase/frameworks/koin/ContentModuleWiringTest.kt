package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * DI wiring smoke test for the content adapter.
 *
 * Regression guard: [contentModule] was DECLARED but never installed in the production
 * `startKoin { modules(...) }` set, so `ContentStore` could not be resolved at runtime even though
 * the module existed. This test builds the PRODUCTION module set and resolves [ContentStore], so a
 * "declared but not installed" gap fails the build instead of failing silently in production.
 *
 * It mirrors how the app wires Koin ([com.plainbase.Application] `serve()`) but does NOT start a
 * real server. Resolution is lazy: only [ContentStore] and its direct dependencies
 * (`PlainbaseConfig`, `IgnoreRules`) are instantiated — the SQL driver is never touched.
 * `PlainbaseConfig.fromEnv()` supplies `CONTENT_DIR` exactly as the app does (default `./content`),
 * and `LocalContentStore` construction performs no disk I/O, so no temp dir is required.
 */
class ContentModuleWiringTest : FunSpec({

    test("the production module set resolves ContentStore (contentModule is installed)") {
        // Mirror Application.serve()'s startKoin module list — the production wiring under test.
        val app = koinApplication {
            modules(configModule, contentModule, repositoryModule, securityModule)
        }
        try {
            val store = app.koin.get<ContentStore>()
            store.shouldBeInstanceOf<LocalContentStore>()
        } finally {
            app.close()
        }
    }

    test("storage.backend=object refuses ContentStore resolution actionably (the hybrid adapter is not built yet)") {
        val objectConfig = module {
            single {
                PlainbaseConfig.fromEnv(emptyMap()).copy(
                    storage = StorageConfig(
                        backend = StorageBackend.OBJECT,
                        endpoint = "https://acct.r2.cloudflarestorage.com",
                        bucket = "docs",
                        accessKeyId = "k",
                        secretAccessKey = "s",
                    ),
                )
            }
        }
        val app = koinApplication { modules(objectConfig, contentModule) }
        try {
            val failure = shouldThrowAny { app.koin.get<ContentStore>() }
            // Koin wraps instance-creation failures; the actionable message must survive somewhere in the chain.
            generateSequence<Throwable>(failure) { it.cause }
                .any { it.message?.contains("storage.backend=object") == true } shouldBe true
        } finally {
            app.close()
        }
    }
})
