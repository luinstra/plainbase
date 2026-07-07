package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.S3ObjectClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files

/**
 * R9, counter-proven (never reasoned from `single {}` laziness alone, per the plan's own rule):
 *
 * (i) a LOCAL boot constructs ZERO [ObjectContentStore] / [S3ObjectClient].
 * (ii) an OBJECT boot constructs ZERO contentDir [LocalContentStore] (the dead provider).
 *
 * The counters ([contentDirStoreConstructions], [ObjectContentStore.constructions],
 * [S3ObjectClient.constructions]) are process-global `AtomicInteger`s, so every assertion here is a
 * BEFORE/AFTER delta over the resolution under test, not an absolute zero - robust to other test
 * classes in the same forked JVM having already incremented them.
 */
class LocalBootNoObjectConstructionTest : FunSpec({

    test("LOCAL boot resolves ContentStore + HistoryProvider without constructing ObjectContentStore or S3ObjectClient") {
        val objectBefore = ObjectContentStore.constructions.get()
        val s3Before = S3ObjectClient.constructions.get()

        val app = koinApplication { modules(configModule, contentModule, repositoryModule, securityModule, historyModule) }
        try {
            app.koin.get<ContentStore>().shouldBeInstanceOf<LocalContentStore>()
            app.koin.get<HistoryProvider>() // the graph-resolution site the R9 boot trap threatened
        } finally {
            app.close()
        }

        ObjectContentStore.constructions.get() shouldBe objectBefore
        S3ObjectClient.constructions.get() shouldBe s3Before
    }

    test("OBJECT boot resolves ContentStore + HistoryProvider without constructing the contentDir LocalContentStore") {
        val before = contentDirStoreConstructions.get()

        withTempDataDir { dataDir ->
            val objectConfig = module {
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
                    )
                }
            }
            val app = koinApplication { modules(objectConfig, contentModule, repositoryModule, securityModule, historyModule) }
            try {
                app.koin.get<ContentStore>().shouldBeInstanceOf<ObjectContentStore>()
                app.koin.get<HistoryProvider>() // must not force get<LocalContentStore>() (the dead provider)
            } finally {
                app.koin.get<ObjectContentStore>().close() // Koin does not auto-close singles: no leaked transport
                app.close()
            }
        }

        contentDirStoreConstructions.get() shouldBe before
    }
})

private fun withTempDataDir(block: (java.nio.file.Path) -> Unit) {
    val dir = Files.createTempDirectory("plainbase-r9-no-object-construction")
    try {
        block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
