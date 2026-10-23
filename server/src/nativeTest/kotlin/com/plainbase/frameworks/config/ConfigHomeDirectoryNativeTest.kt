package com.plainbase.frameworks.config

import com.typesafe.config.ConfigUtil
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("native")
class ConfigHomeDirectoryNativeTest {
    @Test
    fun `absent DATA_DIR selects HOME dot plainbase without creating it`() = homeFixture { base ->
        val home = base.resolve("disposable home ü")
        val env = mapOf("HOME" to home.toString())
        assertEquals(home.resolve(".plainbase"), ConfigLoader.fromEnv(env).dataDir)
        assertEquals(home.resolve(".plainbase"), ConfigLoader.dataDirFrom(env, base))
        assertFalse(Files.exists(home))
    }

    @Test
    fun `explicit paths preserve spelling semantics without consulting HOME`() = homeFixture { base ->
        listOf(base.resolve("state ü ").toString(), "relative state/../state ü ", "~/literal").forEach { explicit ->
            listOf(emptyMap(), mapOf("HOME" to "bad\u0000home")).forEach { home ->
                val env = home + ("DATA_DIR" to explicit)
                val expected = Path.of(explicit).toAbsolutePath().normalize()
                assertEquals(expected, ConfigLoader.fromEnv(env).dataDir)
                assertEquals(expected, ConfigLoader.dataDirFrom(env, base))
            }
        }
        assertEquals(Path.of("/.plainbase"), ConfigLoader.dataDirFrom(mapOf("HOME" to "/"), base))
        assertEquals(base.resolve(".plainbase"), ConfigLoader.dataDirFrom(mapOf("HOME" to "$base/"), base))
    }

    @Test
    fun `missing blank relative or invalid HOME and blank DATA_DIR refuse with keys`() = homeFixture { base ->
        val invalidHomes = listOf(
            emptyMap(), mapOf("HOME" to ""), mapOf("HOME" to "  "),
            mapOf("HOME" to "relative"), mapOf("HOME" to " /relative"), mapOf("HOME" to "bad\u0000home"),
        )
        invalidHomes.forEach { env ->
            val failure = assertFailsWith<IllegalArgumentException> { ConfigLoader.dataDirFrom(env, base) }
            assertTrue(requireNotNull(failure.message).contains("HOME"))
            assertTrue(requireNotNull(failure.message).contains("absolute DATA_DIR"))
        }
        listOf("", " \t ").forEach { blank ->
            val failure = assertFailsWith<IllegalArgumentException> {
                ConfigLoader.fromEnv(mapOf("DATA_DIR" to blank, "HOME" to base.toString()))
            }
            assertTrue(requireNotNull(failure.message).contains("DATA_DIR"))
        }
        assertFalse(Files.exists(base.resolve(".plainbase")))
    }

    @Test
    fun `each durable marker refuses without reading contents or creating state`() = homeFixture { base ->
        val old = Files.createDirectory(base.resolve("data"))
        val env = mapOf("HOME" to base.resolve("home").toString())
        listOf(
            "plainbase.db", "plainbase.db-wal", "plainbase.db-shm", "plainbase.conf", "roots.conf",
            "roots.conf.bak", "custom.css", "themes",
        ).forEach { marker ->
            val entry = old.resolve(marker)
            // A directory in place of a DB/config still counts; no parsing is appropriate here.
            Files.createDirectory(entry)
            val failure = assertFailsWith<IllegalArgumentException> { ConfigLoader.dataDirFrom(env, base) }
            assertTrue(requireNotNull(failure.message).contains(old.toString()))
            assertTrue(requireNotNull(failure.message).contains(base.resolve("home/.plainbase").toString()))
            assertTrue(requireNotNull(failure.message).contains("offline migration"))
            assertTrue(
                requireNotNull(failure.message).contains("https://github.com/luinstra/plainbase/blob/main/docs/operating-plainbase.md"),
            )
            assertFalse(Files.exists(base.resolve("home")))
            assertEquals(listOf(marker), Files.list(old).use { it.map { p -> p.fileName.toString() }.toList() })
            Files.delete(entry)
        }
    }

