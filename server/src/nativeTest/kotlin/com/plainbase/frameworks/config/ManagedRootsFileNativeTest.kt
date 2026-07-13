package com.plainbase.frameworks.config

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.filesystem.FileAtomics
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `roots.conf` writer, over the two divergence surfaces project policy native-tags by default: **charset
 * decoding** (a path with a non-ASCII character, a quote and a backslash) and **NIO** (the atomic promote).
 *
 * **The round trip goes through `PlainbaseConfig.fromEnvAndFile`, NOT through a reader in `ManagedRootsFile` -
 * because there ISN'T one.** That is the point. A twin parser that agreed with the writer while both disagreed
 * with the server is exactly the drift this test exists to exclude, so the only parser whose agreement means
 * anything is the one the server will actually use at boot.
 */
@Tag("native")
class ManagedRootsFileNativeTest {

    private fun root(name: String, path: String) = Root(
        name = RootName.require(name),
        backend = RootBackend.Local(Path.of(path).toAbsolutePath().normalize()),
        editable = true,
        history = HistoryMode.OFF,
    )

    @Test
    fun `a path carrying a non-ASCII character, a quote and a backslash round-trips through the REAL loader`() {
        val base = Files.createTempDirectory("pb-managed-native")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            // A quote and a backslash are what the HOCON quoting must escape; the non-ASCII character is what the
            // UTF-8 decode must survive on both sides of the write.
            val awkward = base.resolve("""docs-ünïcode-"quoted"-back\slash""")
            val written = root("notes", awkward.toString())

            val hocon = ManagedRootsFile.serialize(listOf(written))
            ManagedRootsFile.writeAtomically(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), hocon)

