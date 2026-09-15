package com.plainbase.frameworks.koin

import com.plainbase.domain.history.HistoryProvider
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.S3ObjectClient
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.koin.dsl.module
import java.nio.file.Files

/**
 * History-only graph check: resolving `HistoryProvider` + calling `gateCheck()` for an
 * object+`git.enabled=true` graph must construct ZERO [ObjectContentStore]/[S3ObjectClient]. The
 * object-mode `repoPath` callback is deferred until the prepared mirror is armed, never while this history
 * graph is wired or checked. The gate check must PASS even though `DATA_DIR/mirror` does not exist
 * yet; this test never creates it and proves the construction counters directly.
 */
class ObjectBootNoTransportBeforeLockTest : FunSpec({

    test("object + git.enabled=true: resolving HistoryProvider and calling gateCheck() constructs zero ObjectContentStore/S3ObjectClient") {
        val objectBefore = ObjectContentStore.constructions.get()
        val s3Before = S3ObjectClient.constructions.get()

        withTempDataDir { dataDir ->
            val config = objectGitEnabledConfig(dataDir)
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
                val history = app.koin.get<HistoryProvider>()
                history.gateCheck() // must not throw - DATA_DIR/mirror does not exist yet (pre-lock)
            } finally {
                owner.close()
            }
        }

        ObjectContentStore.constructions.get() shouldBe objectBefore
        S3ObjectClient.constructions.get() shouldBe s3Before
    }

    // Multi-root C1 regression pin: an env-built object config synthesizes an Object-backed main whose
    // localPath is NULL, and the HistoryProvider lambda evaluates mainContentRoot() EAGERLY at both
    // selectHistoryProvider call sites - so a requireNotNull over main.localPath there would crash every
    // real object boot. The copy()-built config above cannot catch that (its stale synthesized main is
    // Local), hence this second, synthesized-from-env graph.
    test("object synthesis (main has no local path): HistoryProvider resolves and gate-checks without throwing") {
        withTempDataDir { dataDir ->
            val objectEnvConfig = ConfigLoader.fromEnv(
                        mapOf(
                            "DATA_DIR" to dataDir.toString(),
                            "PLAINBASE_STORAGE_BACKEND" to "object",
                            "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                            "PLAINBASE_S3_BUCKET" to "docs",
                            "PLAINBASE_S3_ACCESS_KEY_ID" to "k",
                            "PLAINBASE_S3_SECRET_ACCESS_KEY" to "s",
                            "PLAINBASE_GIT_ENABLED" to "true",
                        ),
                    )
            val openers = ServerOpeners()
            val inputs = prepareRootBootInputs(objectEnvConfig, openers.openLocal)
            val owner = ServerResourceOwner()
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { objectEnvConfig } },
                    createContentModule(objectEnvConfig, inputs, openers.openObject, { it.close() }, owner),
                    repositoryModule(owner),
                    securityModule,
                    createHistoryModule(objectEnvConfig, inputs.history, owner),
                ),
            )
            try {
                app.koin.get<HistoryProvider>().gateCheck()
            } finally {
                owner.close()
            }
        }
    }
})

private fun objectGitEnabledConfig(dataDir: java.nio.file.Path): PlainbaseConfig =
    ConfigLoader.fromEnv(emptyMap()).copy(
        dataDir = dataDir,
        storage = StorageConfig(
            backend = StorageBackend.OBJECT,
            endpoint = "https://acct.example.com",
            bucket = "docs",
            accessKeyId = "k",
            secretAccessKey = "s",
        ),
        git = GitConfig(enabled = true),
    )

private fun withTempDataDir(block: (java.nio.file.Path) -> Unit) {
    val dir = Files.createTempDirectory("plainbase-object-boot-no-transport")
    try {
        block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
