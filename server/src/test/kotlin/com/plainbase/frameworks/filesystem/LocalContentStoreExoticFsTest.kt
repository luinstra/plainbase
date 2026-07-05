package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.grantForTests
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Exotic-FS fail-closed matrix (C1b item 3): every create/write branch that fires when
 * [Files.createLink] or `ATOMIC_MOVE` is unavailable, exercised DETERMINISTICALLY through the
 * [FileAtomics] seam — no real-filesystem probing, no FS-conditional gating, so the fallback
 * branches run identically on ext4, APFS, and CI. The sibling [LocalContentStoreTest]'s
 * probe-gated no-empty-window test stays as-is (it exercises the REAL filesystem); this file
 * exercises the BRANCHES the real FS never takes.
 *
 * JVM-only Kotest (the [LocalContentStoreTest] policy at its :22-27): the tests drive CONTROL
 * FLOW via injected failures, not NIO behavior, so they buy nothing in the closed-world image.
 */
class LocalContentStoreExoticFsTest : FunSpec({

    val hasher: (ByteArray) -> String = { it.size.toString() } // content-only tests; hash value unused

    // seam control: a pass-through FileAtomics behaves identically to the default on a create + read.
    test("a pass-through FileAtomics behaves identically to the production default") {
        withTempDir { root ->
            val passthrough = object : FileAtomics by FileAtomics.Real {}
            val store = LocalContentStore(root, atomics = passthrough)
            val content = "# Hi\n\npass-through.\n".toByteArray()

            store.createExclusive(TreePath.require("page.md"), content, hasher).shouldBeInstanceOf<CreateResult.Created>()
            store.scan()
            store.read(TreePath.require("page.md")) shouldBe content
        }
    }

    // (a) Asset write fails CLOSED without hardlinks: no reserve-then-move, nothing created, no residue.
    test("asset write fails closed when hardlinks are unavailable") {
        withTempDir { root ->
            Files.createDirectory(root.resolve("page"))
            val noLinks = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) = throw UnsupportedOperationException("no hardlinks")
            }
            val store = LocalContentStore(root, atomics = noLinks)
            store.scan()

            val result = store.writeAssetExclusive(grantForTests(), TreePath.require("page/img.png"), "binary".toByteArray(), hasher)

            result.shouldBeInstanceOf<CreateResult.Unreadable>().cause shouldContain "hardlink unavailable"
            Files.exists(root.resolve("page/img.png"), LinkOption.NOFOLLOW_LINKS) shouldBe false
            residueCount(root.resolve("page")) shouldBe 0
        }
    }

    // (b) Page create falls back to reserve-then-move and lands whole, with no temp residue.
    test("page create falls back to reserve-then-move and lands the full bytes") {
        withTempDir { root ->
            val noLinks = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) = throw UnsupportedOperationException("no hardlinks")
            }
            val store = LocalContentStore(root, atomics = noLinks)
            val content = "# Fallback\n\nreserve-then-move body.\n".toByteArray()

            store.createExclusive(TreePath.require("fallback.md"), content, hasher).shouldBeInstanceOf<CreateResult.Created>()

            Files.readAllBytes(root.resolve("fallback.md")) shouldBe content
            residueCount(root) shouldBe 0
        }
    }

    // (c) The reservation window is real and mid-flight-observable on the fallback — pins the DOCUMENTED
    // 0-byte window that justifies (a)'s fail-closed asset law. In-band observation, single-threaded.
    test("the fallback's 0-byte reservation is observable when the content move begins") {
        withTempDir { root ->
            var reservationWasEmpty = false
            val observingLinkless = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) = throw UnsupportedOperationException("no hardlinks")
                override fun atomicMove(source: Path, target: Path) {
                    // The reserve-then-move fallback created `target` as a 0-byte reservation before this move.
                    reservationWasEmpty = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == 0L
                    FileAtomics.Real.atomicMove(source, target)
                }
            }
            val store = LocalContentStore(root, atomics = observingLinkless)
            val content = "# Window\n\nobserved.\n".toByteArray()

            store.createExclusive(TreePath.require("window.md"), content, hasher).shouldBeInstanceOf<CreateResult.Created>()

            reservationWasEmpty shouldBe true // the createFile reservation was 0-byte when the move began
            Files.readAllBytes(root.resolve("window.md")) shouldBe content // final bytes complete
        }
    }

    // (d) A move failure on the fallback drops the reservation (the :600 self-heal) — no wedge, no ghost.
    test("a move failure on the fallback deletes the reservation and does not wedge the path") {
        withTempDir { root ->
            val brokenMove = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) = throw UnsupportedOperationException("no hardlinks")
                override fun atomicMove(source: Path, target: Path) =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")
                override fun copyReplace(source: Path, target: Path) = throw IOException("disk full")
            }
            val store = LocalContentStore(root, atomics = brokenMove)
            val path = TreePath.require("doomed.md")

            store.createExclusive(path, "# Doomed\n".toByteArray(), hasher).shouldBeInstanceOf<CreateResult.Unreadable>()
            Files.exists(root.resolve("doomed.md"), LinkOption.NOFOLLOW_LINKS) shouldBe false // no 0-byte ghost survives

            // The path is NOT permanently wedged: a retry with a healthy store creates it cleanly.
            val healthy = "# Recovered\n".toByteArray()
            LocalContentStore(root).createExclusive(path, healthy, hasher).shouldBeInstanceOf<CreateResult.Created>()
            Files.readAllBytes(root.resolve("doomed.md")) shouldBe healthy
        }
    }

    // (e) The copy+delete fallback lands whole for both write() and compareAndSwapWrite().
    test("the copy+delete fallback lands the full bytes for write and CAS") {
        withTempDir { root ->
            val copyFallback = object : FileAtomics by FileAtomics.Real {
                override fun atomicMove(source: Path, target: Path) =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")
            }
            val store = LocalContentStore(root, atomics = copyFallback)
            val path = TreePath.require("cas.md")
            val initial = "original bytes\n".toByteArray()

            store.write(path, initial) // exercises write()'s copy+delete fallback
            Files.readAllBytes(root.resolve("cas.md")) shouldBe initial
            store.scan() // index the file so it is a CAS target

            val next = "swapped bytes\n".toByteArray()
            store.compareAndSwapWrite(path, hasher(initial), next, hasher).shouldBeInstanceOf<CasResult.Written>()
            Files.readAllBytes(root.resolve("cas.md")) shouldBe next
        }
    }

    // (f) A CAS copy-fallback failure reports Unreadable(targetMutated = true) — item 2's store half. The
    // seam writes PARTIAL bytes then throws (the real "non-atomic copy truncated the target" hazard), so
    // the on-disk state proves the "target may have been mutated" premise: the drift the pipeline keeps
    // the write-ahead mark for, and that reconcile's hash-mismatch drift-skip then catches.
    test("a CAS copy-fallback failure reports Unreadable with targetMutated = true and leaves the target mutated on disk") {
        withTempDir { root ->
            // Seed the target directly (not through the failing seam), then scan so it is a CAS target.
            val original = "original bytes\n".toByteArray()
            Files.write(root.resolve("mutated.md"), original)
            val intended = "the intended new bytes\n".toByteArray()
            val partial = "the intended new".toByteArray() // a truncated prefix of [intended]
            val truncatingCopy = object : FileAtomics by FileAtomics.Real {
                override fun atomicMove(source: Path, target: Path) =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")
                override fun copyReplace(source: Path, target: Path) {
                    Files.write(target, partial) // the non-atomic copy partially replaced the target...
                    throw IOException("copy truncated the target") // ...then failed mid-copy
                }
            }
            val store = LocalContentStore(root, atomics = truncatingCopy)
            store.scan()
            val path = TreePath.require("mutated.md")

            val result = store.compareAndSwapWrite(path, hasher(original), intended, hasher)

            result.shouldBeInstanceOf<CasResult.Unreadable>().targetMutated shouldBe true
            // The premise is real: the on-disk target is the truncated partial — NOT the intended bytes
            // (the write never completed) and NOT the pristine original (the copy did mutate it).
            val onDisk = Files.readAllBytes(root.resolve("mutated.md"))
            onDisk shouldBe partial
            onDisk.contentEquals(intended) shouldBe false
        }
    }
})

/** The count of leftover temp residue (`.pbtmp*` / `.<name>.*.tmp`) directly under [dir]. */
private fun residueCount(dir: Path): Int =
    Files.newDirectoryStream(dir).use { stream ->
        stream.count { it.fileName.toString().let { name -> name.startsWith(".pbtmp") || name.endsWith(".tmp") } }
    }

/** Runs [block] with a fresh temp dir; always cleans up (the [LocalContentStoreTest] temp-dir idiom). */
private fun withTempDir(block: (Path) -> Unit) {
    val root = Files.createTempDirectory("pb-exotic-fs")
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}