            val loaded = PlainbaseConfig.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString()),
            ).roots

            assertEquals(listOf(written), loaded.extras, "the writer and the SERVER'S loader must agree, byte for byte")
            assertEquals(setOf(RootName.require("notes")), loaded.managed)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * The ONE residual assumption the in-memory candidate mechanism rests on: `parseString` of text T versus
     * `parseFile` of a file CONTAINING T. They agree - same parser, UTF-8 on both sides, and the writer pins
     * `Charsets.UTF_8` - but the whole gate is built on that, so it is PINNED rather than assumed.
     */
    @Test
    fun `the in-memory candidate and the on-disk file parse to the SAME roots - the gate's load-bearing assumption`() {
        val base = Files.createTempDirectory("pb-managed-native-eq")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())
            val awkward = base.resolve("""tree-ünïcode-"q"-back\slash""")
            val text = ManagedRootsFile.serialize(listOf(root("notes", awkward.toString())))

            // The CLI validates THIS - the string, in memory, before anything exists on disk...
            val candidate = PlainbaseConfig.fromEnvAndCandidateRoots(text, env).roots

            // ...and then writes exactly those bytes. The next boot parses the FILE.
            ManagedRootsFile.writeAtomically(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), text)
            val onDisk = PlainbaseConfig.fromEnvAndFile(env).roots

            assertEquals(candidate.list, onDisk.list, "the artifact validated must BE the artifact served")
            assertEquals(candidate.managed, onDisk.managed)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the promote is atomic and a failed promote leaves the previous file intact`() {
        val base = Files.createTempDirectory("pb-managed-native-atomic")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)

            val first = ManagedRootsFile.serialize(listOf(root("alpha", base.resolve("a").toString())))
            ManagedRootsFile.writeAtomically(target, first)
            val before = Files.readAllBytes(target)

            // A control character cannot be round-tripped and is REFUSED outright - a path with a newline in it is
            // not something to be clever about. The previous file must survive that refusal untouched.
            runCatching {
                ManagedRootsFile.writeAtomically(
                    target,
                    ManagedRootsFile.serialize(listOf(root("beta", "with\nnewline"))),
                )
            }.also { assertTrue(it.isFailure, "a control character in a path must be refused, not escaped") }

            assertContentEquals(before, Files.readAllBytes(target), "a failed promote must leave the previous file intact")
            val litter = Files.list(data).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
            }
            assertTrue(litter.isEmpty(), "the temp sibling must be removed on failure: $litter")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * A DATA_DIR whose filesystem has no atomic rename (a network mount) DEGRADES, and does not throw. Driven
     * through the [FileAtomics] seam rather than a real exotic mount, exactly as `LocalContentStoreExoticFsTest`
     * drives the same fallback: the branch then runs identically on APFS, ext4 and CI instead of being skipped
     * everywhere it cannot be provoked.
     */
    @Test
    fun `a filesystem with no ATOMIC_MOVE degrades to copy-replace rather than throwing`() {
        val base = Files.createTempDirectory("pb-managed-native-nonatomic")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            val noAtomicMove = object : FileAtomics by FileAtomics.Real {
                override fun atomicMove(source: Path, target: Path) =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")
            }
            val text = ManagedRootsFile.serialize(listOf(root("alpha", base.resolve("a").toString())))

            ManagedRootsFile.writeAtomically(target, text, noAtomicMove)

            assertEquals(text, Files.readString(target), "the copy fallback must land the whole file")
            val litter = Files.list(data).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
            }
            assertTrue(litter.isEmpty(), "the temp sibling must be removed after the fallback too: $litter")
            // And the REAL loader still reads it back - a degraded promote is still a promote.
            val loaded = PlainbaseConfig.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString()),
            ).roots
            assertEquals(setOf(RootName.require("alpha")), loaded.managed)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * **A copy that fails MIDWAY may not take `roots.conf` with it.** The fallback replaces the target in place,
     * so the hazard is the same one `LocalContentStoreExoticFsTest` models for a page - a partially-written
     * target - except that here the truncated file is the one the next boot has to parse, and the command has
     * already exited 1 by the time anyone finds out. The seam writes a partial prefix and then throws, exactly as
     * a real mid-copy I/O failure would leave things, and the previous config must come back byte-identical.
     */
    @Test
    fun `a copy-fallback that fails mid-write restores the previous roots dot conf byte-identically`() {
        val base = Files.createTempDirectory("pb-managed-native-midcopy")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())

            val good = ManagedRootsFile.serialize(listOf(root("alpha", base.resolve("a").toString())))
            ManagedRootsFile.writeAtomically(target, good)
            val before = Files.readAllBytes(target)

            val truncatingCopy = object : FileAtomics by FileAtomics.Real {
                override fun atomicMove(source: Path, target: Path) =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")

                override fun copyReplace(source: Path, target: Path) {
                    Files.writeString(target, "roots {\n  \"beta\" {\n    back") // the copy truncated the target...
                    throw IOException("the copy failed midway") // ...and then died
                }
            }
            val next = ManagedRootsFile.serialize(listOf(root("beta", base.resolve("b").toString())))
            assertFailsWith<IOException> { ManagedRootsFile.writeAtomically(target, next, truncatingCopy) }

            assertContentEquals(before, Files.readAllBytes(target), "the last-known-good config must survive a failed promote")
            // And it is still a CONFIG, not just the right bytes: the loader the next boot runs still reads it.
            assertEquals(setOf(RootName.require("alpha")), PlainbaseConfig.fromEnvAndFile(env).roots.managed)
            val litter = Files.list(data).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") || it.endsWith(ManagedRootsFile.BACKUP_SUFFIX) }.toList()
            }
            assertTrue(litter.isEmpty(), "a RESTORED promote keeps no backup: the live file IS the last-known-good again. Found: $litter")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * The other half: a FIRST-EVER promote that fails midway has no last-known-good to restore, and absence is
     * what preceded it - so the partial file is taken away rather than left for the loader to choke on.
     */
    @Test
    fun `a copy-fallback that fails on the FIRST promote leaves no partial roots dot conf behind`() {
        val base = Files.createTempDirectory("pb-managed-native-midcopy-first")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            val truncatingCopy = object : FileAtomics by FileAtomics.Real {
                override fun atomicMove(source: Path, target: Path) =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")

                override fun copyReplace(source: Path, target: Path) {
                    Files.writeString(target, "roots {\n  \"alph")
                    throw IOException("the copy failed midway")
                }
            }
            val text = ManagedRootsFile.serialize(listOf(root("alpha", base.resolve("a").toString())))

            assertFailsWith<IOException> { ManagedRootsFile.writeAtomically(target, text, truncatingCopy) }

            assertTrue(!Files.exists(target), "a partial first write is not a config - the install stays SYNTHESIZED")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delete unlinks the file, which is what remove of the LAST managed root promotes`() {
        val base = Files.createTempDirectory("pb-managed-native-delete")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            ManagedRootsFile.writeAtomically(target, ManagedRootsFile.serialize(listOf(root("alpha", base.resolve("a").toString()))))
            assertTrue(Files.exists(target))

            ManagedRootsFile.delete(target)
            assertTrue(!Files.exists(target))
            // Idempotent: unlinking an absent file is not an error.
            ManagedRootsFile.delete(target)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
