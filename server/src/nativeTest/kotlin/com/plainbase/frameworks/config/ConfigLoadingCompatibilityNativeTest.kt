package com.plainbase.frameworks.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigUtil
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Characterizes four real configuration-loader seams in the closed-world test image. The same class is folded
 * into the JVM test task, so each case is executed in both environments without a duplicate test implementation.
 */
@Tag("native")
class ConfigLoadingCompatibilityNativeTest {

    @Test
    fun `env-only loading ignores malformed config files and backup evidence`() {
        val base = Files.createTempDirectory("pb-config-compat-env-only")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("content")
            val plainbase = data.resolve("plainbase.conf")
            val managed = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            val backup = data.resolve("${PlainbaseConfig.MANAGED_ROOTS_FILE}${ManagedRootsFile.BACKUP_SUFFIX}")
            Files.writeString(plainbase, "auth { mode =")
            Files.writeString(managed, "roots {")
            Files.writeString(backup, "roots {")
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
            val plainbaseBefore = Files.readAllBytes(plainbase)
            val managedBefore = Files.readAllBytes(managed)
            val backupBefore = Files.readAllBytes(backup)

            val envOnly = PlainbaseConfig.fromEnv(env)
            assertEquals(content.toAbsolutePath().normalize(), envOnly.contentDir)
            assertEquals(data.toAbsolutePath().normalize(), envOnly.dataDir)
            assertEquals(ConfigSource.ENV, envOnly.contentDirSource)
            assertEquals(PlainbaseConfig.DEFAULT_HOST, envOnly.host)
            assertEquals(PlainbaseConfig.DEFAULT_PORT, envOnly.port)
            assertEquals(PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES, envOnly.maxWriteBodyBytes)
            assertEquals(PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES, envOnly.maxAssetBytes)
            assertEquals(RootsOrigin.SYNTHESIZED, envOnly.roots.origin)
            assertEquals("docs", envOnly.roots.primary.name.value)
            assertContentEquals(plainbaseBefore, Files.readAllBytes(plainbase))
            assertContentEquals(managedBefore, Files.readAllBytes(managed))
            assertContentEquals(backupBefore, Files.readAllBytes(backup))

            assertFailsWith<ConfigException.Parse> { PlainbaseConfig.fromEnvAndFile(env) }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `file-side data directory cannot redirect layered loading`() {
        val base = Files.createTempDirectory("pb-config-compat-data-dir")
        try {
            val dataA = Files.createDirectory(base.resolve("data-a"))
            val dataB = Files.createDirectory(base.resolve("data-b"))
            val content = base.resolve("content")
            Files.writeString(
                dataA.resolve("plainbase.conf"),
                "dataDir = ${ConfigUtil.quoteString(dataB.toString())}\nhost = \"127.0.0.2\"",
            )
            Files.writeString(dataB.resolve("plainbase.conf"), "host = \"127.0.0.3\"")
            val env = mapOf("DATA_DIR" to dataA.toString(), "CONTENT_DIR" to content.toString())

            val layered = PlainbaseConfig.fromEnvAndFile(env)
            assertEquals(dataA.toAbsolutePath().normalize(), layered.dataDir)
            assertEquals("127.0.0.2", layered.host)

            val candidate = PlainbaseConfig.fromEnvAndCandidateRoots(null, env)
            assertEquals(dataA.toAbsolutePath().normalize(), candidate.dataDir)
            assertEquals("127.0.0.2", candidate.host)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shadowed invalid fields preserve eager content and lazy host reads`() {
        val base = Files.createTempDirectory("pb-config-compat-shadowed")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("content")
            val env = mapOf(
                "DATA_DIR" to data.toString(),
                "CONTENT_DIR" to content.toString(),
                "PLAINBASE_HOST" to "127.0.0.2",
            )
            val plainbase = data.resolve("plainbase.conf")

            Files.writeString(plainbase, "host = []")
            val hostFromEnv = PlainbaseConfig.fromEnvAndFile(env)
            assertEquals("127.0.0.2", hostFromEnv.host)

            Files.writeString(plainbase, "contentDir = []")
            val failure = assertFailsWith<ConfigException.WrongType> { PlainbaseConfig.fromEnvAndFile(env) }
            assertTrue(requireNotNull(failure.message).contains("contentDir"))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nonpositive file sizes fall back while nonpositive environment sizes refuse`() {
        val base = Files.createTempDirectory("pb-config-compat-sizes")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("content")
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
            Files.writeString(data.resolve("plainbase.conf"), "maxWriteBodyBytes = 0\nmaxAssetBytes = -1")

            val fileValues = PlainbaseConfig.fromEnvAndFile(env)
            assertEquals(PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES, fileValues.maxWriteBodyBytes)
            assertEquals(PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES, fileValues.maxAssetBytes)

            val envValues = PlainbaseConfig.fromEnvAndFile(
                env +
                    ("PLAINBASE_MAX_WRITE_BODY_BYTES" to "2097152") +
                    ("PLAINBASE_MAX_ASSET_BYTES" to "3145728"),
            )
            assertEquals(2_097_152L, envValues.maxWriteBodyBytes)
            assertEquals(3_145_728L, envValues.maxAssetBytes)

            listOf("PLAINBASE_MAX_WRITE_BODY_BYTES", "PLAINBASE_MAX_ASSET_BYTES").forEach { key ->
                listOf("0", "-1").forEach { value ->
                    val failure = assertFailsWith<IllegalArgumentException> {
                        PlainbaseConfig.fromEnvAndFile(env + (key to value))
                    }
                    assertTrue(requireNotNull(failure.message).contains(key))
                }
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
