package com.plainbase.frameworks.koin

import com.plainbase.domain.history.HistoryProvider
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.S3ObjectClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files

/**
 * C5 VERIFY (Step 1 acceptance item): resolving `HistoryProvider` + calling `gateCheck()` for an
 * object+`git.enabled=true` boot must construct ZERO [ObjectContentStore]/[S3ObjectClient] - the
 * object-mode `repoPath` lambda resolves the store on CALL (commit time), never at wiring/gate time,
 * and `Application.kt`'s `serve()` only calls `koin.get<ObjectContentStore>().hydrate(...)` AFTER the
 * lock. The gate check itself must PASS (Cluster-1 fix: the `--version` probe never touches `-C
 * <missing-mirror>`) even though `DATA_DIR/mirror` does not exist yet at this point (this test never
 * creates it) - counter-proven, never reasoned from laziness alone (the R9 policy).
 */
class ObjectBootNoTransportBeforeLockTest : FunSpec({

    test("object + git.enabled=true: resolving HistoryProvider and calling gateCheck() constructs zero ObjectContentStore/S3ObjectClient") {
        val objectBefore = ObjectContentStore.constructions.get()
        val s3Before = S3ObjectClient.constructions.get()

        withTempDataDir { dataDir ->
            val app = koinApplication {
                modules(objectGitEnabledConfigModule(dataDir), contentModule, repositoryModule, securityModule, historyModule)
            }
            try {
                val history = app.koin.get<HistoryProvider>()
                history.gateCheck() // must not throw - DATA_DIR/mirror does not exist yet (pre-lock)
            } finally {
                app.close()
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
            val objectEnvConfig = module {
                single {
                    PlainbaseConfig.fromEnv(
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
                }
            }
            val app = koinApplication {
                modules(objectEnvConfig, contentModule, repositoryModule, securityModule, historyModule)
            }
            try {
                app.koin.get<HistoryProvider>().gateCheck()
            } finally {
                app.close()
            }
        }
    }
})

private fun objectGitEnabledConfigModule(dataDir: java.nio.file.Path) = module {
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
            git = GitConfig(enabled = true),
        )
    }
}

private fun withTempDataDir(block: (java.nio.file.Path) -> Unit) {
    val dir = Files.createTempDirectory("plainbase-object-boot-no-transport")
    try {
        block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