    @Test
    fun `both populated directories refuse but explicit override bypasses legacy`() = homeFixture { base ->
        val old = Files.createDirectory(base.resolve("data"))
        Files.writeString(old.resolve("plainbase.conf"), "malformed = {")
        val home = Files.createDirectories(base.resolve("home/.plainbase"))
        Files.writeString(home.resolve("plainbase.conf"), "host = \"127.0.0.2\"")
        val env = mapOf("HOME" to home.parent.toString())
        assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env, base) }
        assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndCandidateRoots(null, env, base) }
        assertEquals(home, ConfigLoader.fromEnvAndFile(env + ("DATA_DIR" to home.toString()), base).dataDir)
        // The env-only fast path ignores both malformed legacy files and valid home files.
        assertEquals(PlainbaseConfig.DEFAULT_HOST, ConfigLoader.fromEnv(env).host)
        assertFalse(Files.exists(home.resolve("plainbase.lock")))
        assertFalse(Files.exists(home.resolve("roots.lock")))
        assertFalse(Files.exists(home.resolve("plainbase.db")))
    }

    @Test
    fun `empty unrelated cache-only and regular-file data do not count`() = homeFixture { base ->
        val home = base.resolve("home")
        val env = mapOf("HOME" to home.toString())
        assertEquals(home.resolve(".plainbase"), ConfigLoader.dataDirFrom(env, base))
        val old = Files.createDirectory(base.resolve("data"))
        assertEquals(home.resolve(".plainbase"), ConfigLoader.dataDirFrom(env, base))
        listOf("search.db", "discussions.db", "mirror", "mirror-state", "runtime", "unrelated").forEach {
            Files.createFile(old.resolve(it))
        }
        assertEquals(home.resolve(".plainbase"), ConfigLoader.fromEnvAndFile(env, base).dataDir)
        old.toFile().deleteRecursively()
        Files.writeString(old, "ordinary file")
        assertEquals(home.resolve(".plainbase"), ConfigLoader.dataDirFrom(env, base))
        assertFalse(Files.exists(home))
    }

    @Test
    fun `symlink aliases prove identity and dangling data or marker links refuse`() = homeFixture { base ->
        val home = Files.createDirectories(base.resolve("home/.plainbase"))
        val env = mapOf("HOME" to home.parent.toString())
        Files.writeString(home.resolve("custom.css"), "/* theme */")
        val old = base.resolve("data")
        homeLink(old, home)
        assertEquals(home, ConfigLoader.fromEnvAndFile(env, base).dataDir)
        Files.delete(old)
        homeLink(old, base.resolve("missing"))
        assertFailsWith<IllegalArgumentException> { ConfigLoader.dataDirFrom(env, base) }
        Files.delete(old)
        Files.createDirectory(old)
        homeLink(old.resolve("plainbase.db"), base.resolve("missing-db"))
        assertFailsWith<IllegalArgumentException> { ConfigLoader.dataDirFrom(env, base) }
    }

    @Test
    fun `target symlink to old data permits proven same directory`() = homeFixture { base ->
        val old = Files.createDirectory(base.resolve("data"))
        Files.writeString(old.resolve("custom.css"), "/* theme */")
        val home = Files.createDirectory(base.resolve("home"))
        homeLink(home.resolve(".plainbase"), old)
        assertEquals(home.resolve(".plainbase"), ConfigLoader.dataDirFrom(mapOf("HOME" to home.toString()), base))
    }

    @Test
    fun `uninspectable legacy directory refuses and explicit DATA_DIR bypasses it`() = permissionCase(false)

    @Test
    fun `uninspectable marker lookup refuses and explicit DATA_DIR bypasses it`() = permissionCase(true)

    @Test
    fun `home layered and candidate config retain includes precedence and CWD paths`() = homeFixture { base ->
        val data = Files.createDirectories(base.resolve("home/.plainbase"))
        Files.writeString(data.resolve("included.conf"), "host = \"127.0.0.2\"\nport = 8123")
        Files.writeString(
            data.resolve("plainbase.conf"),
            "include \"included.conf\"\ncontentDir = \"relative-content\"\ndataDir = \"/ignored\"\n" +
                "maxAssetBytes = ${'$'}{maxWriteBodyBytes}\nmaxWriteBodyBytes = 12345",
        )
        val env = mapOf("HOME" to data.parent.toString())
        val file = ConfigLoader.fromEnvAndFile(env, base)
        assertEquals(data, file.dataDir)
        assertEquals("127.0.0.2", file.host)
        assertEquals(8123, file.port)
        assertEquals(12345L, file.maxAssetBytes)
        assertEquals(Path.of("relative-content").toAbsolutePath(), file.contentDir)
        val candidate = ConfigLoader.fromEnvAndCandidateRoots(
            "roots { notes { path = ${ConfigUtil.quoteString("relative-notes")} } }",
            env + ("PLAINBASE_HOST" to "127.0.0.3") + ("CONTENT_DIR" to "env-content"), base,
        )
        assertEquals(data, candidate.dataDir)
        assertEquals("127.0.0.3", candidate.host)
        assertEquals(Path.of("env-content").toAbsolutePath(), candidate.contentDir)
        assertEquals(Path.of("relative-notes").toAbsolutePath(), candidate.roots.extras.single().localPath)
        assertEquals(Path.of("content").toAbsolutePath(), ConfigLoader.fromEnv(env).contentDir)
        // Probe seam controls only legacy lookup, never resolution of relative explicit paths.
        val explicit = env + ("DATA_DIR" to "relative-state")
        assertEquals(Path.of("relative-state").toAbsolutePath(), ConfigLoader.dataDirFrom(explicit, base))
    }

    @Test
    fun `home managed backup evidence still refuses missing or damaged roots`() = homeFixture { base ->
        val data = Files.createDirectories(base.resolve("home/.plainbase"))
        Files.writeString(data.resolve("roots.conf.bak"), "roots { notes { path = \"/notes\" } }")
        val env = mapOf("HOME" to data.parent.toString())
        listOf<String?>(null, "roots {").forEach { managed ->
            if (managed != null) Files.writeString(data.resolve("roots.conf"), managed)
            val failure = assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env, base) }
            assertTrue(requireNotNull(failure.message).contains("machine-managed roots file"))
            assertTrue(Files.exists(data.resolve("roots.conf.bak")))
        }
    }
}

