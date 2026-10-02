package com.plainbase.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedBuildContractTest {
    @TempDir
    lateinit var root: Path

    private val identity = SharedBuildIdentity("a".repeat(40), "b".repeat(64), "local-contract", "0.0.0-ci")
    private val archives =
        listOf("server/build/libs/server.jar", "server/build/distributions/plainbase.tar", "server/build/distributions/plainbase.zip")
    private val contract = SharedBuildContract(archives)
    private val dependencies = SharedBuildContract.configurations.associateWith {
        listOf(SharedDependency("org.example:library:1|library.jar", "usage=java-runtime", "library.jar", "c".repeat(64)))
    }

    private fun write(name: String, bytes: ByteArray = "output".toByteArray(), executable: Boolean = false) {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
        check(file.toFile().setExecutable(executable, false))
    }

    private fun fixture(): Path {
        listOf("main", "test", "nativeTest").forEach { set ->
            write(
                "server/build/classes/kotlin/$set/Example.class",
                byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 69),
            )
            write("server/build/classes/kotlin/$set/META-INF/example.kotlin_module")
        }
        listOf(
            "frontend/dist/index.html", "frontend/dist/assets/app.js",
            "server/build/generated/source/buildInfo/kotlin/com/plainbase/BuildInfo.kt",
            "server/build/generated/sqldelight/code/PlainbaseDb/main/Database.kt",
            "server/build/generated/frontend/static/index.html", "server/build/resources/main/static/index.html",
            "server/build/resources/main/META-INF/native-image/reflect-config.json", "server/build/install/plainbase/lib/server.jar",
        ).forEach { write(it) }
        write("server/build/scripts/plainbase", executable = true)
        write("server/build/install/plainbase/bin/plainbase", executable = true)
        archives.forEach { write(it) }
        val bundle = root.resolve("build/ci-shared/shared-build.zip")
        contract.prepare(root, bundle, identity, dependencies, mapOf("attempt" to "1"))
        return bundle
    }

    @Test
    fun `should restore absent outputs and purge stale classes while preserving unrelated reports and archives`() {
        // Arrange
        val bundle = fixture()
        SharedBuildFiles.deleteTree(root.resolve("frontend/dist"))
        write("server/build/classes/kotlin/test/Stale.class")
        write("server/build/reports/keep.txt")
        write("server/build/libs/unrelated.jar")
        // Act
        assertTrue(contract.consume(root, bundle, SharedBuildFiles.sha256(bundle), identity, dependencies))
        // Assert
        assertFalse(Files.exists(root.resolve("server/build/classes/kotlin/test/Stale.class")))
        assertTrue(Files.isExecutable(root.resolve("server/build/install/plainbase/bin/plainbase")))
        assertTrue(Files.exists(root.resolve("server/build/reports/keep.txt")))
        assertTrue(Files.exists(root.resolve("server/build/libs/unrelated.jar")))
        assertFalse(contract.consume(root, bundle, SharedBuildFiles.sha256(bundle), identity, dependencies))
    }

    @Test
    fun `should fail identity and transport mismatches before changing destinations`() {
        val bundle = fixture()
        val hash = SharedBuildFiles.sha256(bundle)
        val identities = listOf(
            identity.copy(commit = "d".repeat(40)), identity.copy(source = "d".repeat(64)),
            identity.copy(run = "local-other"), identity.copy(version = "0.1.0-SNAPSHOT"),
        )
        write("server/build/classes/kotlin/test/Stale.class")
        identities.forEach { changed ->
            assertFailsWith<IllegalArgumentException> { contract.consume(root, bundle, hash, changed, dependencies) }
            assertTrue(Files.exists(root.resolve("server/build/classes/kotlin/test/Stale.class")))
        }
        assertFailsWith<IllegalArgumentException> { contract.consume(root, bundle, "0".repeat(64), identity, dependencies) }
    }

    @Test
    fun `should reuse exact file inventories despite empty output directories created by Gradle`() {
        val bundle = fixture()
        val absent = root.resolve("server/build/classes/java/main")
        Files.createDirectories(absent)
        assertFalse(contract.consume(root, bundle, SharedBuildFiles.sha256(bundle), identity, dependencies))
        assertFalse(Files.exists(absent))
    }

    @Test
    fun `should reject missing extra coordinates variants filenames and hashes in every dependency inventory`() {
        val bundle = fixture()
        val hash = SharedBuildFiles.sha256(bundle)
        dependencies.forEach { (configuration, entries) ->
            val entry = entries.single()
            listOf(
                emptyList(), entries + entry.copy(file = "extra.jar"), listOf(entry.copy(coordinates = "changed")),
                listOf(entry.copy(variant = "changed")), listOf(entry.copy(file = "changed.jar")),
                listOf(entry.copy(sha256 = "0".repeat(64))),
            ).forEach { changed ->
                val error = assertFailsWith<IllegalArgumentException> {
                    contract.consume(root, bundle, hash, identity, dependencies + (configuration to changed))
                }
                assertTrue(error.message.orEmpty().contains(configuration))
            }
        }
    }

    @Test
    fun `should round trip snapshot and release identities and reject missing logical outputs`() {
        val bundle = fixture()
        listOf("0.1.0-SNAPSHOT", "1.2.3-rc.1").forEach { version ->
            val versioned = identity.copy(version = version)
            contract.prepare(root, bundle, versioned, dependencies, mapOf("attempt" to "2"))
            assertFalse(contract.consume(root, bundle, SharedBuildFiles.sha256(bundle), versioned, dependencies))
        }
        Files.delete(root.resolve("server/build/classes/kotlin/test/META-INF/example.kotlin_module"))
        assertFailsWith<IllegalArgumentException> { contract.prepare(root, bundle, identity, dependencies, emptyMap()) }
    }

    private fun rewrite(bundle: Path, extra: String? = null, corrupt: String? = null, listedExtra: Boolean = false): Path {
        val changed = root.resolve("changed.zip")
        val extraBytes = "extra".toByteArray()
        ZipFile(bundle.toFile()).use { original ->
            val manifest = Properties().apply { original.getInputStream(original.getEntry("manifest.properties")).use(::load) }
            if (listedExtra) {
                val index = manifest.getProperty("output.count").toInt()
                manifest.setProperty("output.$index.path", requireNotNull(extra))
                manifest.setProperty("output.$index.sha256", extraBytes.inputStream().use(SharedBuildFiles::sha256))
                manifest.setProperty("output.$index.executable", "false")
                manifest.setProperty("output.count", (index + 1).toString())
                val records = (0..index).map { output ->
                    listOf("path", "sha256", "executable").map { field -> manifest.getProperty("output.$output.$field") }
                }
                manifest.setProperty("output.digest", digestRecords(records))
            }
            ZipOutputStream(Files.newOutputStream(changed)).use { zip ->
                original.entries().toList().forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    when {
                        entry.name == "manifest.properties" && listedExtra -> manifest.store(zip, "Manifest-listed output fixture")
                        entry.name == corrupt -> zip.write("corrupt".toByteArray())
                        else -> original.getInputStream(entry).use { it.copyTo(zip) }
                    }
                    zip.closeEntry()
                }
                if (extra != null) {
                    zip.putNextEntry(ZipEntry(extra))
                    zip.write(extraBytes)
                    zip.closeEntry()
                }
            }
        }
        return changed
    }

    @Test
    fun `should reject corrupt payload and unsafe or unlisted zip paths`() {
        val bundle = fixture()
        val corrupt = rewrite(bundle, corrupt = "frontend/dist/index.html")
        assertFailsWith<IllegalArgumentException> {
            contract.consume(root, corrupt, SharedBuildFiles.sha256(corrupt), identity, dependencies)
        }
        listOf("../escape", "/absolute", "server/build/reports/injected.txt", "frontend/dist/extra.js").forEach { path ->
            val changed = rewrite(bundle, extra = path)
            assertFailsWith<IllegalArgumentException> {
                contract.consume(root, changed, SharedBuildFiles.sha256(changed), identity, dependencies)
            }
        }
    }

    @Test
    fun `should reject manifest listed unsafe and disallowed outputs before changing destinations`() {
        // Arrange: preserve valid identity/dependency metadata and recompute both output and transport digests.
        val bundle = fixture()
        val sentinel = "server/build/classes/kotlin/test/Stale.class"
        write(sentinel, "keep destination".toByteArray())
        val diagnostics = listOf(
            "../escape" to "Unsafe path: ../escape",
            "frontend/dist/../escape" to "Unsafe path: frontend/dist/../escape",
            "/absolute" to "Unsafe path: /absolute",
            "server/build/reports/injected.txt" to "Output outside allowlist: server/build/reports/injected.txt",
        )
        diagnostics.forEach { (path, diagnostic) ->
            val changed = rewrite(bundle, extra = path, listedExtra = true)
            // Act: the listed payload reaches path validation after the output inventory digest check.
            val error = assertFailsWith<IllegalArgumentException> {
                contract.consume(root, changed, SharedBuildFiles.sha256(changed), identity, dependencies)
            }
            // Assert
            assertEquals(diagnostic, error.message)
            assertEquals("keep destination", Files.readString(root.resolve(sentinel)))
        }
    }

    @Test
    fun `should reject producer and destination symlinks without following them`() {
        val bundle = fixture()
        val library = root.resolve("server/build/install/plainbase/lib/server.jar")
        Files.delete(library)
        Files.createSymbolicLink(library, bundle)
        assertFailsWith<IllegalArgumentException> {
            contract.prepare(root, root.resolve("producer.zip"), identity, dependencies, emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            contract.consume(root, bundle, SharedBuildFiles.sha256(bundle), identity, dependencies)
        }
        assertTrue(Files.isSymbolicLink(library))
    }

    @Test
    fun `should reject special zip entries including symlinks`() {
        val bundle = fixture()
        val bytes = Files.readAllBytes(bundle)
        val index = (0..bytes.size - 46).first { i ->
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()
        }
        bytes[index + 41] = 0xa0.toByte() // Unix symlink type in central-directory external attributes.
        Files.write(bundle, bytes)
        assertFailsWith<IllegalArgumentException> {
            contract.consume(root, bundle, SharedBuildFiles.sha256(bundle), identity, dependencies)
        }
    }

    @Test
    fun `should reject duplicate zip entries before extraction`() {
        val bundle = fixture()
        val bytes = Files.readAllBytes(bundle)
        val source = "server/build/classes/kotlin/main/Example.class".toByteArray()
        val duplicate = "server/build/classes/kotlin/test/Example.class".toByteArray()
        for (index in 0..bytes.size - source.size) {
            if (source.indices.all { bytes[index + it] == source[it] }) duplicate.copyInto(bytes, index)
        }
        Files.write(bundle, bytes)
        val error = assertFailsWith<IllegalArgumentException> {
            contract.consume(root, bundle, SharedBuildFiles.sha256(bundle), identity, dependencies)
        }
        assertTrue(error.message.orEmpty().contains("Duplicate ZIP"))
    }

    @Test
    fun `should bind run identity without binding a consumer to the producer attempt`() {
        assertEquals(
            "123",
            SharedBuildPlugin.runIdentity(
                mapOf(
                    "GITHUB_ACTIONS" to "true", "GITHUB_RUN_ID" to "123",
                    "GITHUB_RUN_ATTEMPT" to "3",
                ),
                null,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            SharedBuildPlugin.runIdentity(mapOf("GITHUB_ACTIONS" to "true", "GITHUB_RUN_ID" to "123"), "local-override")
        }
        assertFailsWith<IllegalArgumentException> { SharedBuildPlugin.runIdentity(emptyMap(), null) }
        assertEquals("local-test", SharedBuildPlugin.runIdentity(emptyMap(), "local-test"))
    }
}
