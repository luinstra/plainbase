package com.plainbase.frameworks.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigUtil
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Characterizes real configuration-loader seams in the closed-world test image. The same class is folded
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

            val envOnly = ConfigLoader.fromEnv(env)
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

            assertFailsWith<ConfigException.Parse> { ConfigLoader.fromEnvAndFile(env) }
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

            val layered = ConfigLoader.fromEnvAndFile(env)
            assertEquals(dataA.toAbsolutePath().normalize(), layered.dataDir)
            assertEquals("127.0.0.2", layered.host)

            val candidate = ConfigLoader.fromEnvAndCandidateRoots(null, env)
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
            val hostFromEnv = ConfigLoader.fromEnvAndFile(env)
            assertEquals("127.0.0.2", hostFromEnv.host)

            Files.writeString(plainbase, "contentDir = []")
            val failure = assertFailsWith<ConfigException.WrongType> { ConfigLoader.fromEnvAndFile(env) }
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

            val fileValues = ConfigLoader.fromEnvAndFile(env)
            assertEquals(PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES, fileValues.maxWriteBodyBytes)
            assertEquals(PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES, fileValues.maxAssetBytes)

            val envValues = ConfigLoader.fromEnvAndFile(
                env +
                    ("PLAINBASE_MAX_WRITE_BODY_BYTES" to "2097152") +
                    ("PLAINBASE_MAX_ASSET_BYTES" to "3145728"),
            )
            assertEquals(2_097_152L, envValues.maxWriteBodyBytes)
            assertEquals(3_145_728L, envValues.maxAssetBytes)

            listOf("PLAINBASE_MAX_WRITE_BODY_BYTES", "PLAINBASE_MAX_ASSET_BYTES").forEach { key ->
                listOf("0", "-1").forEach { value ->
                    val failure = assertFailsWith<IllegalArgumentException> {
                        ConfigLoader.fromEnvAndFile(env + (key to value))
                    }
                    assertTrue(requireNotNull(failure.message).contains(key))
                }
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `operator HOCON resolves a real process environment value before the injected map`() {
        val processPath = System.getenv("PATH")
        assertTrue(!processPath.isNullOrEmpty(), "PATH must be present in the real process environment")
        val base = Files.createTempDirectory("pb-config-compat-process-env")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("content")
            val injectedPath = "deliberately-not-the-process-path"
            Files.writeString(data.resolve("plainbase.conf"), "host = ${'$'}{PATH}")
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString(), "PATH" to injectedPath)

            val fromProcess = ConfigLoader.fromEnvAndFile(env)
            assertEquals(processPath, fromProcess.host)
            assertTrue(fromProcess.host != injectedPath)
            assertEquals("127.0.0.2", ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_HOST" to "127.0.0.2")).host)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a retained config snapshot does not observe a later managed roots replacement`() {
        val base = Files.createTempDirectory("pb-config-compat-retained")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("content")
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
            val managed = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            Files.writeString(managed, "roots { notes { path = \"${base.resolve("notes")}\" } }")

            val retained = ConfigLoader.fromEnvAndFile(env)
            Files.writeString(managed, "roots { archive { path = \"${base.resolve("archive")}\" } }")

            assertEquals(listOf("docs", "notes"), retained.roots.list.map { it.name.value })
            assertEquals(RootsOrigin.EXPLICIT, retained.roots.origin)
            assertEquals(false, retained.roots.primaryDeclared)
            assertEquals(setOf("notes"), retained.roots.managed.map { it.value }.toSet())

            val fresh = ConfigLoader.fromEnvAndFile(env)
            assertEquals(listOf("docs", "archive"), fresh.roots.list.map { it.name.value })
            assertEquals(setOf("archive"), fresh.roots.managed.map { it.value }.toSet())
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `operator directory refuses through both configuration entry points`() {
        val base = Files.createTempDirectory("pb-config-compat-directory")
        try {
            val operator = Files.createDirectory(base.resolve("plainbase.conf"))
            val env = mapOf("DATA_DIR" to base.toString())
            val fullFailure = assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
            assertTrue(requireNotNull(fullFailure.message).contains(operator.toString()))
            val profileFailure = assertFailsWith<IllegalArgumentException> { ConfigLoader.profileFrom(env) }
            assertTrue(requireNotNull(profileFailure.message).contains(operator.toString()))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `operator regular symlink follows the host and profile outside the data directory`() {
        val base = Files.createTempDirectory("pb-config-compat-symlink")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = base.resolve("operator-target.conf")
            Files.writeString(target, "host = \"127.0.0.2\"\nprofile = reader")
            val operator = data.resolve("plainbase.conf")
            createSymbolicLinkOrSkip(operator, target)
            assertTrue(Files.isRegularFile(operator))
            assertTrue(Files.readAttributes(operator, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isSymbolicLink)
            val env = mapOf("DATA_DIR" to data.toString())
            assertEquals("127.0.0.2", ConfigLoader.fromEnvAndFile(env).host)
            assertEquals(DeploymentProfile.READER, ConfigLoader.profileFrom(env))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `operator dangling symlink refuses through both configuration entry points`() {
        val base = Files.createTempDirectory("pb-config-compat-dangling")
        try {
            val operator = base.resolve("plainbase.conf")
            createSymbolicLinkOrSkip(operator, Path.of("missing.conf"))
            val env = mapOf("DATA_DIR" to base.toString())
            val fullFailure = assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
            assertTrue(requireNotNull(fullFailure.message).contains(operator.toString()))
            val profileFailure = assertFailsWith<IllegalArgumentException> { ConfigLoader.profileFrom(env) }
            assertTrue(requireNotNull(profileFailure.message).contains(operator.toString()))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unreadable operator file refuses through both configuration entry points`() {
        val base = Files.createTempDirectory("pb-config-compat-unreadable")
        val operator = base.resolve("plainbase.conf")
        try {
            Files.writeString(operator, "profile = reader")
            Files.setPosixFilePermissions(operator, emptySet())
            assumeTrue(!Files.isReadable(operator), "mode-000 operator file must be unreadable to this process")
            val env = mapOf("DATA_DIR" to base.toString())
            assertFailsWith<ConfigException> { ConfigLoader.fromEnvAndFile(env) }
            assertFailsWith<ConfigException> { ConfigLoader.profileFrom(env) }
        } finally {
            if (Files.exists(operator)) Files.setPosixFilePermissions(operator, setOf(PosixFilePermission.OWNER_READ))
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `inaccessible operator parent reports unknown file type and permissions through both entry points`() {
        val base = Files.createTempDirectory("pb-config-compat-inaccessible")
        val data = Files.createDirectory(base.resolve("data"))
        val permissions = Files.getPosixFilePermissions(data)
        try {
            val operator = Files.writeString(data.resolve("plainbase.conf"), "profile = reader")
            Files.setPosixFilePermissions(data, emptySet())
            assumeTrue(!Files.isExecutable(data), "mode-000 data directory must deny traversal to this process")
            assertTrue(!Files.notExists(operator, LinkOption.NOFOLLOW_LINKS))
            val env = mapOf("DATA_DIR" to data.toString())
            val fullFailure = assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
            val profileFailure = assertFailsWith<IllegalArgumentException> { ConfigLoader.profileFrom(env) }
            listOf(fullFailure, profileFailure).forEach { failure ->
                val message = requireNotNull(failure.message)
                assertTrue(message.contains(operator.toString()))
                assertTrue(message.contains("regular file"))
                assertTrue(message.contains("cannot be determined"))
                assertTrue(message.contains("permissions"))
            }
        } finally {
            Files.setPosixFilePermissions(data, permissions)
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `absent operator file loads workspace defaults through both configuration entry points`() {
        val base = Files.createTempDirectory("pb-config-compat-absent")
        try {
            val env = mapOf("DATA_DIR" to base.toString())
            val loaded = ConfigLoader.fromEnvAndFile(env)
            assertEquals(PlainbaseConfig.DEFAULT_HOST, loaded.host)
            assertEquals(DeploymentProfile.WORKSPACE, loaded.profile)
            assertEquals(DeploymentProfile.WORKSPACE, ConfigLoader.profileFrom(env))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `environment selects reader with an absent operator file through both entry points`() {
        withProfileInputs(null, " ReAdEr ", DeploymentProfile.READER)
    }

    @Test
    fun `environment selects reader with an absent profile key through both entry points`() {
        withProfileInputs("host = localhost", "reader", DeploymentProfile.READER)
    }

    @Test
    fun `environment selects reader over a workspace file through both entry points`() {
        withProfileInputs("profile = workspace", "reader", DeploymentProfile.READER)
    }

    @Test
    fun `blank environment profile leaves the file in control through both entry points`() {
        withProfileInputs(null, " \t ", DeploymentProfile.WORKSPACE)
        withProfileInputs("profile = reader", "", DeploymentProfile.READER)
    }

    @Test
    fun `nonblank environment profile accepts only reader through both entry points`() {
        val base = Files.createTempDirectory("pb-config-profile-invalid")
        try {
            Files.writeString(base.resolve("plainbase.conf"), "profile = reader")
            val env = mapOf("DATA_DIR" to base.toString(), "PLAINBASE_PROFILE" to "workspace")
            val failures = listOf(
                assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) },
                assertFailsWith<IllegalArgumentException> { ConfigLoader.profileFrom(env) },
            )
            failures.forEach { failure ->
                assertTrue(requireNotNull(failure.message).contains("PLAINBASE_PROFILE"))
                assertTrue(requireNotNull(failure.message).contains("only accepted value is `reader`"))
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun withProfileInputs(conf: String?, value: String, expected: DeploymentProfile) {
        val base = Files.createTempDirectory("pb-config-profile")
        try {
            if (conf != null) Files.writeString(base.resolve("plainbase.conf"), conf)
            val env = mapOf("DATA_DIR" to base.toString(), "PLAINBASE_PROFILE" to value)
            assertEquals(expected, ConfigLoader.fromEnvAndFile(env).profile)
            assertEquals(expected, ConfigLoader.profileFrom(env))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed entries retain regular-file follow behavior across NIO entry kinds`() {
        val base = Files.createTempDirectory("pb-config-compat-entries")
        try {
            val content = base.resolve("content")
            val managedCases = listOf("directory", "regular symlink", "dangling symlink")
            managedCases.forEach { kind ->
                val data = Files.createDirectory(base.resolve("managed-${kind.replace(' ', '-')}"))
                val managed = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
                when (kind) {
                    "directory" -> Files.createDirectory(managed)
                    "regular symlink" -> {
                        val target = data.resolve("managed-target.conf")
                        Files.writeString(target, "roots { notes { path = \"${base.resolve("notes")}\" } }")
                        createSymbolicLinkOrSkip(managed, target.fileName)
                        assertTrue(Files.isRegularFile(managed))
                    }
                    else -> {
                        val target = data.resolve("managed-missing.conf")
                        createSymbolicLinkOrSkip(managed, target.fileName)
                        assertTrue(!Files.isRegularFile(managed))
                    }
                }
                val loaded = ConfigLoader.fromEnvAndFile(
                    mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString()),
                )
                if (kind == "regular symlink") {
                    assertEquals(listOf("docs", "notes"), loaded.roots.list.map { it.name.value })
                } else {
                    assertEquals(listOf("docs"), loaded.roots.list.map { it.name.value })
                }
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `paired invalid fromEnv values preserve storage then data directory before port and auth`() {
        val content = Files.createTempDirectory("pb-config-compat-order-content")
        try {
            val invalidDataDir = "bad\u0000data"
            val storageFailure = assertFailsWith<IllegalArgumentException> {
                ConfigLoader.fromEnv(
                    mapOf(
                        "DATA_DIR" to invalidDataDir,
                        "CONTENT_DIR" to content.toString(),
                        "PLAINBASE_STORAGE_BACKEND" to "object",
                        "PLAINBASE_S3_BUCKET" to "docs",
                        "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
                        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
                        "PLAINBASE_PORT" to "not-a-port",
                        "PLAINBASE_AUTH_MODE" to "not-a-mode",
                    ),
                )
            }
            assertEquals(
                "storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)",
                storageFailure.message,
            )

            val dataDirFailure = assertFailsWith<InvalidPathException> {
                ConfigLoader.fromEnv(
                    mapOf(
                        "DATA_DIR" to invalidDataDir,
                        "CONTENT_DIR" to content.toString(),
                        "PLAINBASE_STORAGE_BACKEND" to "object",
                        "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                        "PLAINBASE_S3_BUCKET" to "docs",
                        "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
                        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
                        "PLAINBASE_PORT" to "not-a-port",
                        "PLAINBASE_AUTH_MODE" to "not-a-mode",
                    ),
                )
            }
            assertEquals(invalidDataDir, dataDirFailure.input)
            assertEquals("Nul character not allowed", dataDirFailure.reason)
        } finally {
            content.toFile().deleteRecursively()
        }
    }

    @Test
    fun `object mode file poll seconds zero and negative values use the exact default`() {
        val base = Files.createTempDirectory("pb-config-compat-poll")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("content")
            val env = mapOf(
                "DATA_DIR" to data.toString(),
                "CONTENT_DIR" to content.toString(),
                "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
                "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
            )
            listOf(0, -1).forEach { pollSeconds ->
                Files.writeString(
                    data.resolve("plainbase.conf"),
                    """
                    storage {
                      backend = object
                      object {
                        endpoint = "https://acct.example.com"
                        bucket = "docs"
                        pollSeconds = $pollSeconds
                      }
                    }
                    """.trimIndent(),
                )

                val config = ConfigLoader.fromEnvAndFile(env)
                assertEquals(PlainbaseConfig.DEFAULT_S3_POLL_SECONDS, config.storage.pollSeconds)
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}

private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
    try {
        Files.createSymbolicLink(link, target)
    } catch (failure: UnsupportedOperationException) {
        assumeTrue(false, "the test filesystem does not support symbolic links: ${failure.message}")
    }
}