private fun homeFixture(block: (Path) -> Unit) {
    val base = Files.createTempDirectory("pb-home-default").toAbsolutePath()
    try {
        block(base)
    } finally {
        base.toFile().deleteRecursively()
    }
}

private fun homeLink(link: Path, target: Path) {
    try {
        Files.createSymbolicLink(link, target)
    } catch (failure: UnsupportedOperationException) {
        assumeTrue(false, "symbolic links unsupported: ${failure.message}")
    }
}

private fun permissionCase(markerLookup: Boolean) = homeFixture { base ->
    assumeTrue("posix" in base.fileSystem.supportedFileAttributeViews(), "POSIX permissions unavailable")
    val probe = Files.createDirectory(base.resolve("probe"))
    val old = Files.createDirectory(probe.resolve("data"))
    Files.writeString(old.resolve("plainbase.db"), "not opened")
    val restricted = if (markerLookup) old else probe
    val inspected = if (markerLookup) old.resolve("plainbase.db") else old
    val original = Files.getPosixFilePermissions(restricted)
    Files.setPosixFilePermissions(restricted, PosixFilePermissions.fromString("---------"))
    try {
        val failure = runCatching {
            Files.readAttributes(inspected, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.exceptionOrNull()
        assumeTrue(failure is IOException, "permission drop did not prevent inspection (elevated user/filesystem)")
        val env = mapOf("HOME" to base.resolve("home").toString())
        val refusal = assertFailsWith<IllegalArgumentException> { ConfigLoader.dataDirFrom(env, probe) }
        assertTrue(requireNotNull(refusal.message).contains("Cannot inspect legacy"))
        val explicit = base.resolve("explicit")
        assertEquals(explicit, ConfigLoader.dataDirFrom(env + ("DATA_DIR" to explicit.toString()), probe))
        assertFalse(Files.exists(base.resolve("home")))
        assertFalse(Files.exists(explicit))
    } finally {
        Files.setPosixFilePermissions(restricted, original)
    }
}
