package com.plainbase.frameworks.config

import com.plainbase.domain.root.BootRefusal
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Filesystem listing edges for an object reader's disposable state directory. */
@Tag("native")
class ReaderDataDirNativeTest {
    @Test
    fun `reader data directory permits a process lock file beside operator configuration`() {
        withReaderConfig { config, _ ->
            Files.writeString(config.dataDir.resolve("plainbase.lock"), "")
            assertEquals(emptyList(), ConfigBootInspector.readerDataDirRefusals(config))
        }
    }

    @Test
    fun `missing reader data directory refuses freshness inspection`() {
        withReaderConfig { config, base ->
            val missing = config.copy(dataDir = base.resolve("missing"))
            val refusals = ConfigBootInspector.readerDataDirRefusals(missing)
            assertEquals(listOf(BootRefusal.Kind.READER_DATA_DIR), refusals.map { it.kind })
            assertTrue(refusals.single().message.contains("cannot list DATA_DIR to prove it is fresh"))
        }
    }

    @Test
    fun `regular file as reader data directory refuses freshness inspection`() {
        withReaderConfig { config, base ->
            val file = Files.writeString(base.resolve("not-a-directory"), "")
            val refusals = ConfigBootInspector.readerDataDirRefusals(config.copy(dataDir = file))
            assertEquals(listOf(BootRefusal.Kind.READER_DATA_DIR), refusals.map { it.kind })
            assertTrue(refusals.single().message.contains("cannot list DATA_DIR to prove it is fresh"))
        }
    }

    @Test
    fun `real reader data directory with only operator configuration passes freshness inspection`() {
        withReaderConfig { config, _ ->
            assertEquals(emptyList(), ConfigBootInspector.readerDataDirRefusals(config))
        }
    }

    private fun withReaderConfig(block: (PlainbaseConfig, Path) -> Unit) {
        val base = Files.createTempDirectory("pb-reader-data-dir")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            Files.writeString(data.resolve("plainbase.conf"), "profile = reader")
            val config = ConfigLoader.fromEnvAndFile(
                mapOf(
                    "DATA_DIR" to data.toString(),
                    "PLAINBASE_STORAGE_BACKEND" to "object",
                    "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                    "PLAINBASE_S3_BUCKET" to "docs",
                    "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
                    "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
                    "PLAINBASE_S3_PREFIX" to "releases/r1",
                ),
            )
            assertEquals(DeploymentProfile.READER, config.profile)
            assertEquals(StorageBackend.OBJECT, config.storage.backend)
            block(config, base)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
