package com.plainbase.frameworks.config

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.filesystem.FileAtomics
import com.typesafe.config.ConfigException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `roots.conf` writer, over the two divergence surfaces project policy native-tags by default: **charset
 * decoding** (a path with a non-ASCII character, a quote and a backslash) and **NIO** (the atomic promote).
 *
 * **The round trip goes through `ConfigLoader.fromEnvAndFile`, NOT through a reader in `ManagedRootsFile` -
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

            val loaded = ConfigLoader.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString()),
            ).roots

            assertEquals(listOf(written), loaded.extras, "the writer and the SERVER'S loader must agree, byte for byte")
            assertEquals(setOf(RootName.require("notes")), loaded.managed)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * The ONE residual assumption the in-memory candidate mechanism rests on: `parseString` of managed text T versus
     * `parseFile` of a file CONTAINING T, with the operator roots merged on both paths. They agree - same parser, UTF-8
     * on both sides, and the writer pins `Charsets.UTF_8` - but the whole gate is built on that, so it is PINNED.
     */
    @Test
    fun `the in-memory candidate and the on-disk file parse to the SAME roots - the gate's load-bearing assumption`() {
        val base = Files.createTempDirectory("pb-managed-native-eq")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())
            Files.writeString(
                data.resolve("plainbase.conf"),
                "roots { docs { path = \"" + base.resolve("docs") + "\" } }",
            )
            val awkward = base.resolve("""tree-ünïcode-"q"-back\slash""")
            val text = ManagedRootsFile.serialize(listOf(root("notes", awkward.toString())))

            // The CLI validates THIS - the string, in memory, before anything exists on disk...
            val candidate = ConfigLoader.fromEnvAndCandidateRoots(text, env)

            // ...and then writes exactly those bytes. The next boot parses the FILE.
            ManagedRootsFile.writeAtomically(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), text)
            val onDisk = ConfigLoader.fromEnvAndFile(env)

            assertEquals(candidate, onDisk, "the operator+managed artifact validated must BE the artifact served")
            assertEquals(listOf("docs", "notes"), candidate.roots.list.map { it.name.value })
            assertEquals(RootsOrigin.EXPLICIT, candidate.roots.origin)
            assertEquals(setOf(RootName.require("notes")), candidate.roots.managed)
            assertEquals(
                ConfigBootInspector.bootRefusals(candidate),
                ConfigBootInspector.bootRefusals(onDisk),
                "candidate and file refusal observations must agree",
            )
            assertEquals(
                ConfigBootInspector.rootsWarnings(candidate),
                ConfigBootInspector.rootsWarnings(onDisk),
                "candidate and file warning observations must agree in order",
            )
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `semantically invalid candidate bytes and the same managed file refuse identically`() {
        val cases = listOf(
            "extra history auto" to { base: Path -> "roots { notes { path = \"$base/notes\", history = auto } }" },
            "machine primary" to { base: Path -> "roots { docs { path = \"$base/docs\" } }" },
            "unknown root glob" to { base: Path -> "roots { notes { path = \"$base/notes\" } }" },
        )
        val expectedMessages = mapOf(
            "extra history auto" to
                "roots.notes.history = auto is not allowed on an extra root: auto detects a repository and may create " +
                "one, which Plainbase will not do in a tree it does not own. Use `native` to claim an existing " +
                "repository at that path (Plainbase then refuses to start if it is a linked worktree, a submodule, " +
                "or somebody else's checkout), or `off` for no history.",
            "machine primary" to
                "roots.conf must not declare 'docs': primary's directory comes from CONTENT_DIR, or from a roots {} " +
                "block you wrote yourself in plainbase.conf. `plainbase root` never manages docs.",
            "unknown root glob" to
                "auth.agentDirectCommit.roots.ghost names no configured root (declared roots: docs, notes). " +
                "A direct-commit glob for a root that does not exist authorizes nothing - fix the name, or remove the entry.",
        )
        cases.forEach { (label, rootsText) ->
            val base = Files.createTempDirectory("pb-managed-native-semantic")
            try {
                val data = Files.createDirectory(base.resolve("data"))
                val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())
                if (label == "unknown root glob") {
                    Files.writeString(
                        data.resolve("plainbase.conf"),
                        "auth { agentDirectCommit { roots { ghost = [\"*.md\"] } } }",
                    )
                }
                val text = rootsText(base)
                val candidateFailure = assertFailsWith<IllegalArgumentException> {
                    ConfigLoader.fromEnvAndCandidateRoots(text, env)
                }
                ManagedRootsFile.writeAtomically(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), text)
                val onDiskFailure = assertFailsWith<IllegalArgumentException> {
                    ConfigLoader.fromEnvAndFile(env)
                }
                assertEquals(candidateFailure::class, onDiskFailure::class, label)
                assertEquals(expectedMessages.getValue(label), candidateFailure.message, label)
                assertEquals(candidateFailure.message, onDiskFailure.message, label)
            } finally {
                base.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `malformed candidate syntax stays a parse error while malformed managed syntax is wrapped`() {
        val base = Files.createTempDirectory("pb-managed-native-parse-errors")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())
            val candidateFailure = assertFailsWith<ConfigException.Parse> {
                ConfigLoader.fromEnvAndCandidateRoots("roots {", env)
            }
            assertEquals("String: 1: expecting a close parentheses ')' here, not: end of file", candidateFailure.message)

            val managed = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            Files.writeString(managed, "roots {")
            val managedFailure = assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
            assertEquals(ConfigException.Parse::class, managedFailure.cause!!::class)
            assertEquals(
                "$managed is the machine-managed roots file and it does not parse: $managed: 1: " +
                    "expecting a close parentheses ')' here, not: end of file. Refusing to start rather than serve a " +
                    "topology that may have lost roots: booting without them would 404 every page they hold, which reads " +
                    "as deleted rather than as an outage. Remedies: restore it from a backup; or, to accept a " +
                    "CONTENT_DIR-only topology and re-add roots with `plainbase root add`, delete $managed.",
                managedFailure.message,
            )
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
            val loaded = ConfigLoader.fromEnvAndFile(
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
            assertEquals(setOf(RootName.require("alpha")), ConfigLoader.fromEnvAndFile(env).roots.managed)
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

    /**
     * **The states an INTERRUPTED promote can really leave on disk, and the boot each one must now get.**
     *
     * The hazard this closes is the quietest one in the feature: an absent-or-empty `roots.conf` used to parse to
     * "no roots block", which the loader synthesized into a LEGACY, main-only config - so a promote killed at the
     * wrong instant booted GREEN with every extra root gone. Nothing failed. The operator's next `root list` showed
     * one root, and every page under the others 404'd - and a 404 does not read as an outage, it reads as deleted.
     *
     * Each case is a real residue of the copy-replace fallback (the only non-atomic promote path) or of a kill
     * during it, and each asserts the OUTCOME - what `ConfigLoader.fromEnvAndFile`, the parser the next boot
     * actually runs, does with the bytes on disk. `ABSENT_CLEAN` is the one legitimate absence: an install that
     * never ran `plainbase root add`, with nothing beside it to say otherwise.
     */
    private enum class Residue(val roots: String?, val backup: Boolean, val boots: Boolean) {
        ABSENT_CLEAN(roots = null, backup = false, boots = true),
        ABSENT_WITH_BACKUP(roots = null, backup = true, boots = false),
        ZERO_LENGTH(roots = "", backup = false, boots = false),
        ZERO_LENGTH_WITH_BACKUP(roots = "", backup = true, boots = false),
        HEADER_ONLY(roots = "# Managed by `plainbase root` - do not edit by hand.\n", backup = true, boots = false),
        TRUNCATED_MID_BLOCK(roots = "roots {\n  \"alpha\" {\n    back", backup = true, boots = false),
        WHOLE_WITH_STALE_BACKUP(roots = null, backup = true, boots = true), // roots is written for real below
    }

    @Test
    fun `an interrupted promote refuses the boot rather than silently reverting the install to single-root`() {
        Residue.entries.forEach { residue ->
            val base = Files.createTempDirectory("pb-managed-native-residue")
            try {
                val data = Files.createDirectory(base.resolve("data"))
                val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
                val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())
                val whole = ManagedRootsFile.serialize(listOf(root("alpha", base.resolve("a").toString())))

                if (residue == Residue.WHOLE_WITH_STALE_BACKUP) {
                    ManagedRootsFile.writeAtomically(target, whole)
                } else {
                    residue.roots?.let { Files.writeString(target, it) }
                }
                if (residue.backup) {
                    Files.writeString(data.resolve("${PlainbaseConfig.MANAGED_ROOTS_FILE}${ManagedRootsFile.BACKUP_SUFFIX}"), whole)
                }

                if (residue.boots) {
                    val config = ConfigLoader.fromEnvAndFile(env)
                    val expected = if (residue == Residue.WHOLE_WITH_STALE_BACKUP) setOf(RootName.require("alpha")) else emptySet()
                    assertEquals(expected, config.roots.managed, "$residue must boot with exactly the topology on disk")
                    if (residue.backup) {
                        assertTrue(
                            ConfigBootInspector.rootsWarnings(config).any { it.contains(ManagedRootsFile.BACKUP_SUFFIX) },
                            "$residue boots, but the leftover backup is the only evidence a promote died - it must be named",
                        )
                    }
                } else {
                    // The REFUSAL, and it must be actionable: an operator staring at this needs to be told what is
                    // wrong and both ways out, not handed a HOCON parse error against a file they never wrote.
                    val failure = assertFailsWith<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
                    val message = requireNotNull(failure.message)
                    assertTrue(message.contains(PlainbaseConfig.MANAGED_ROOTS_FILE), "$residue: the refusal must name the file: $message")
                    assertTrue(message.contains("delete"), "$residue: the refusal must offer the delete-and-re-add way out: $message")
                    if (residue.backup) {
                        assertTrue(message.contains("mv"), "$residue: with a .bak present the refusal must offer the restore: $message")
                    }
                }
            } finally {
                base.toFile().deleteRecursively()
            }
        }
    }

    /**
     * The other half of the fail-closed rule, and the reason it is a rule about DAMAGE rather than about EMPTINESS:
     * an explicitly empty `roots {}` block is a file `plainbase root` could have written, it says "no extra roots",
     * and it keeps its documented meaning (for the machine file, emptiness IS absence). Refusing it too would be a
     * gate that fires on the one state it was never meant to catch.
     */
    @Test
    fun `an explicitly EMPTY roots block is not damage - it returns the install to SYNTHESIZED`() {
        val base = Files.createTempDirectory("pb-managed-native-empty-block")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            Files.writeString(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), "roots {\n}\n")

            val config = ConfigLoader.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString()),
            )

            assertEquals(emptySet(), config.roots.managed)
            assertEquals(RootsOrigin.SYNTHESIZED, config.roots.origin, "an empty machine file is absence, not an EXPLICIT topology")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * The promote VALIDATES ITS OWN OUTCOME: a filesystem that reports a successful write and then holds different
     * bytes is caught HERE, by re-reading what actually landed, rather than at the next boot's parse. The previous
     * config must survive it - a promote that cannot be verified is a promote that did not happen.
     */
    @Test
    fun `a copy fallback that lands the WRONG BYTES while reporting success is caught, and the previous file restored`() {
        val base = Files.createTempDirectory("pb-managed-native-liar")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())

            val good = ManagedRootsFile.serialize(listOf(root("alpha", base.resolve("a").toString())))
            ManagedRootsFile.writeAtomically(target, good)
            val before = Files.readAllBytes(target)

            // The whole file parses, names a root, and is NOT what we promoted. Every precondition looks fine; only
            // the outcome is wrong, which is exactly the class of failure a pre-write check cannot see.
            val lyingCopy = object : FileAtomics by FileAtomics.Real {
                override fun atomicMove(source: Path, target: Path) =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")

                override fun copyReplace(source: Path, target: Path) {
                    Files.writeString(target, ManagedRootsFile.serialize(listOf(root("wrong", base.resolve("w").toString()))))
                }
            }
            val next = ManagedRootsFile.serialize(listOf(root("beta", base.resolve("b").toString())))

            assertFailsWith<IOException> { ManagedRootsFile.writeAtomically(target, next, lyingCopy) }

            assertContentEquals(before, Files.readAllBytes(target), "an unverifiable promote must leave the previous config in place")
            assertEquals(setOf(RootName.require("alpha")), ConfigLoader.fromEnvAndFile(env).roots.managed)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delete refuses a regular backup entry while loader keeps its regular-file warning predicate`() {
        assertBackupRefusal(
            name = "regular",
            warningExpected = true,
            create = { target, backup ->
                Files.copy(target, backup)
                assertTrue(
                    Files.readAttributes(backup, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile,
                )
            },
            verify = { _, backup -> assertTrue(Files.isRegularFile(backup)) },
        )
    }

    @Test
    fun `delete refuses a directory backup entry while loader keeps its regular-file warning predicate`() {
        assertBackupRefusal(
            name = "directory",
            warningExpected = false,
            create = { _, backup ->
                val child = Files.createDirectory(backup).resolve("recovery.txt")
                Files.writeString(child, "keep")
                assertTrue(
                    Files.readAttributes(backup, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory,
                )
            },
            verify = { _, backup -> assertEquals("keep", Files.readString(backup.resolve("recovery.txt"))) },
        )
    }

    @Test
    fun `delete refuses a symlink-to-regular backup while loader keeps its regular-file warning predicate`() {
        assertBackupRefusal(
            name = "symlink-regular",
            warningExpected = true,
            create = { _, backup ->
                val targetSpelling = Path.of("recovery-target.conf")
                val target = backup.parent.resolve(targetSpelling)
                val targetBytes = "recovery bytes".toByteArray()
                Files.write(target, targetBytes)
                createSymbolicLinkOrSkip(backup, targetSpelling)
                assertTrue(
                    Files.readAttributes(backup, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isSymbolicLink,
                )
                assertEquals(targetSpelling, Files.readSymbolicLink(backup))
                assertContentEquals(targetBytes, Files.readAllBytes(target))
            },
            verify = { _, backup ->
                assertEquals(Path.of("recovery-target.conf"), Files.readSymbolicLink(backup))
                assertContentEquals("recovery bytes".toByteArray(), Files.readAllBytes(backup.parent.resolve("recovery-target.conf")))
            },
        )
    }

    @Test
    fun `delete refuses a dangling symlink backup while loader keeps its regular-file warning predicate`() {
        assertBackupRefusal(
            name = "symlink-dangling",
            warningExpected = false,
            create = { _, backup ->
                val targetSpelling = Path.of("missing-recovery.conf")
                createSymbolicLinkOrSkip(backup, targetSpelling)
                assertTrue(
                    Files.readAttributes(backup, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isSymbolicLink,
                )
                assertEquals(targetSpelling, Files.readSymbolicLink(backup))
                assertTrue(!Files.exists(backup))
            },
            verify = { _, backup ->
                assertEquals(Path.of("missing-recovery.conf"), Files.readSymbolicLink(backup))
                assertTrue(!Files.exists(backup))
            },
        )
    }

    @Test
    fun `delete refuses an absent live file when a backup entry exists`() {
        val base = Files.createTempDirectory("pb-managed-native-delete-absent")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            val backup = ManagedRootsFile.backupPath(target)
            Files.writeString(backup, "recovery")
            assertTrue(
                Files.readAttributes(backup, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile,
            )

            val failure = assertFailsWith<ManagedRootsBackupPresentException> { ManagedRootsFile.delete(target) }
            assertEquals(
                "cannot delete $target while backup entry $backup exists; resolve the backup deliberately, then retry",
                failure.message,
            )
            assertTrue(!Files.exists(target))
            assertEquals("recovery", Files.readString(backup))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loader treats a regular backup as damage beside missing and nonregular managed entries`() {
        listOf("missing", "directory", "dangling symlink").forEach { kind ->
            val base = Files.createTempDirectory("pb-managed-native-loader-backup-" + kind.replace(' ', '-'))
            try {
                val data = Files.createDirectory(base.resolve("data"))
                val content = Files.createDirectory(base.resolve("content"))
                val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
                val backup = ManagedRootsFile.backupPath(target)
                val backupBytes = "regular backup for $kind".toByteArray()
                Files.write(backup, backupBytes)

                when (kind) {
                    "missing" -> assertTrue(!Files.exists(target))
                    "directory" -> {
                        val child = Files.createDirectory(target).resolve("still-here")
                        Files.writeString(child, "target bytes")
                        assertTrue(
                            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory,
                        )
                    }
                    "dangling symlink" -> {
                        createSymbolicLinkOrSkip(target, Path.of("missing-live.conf"))
                        assertTrue(
                            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isSymbolicLink,
                        )
                        assertEquals(Path.of("missing-live.conf"), Files.readSymbolicLink(target))
                        assertTrue(!Files.exists(target))
                    }
                }

                val failure = assertFailsWith<IllegalArgumentException> {
                    ConfigLoader.fromEnvAndFile(
                        mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString()),
                    )
                }
                val deletionTarget = if (Files.exists(target)) target else backup
                assertEquals(
                    "$target is the machine-managed roots file and it is MISSING. Refusing to start rather than serve a " +
                        "topology that may have lost roots: booting without them would 404 every page they hold, which reads " +
                        "as deleted rather than as an outage. Remedies: restore the last-known-good with `mv $backup $target`; " +
                        "or, to accept a CONTENT_DIR-only topology and re-add roots with `plainbase root add`, delete $deletionTarget.",
                    failure.message,
                )
                assertContentEquals(backupBytes, Files.readAllBytes(backup))
                when (kind) {
                    "missing" -> assertTrue(!Files.exists(target))
                    "directory" -> assertEquals("target bytes", Files.readString(target.resolve("still-here")))
                    "dangling symlink" -> {
                        assertEquals(Path.of("missing-live.conf"), Files.readSymbolicLink(target))
                        assertTrue(!Files.exists(target))
                    }
                }
            } finally {
                base.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `delete preserves an unrelated nonempty-directory unlink failure`() {
        val base = Files.createTempDirectory("pb-managed-native-delete-failure")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val target = Files.createDirectory(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE))
            Files.writeString(target.resolve("still-here"), "content")
            assertTrue(!Files.exists(ManagedRootsFile.backupPath(target)))

            assertFailsWith<DirectoryNotEmptyException> { ManagedRootsFile.delete(target) }
            assertEquals("content", Files.readString(target.resolve("still-here")))
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

    private fun assertBackupRefusal(
        name: String,
        warningExpected: Boolean,
        create: (Path, Path) -> Unit,
        verify: (Path, Path) -> Unit,
    ) {
        val base = Files.createTempDirectory("pb-managed-native-delete-$name")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            val rootPath = Files.createDirectory(base.resolve("root"))
            val target = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            val backup = ManagedRootsFile.backupPath(target)
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
            Files.writeString(target, ManagedRootsFile.serialize(listOf(root("notes", rootPath.toString()))))
            val liveBefore = Files.readAllBytes(target)
            create(target, backup)

            val loaded = ConfigLoader.fromEnvAndFile(env)
            assertEquals(warningExpected, ConfigBootInspector.rootsWarnings(loaded).any { it.startsWith("$backup is left over") })

            val failure = assertFailsWith<ManagedRootsBackupPresentException> { ManagedRootsFile.delete(target) }
            assertEquals(
                "cannot delete $target while backup entry $backup exists; resolve the backup deliberately, then retry",
                failure.message,
            )
            assertContentEquals(liveBefore, Files.readAllBytes(target))
            verify(target, backup)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}

private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
    try {
        Files.createSymbolicLink(link, target)
    } catch (failure: UnsupportedOperationException) {
        assumeTrue(false, "the native filesystem does not support symbolic links: ${failure.message}")
    }
}
