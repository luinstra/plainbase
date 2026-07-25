package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.RootUnavailable
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The STORE's OWN root-loss classification, against a REAL [LocalContentStore] over a REAL directory.
 *
 * **Why a real store, and not a double.** A `ContentStore` double implements its own `readClassified` and its own
 * failure arms — so a test that drives the double asserts the double's answer and passes whether or not the
 * production classification was ever written. It is a test that cannot fail when the code is wrong, which is worse
 * than no test at all, because the acceptance criteria would cite it as the proof. The subject here IS the store's
 * classification, so the store is real and only the ONE genuinely unobservable instant is seamed (see [probeOnce]).
 *
 * `@Tag("native")` + kotlin.test: this is squarely the NIO divergence surface — POSIX permission semantics,
 * `isRegularFile` through a non-readable parent, `deleteIfExists`'s errno mapping — which is exactly the class of
 * behavior that can differ between the JVM and the native image.
 *
 * Every `chmod` row is VACUOUS as root (uid 0 bypasses permission bits), so they skip there rather than pass falsely.
 */
@Tag("native")
class LocalContentStoreRootLossNativeTest {

    private val page = TreePath.require("page.md")
    private val hasher: (ByteArray) -> String = { "sha256:" + it.size.toString().padStart(64, '0') }

    /** A scanned root with one page in it. */
    private fun <T> withRoot(block: (Path, LocalContentStore, MutableList<RootName>) -> T): T {
        val root = Files.createTempDirectory("pb-rootloss")
        val marks = mutableListOf<RootName>()
        return try {
            Files.writeString(root.resolve("page.md"), "# Page\n")
            val store = LocalContentStore(
                root = root,
                rootName = RootName.require("extra"),
                onRootUnavailable = { marks += RootName.require("extra") },
            )
            store.scan()
            block(root, store, marks)
        } finally {
            restore(root)
            root.toFile().deleteRecursively()
            root.resolveSibling("${root.fileName}-volume").toFile().deleteRecursively() // the unmount rows' moved-aside tree
        }
    }

    /**
     * The ONE seam, and the minimum honest one: no filesystem state change can happen BETWEEN two calls inside a
     * single operation, so "the root was still there when we ENTERED" is the one instant a test cannot otherwise
     * reproduce. This scripts THAT and nothing else — the failing FS call, the failure arm, the exit classifier, the
     * mark and the throw are all production code.
     *
     * The FALL-THROUGH is production code too: it delegates to a plain store's own `available()`, so the real
     * three-predicate check answers every call after the first. A hand-rolled `isDirectory && isReadable &&
     * isExecutable` here would be a parallel implementation of the very predicate under test.
     */
    private fun probeOnce(root: Path): (Path) -> Boolean {
        val real = LocalContentStore(root)
        var first = true
        return {
            if (first) {
                first = false
                true
            } else {
                real.available()
            }
        }
    }

    private fun store(root: Path, marks: MutableList<RootName>, probe: ((Path) -> Boolean)? = null): LocalContentStore {
        val name = RootName.require("extra")
        val store = if (probe == null) {
            LocalContentStore(root = root, rootName = name, onRootUnavailable = { marks += name })
        } else {
            LocalContentStore(root = root, rootName = name, onRootUnavailable = { marks += name }, probeRoot = probe)
        }
        return store.also { it.scan() }
    }

    private fun chmod(path: Path, vararg perms: PosixFilePermission) =
        Files.setPosixFilePermissions(path, perms.toSet())

    private fun restore(root: Path) =
        runCatching { Files.walk(root).forEach { runCatching { chmod(it, *ALL_PERMS) } } }

    private fun skipAsRoot(): Boolean = System.getProperty("user.name") == "root"

    // ---- the READ path: a THROW is as ambiguous as a null, and both must classify -----------------

    @Test
    fun `a read that THROWS on a GONE root is RootDown, and the store MARKS on the way out`() {
        if (skipAsRoot()) return
        withRoot { root, store, marks ->
            // The deterministic reproduction of "the root went away between read()'s isRegularFile probe and its
            // readAllBytes" - no seam needed, because POSIX gives it to us exactly: a child stat needs only +x on the
            // parent, so isRegularFile still answers TRUE while readAllBytes raises AccessDeniedException and the
            // three-predicate probe fails on its isReadable predicate.
            chmod(root.resolve("page.md"))
            chmod(root, PosixFilePermission.OWNER_EXECUTE)

            assertEquals(StoreRead.RootDown, store.readClassified(page))
            assertTrue(marks.isNotEmpty(), "detection without publication would 503 the write while reads served the carried section")
        }
    }

    @Test
    fun `a read that THROWS on a LIVE root RETHROWS the genuine fault - never a false root_unavailable`() {
        if (skipAsRoot()) return
        withRoot { root, store, marks ->
            // The SAME fault, with the root left rwx. This is the (B) side, and it is the row a "classify everything
            // as RootDown" shortcut breaks: a permission fault on a healthy disk is a 500 an operator must SEE, not a
            // 503 that tells them to go remount something that was never unmounted.
            chmod(root.resolve("page.md"))

            assertFailsWith<java.io.IOException> { store.readClassified(page) }
            assertTrue(marks.isEmpty(), "a live root must NEVER be marked - the mark is sticky until restart")
        }
    }

    @Test
    fun `a page DELETED under a LIVE root is NoBytes - and that is the WHOLE of what a store may say (C1)`() {
        withRoot { root, store, marks ->
            Files.delete(root.resolve("page.md"))

            // It used to be `Absent`, and `Absent` was a verdict: it meant "deleted", and the adapter's callers spent
            // it as a 404. A store cannot know that. It knows there are no bytes here, on a root that is live - which
            // is equally what an empty mount point, a half-finished restore and a decoy tree look like. Whether this
            // page is GONE is a question about the durable index, and it is answered by `AbsenceClassifier`, one layer
            // up. Everything this row asserted about the STORE still holds; the word for it is now honest.
            assertEquals(StoreRead.NoBytes, store.readClassified(page))
            assertTrue(marks.isEmpty())
        }
    }

    // ---- the WRITE path: the five ambiguous outcomes, and the cleanup that can replace them --------

    @Test
    fun `CAS Deleted on a gone root is RootUnavailable - NEVER 409 page_deleted`() {
        withRoot { root, _, marks ->
            val s = store(root, marks, probeOnce(root)) // the entry probe passes once; the root is really gone
            root.toFile().deleteRecursively()

            assertFailsWith<RootUnavailable> { s.compareAndSwapWrite(page, "sha256:x", "new".toByteArray(), hasher) }
            assertTrue(marks.isNotEmpty())
        }
    }

    @Test
    fun `CAS Unreadable on a gone root is RootUnavailable - NEVER 503 content_unreadable`() {
        if (skipAsRoot()) return
        withRoot { root, _, marks ->
            val real = LocalContentStore(root).also { it.scan() }
            // The base hash MUST match, or the CAS answers Mismatch(bytes) and never reaches the failing write - and
            // a Mismatch carrying real bytes is NOT ambiguous, because reading them proves the root was there.
            val baseHash = hasher(real.read(page)!!)
            val s = store(root, marks, probeOnce(root))
            // The identity capture succeeds (the file is still readable through a +x parent), and THEN the temp-create
            // fails: the root has lost read AND write permission, so the exit probe fails and the ambiguous Unreadable
            // classifies instead of surfacing as 503 `content_unreadable` - a code that promises a transient file
            // fault and invites a blind retry.
            chmod(root, PosixFilePermission.OWNER_EXECUTE)

            assertFailsWith<RootUnavailable> { s.compareAndSwapWrite(page, baseHash, "new".toByteArray(), hasher) }
            assertTrue(marks.isNotEmpty())
        }
    }

    /**
     * A [FileAtomics] whose `atomicMove` strips [leave] onto the root and then fails — so the temp file STILL EXISTS
     * when the `finally` tries to delete it, on a root that can no longer be written. That is the ONE arrangement that
     * actually reaches the cleanup throw: after a SUCCESSFUL move the temp is gone, and `deleteIfExists` on a missing
     * file answers ENOENT without raising, whatever the directory's permissions say.
     *
     * `FileAtomics` is a production seam that exists for exactly this (the exotic-FS fallback branches), and the
     * SUBJECT here is the store's exit classifier — so it is a collaborator, not the code under test.
     */
    private fun brokenMove(root: Path, vararg leave: PosixFilePermission) = object : FileAtomics by FileAtomics.Real {
        override fun atomicMove(source: Path, target: Path) {
            chmod(root, *leave)
            throw java.io.IOException("simulated move failure")
        }

        override fun copyReplace(source: Path, target: Path) = throw java.io.IOException("simulated copy failure")
    }

    @Test
    fun `a CLEANUP throw on a GONE root is classified - the classifier sits at the frame's EXIT, not at its outcomes`() {
        if (skipAsRoot()) return
        withRoot { root, _, marks ->
            val real = LocalContentStore(root).also { it.scan() }
            val baseHash = hasher(real.read(page)!!)
            // Root left --x: not writable (so the cleanup raises) and not readable (so the probe calls it GONE).
            val s = LocalContentStore(
                root = root,
                rootName = RootName.require("extra"),
                atomics = brokenMove(root, PosixFilePermission.OWNER_EXECUTE),
                onRootUnavailable = { marks += RootName.require("extra") },
                probeRoot = probeOnce(root),
            ).also { it.scan() }

            // The body computes `CasResult.Unreadable`, and then the `finally`'s deleteIfExists raises
            // AccessDeniedException, DISCARDS that typed result and propagates in its place. No outcome table can ever
            // name this, because a cleanup is not an outcome - it runs after the result is computed. Without a
            // classifier at the frame's EXIT, a raw IOException escapes the store, past every arm, into the 500
            // handler - and it also breaks the method's own documented "a pre-rename failure must NOT escape" contract.
            assertFailsWith<RootUnavailable> { s.compareAndSwapWrite(page, baseHash, "new".toByteArray(), hasher) }
            assertTrue(marks.isNotEmpty())
        }
    }

    @Test
    fun `a CLEANUP throw on a LIVE root still escapes as today's raw fault - and that is deliberate`() {
        if (skipAsRoot()) return
        withRoot { root, _, marks ->
            val real = LocalContentStore(root).also { it.scan() }
            val baseHash = hasher(real.read(page)!!)
            // Root left r-x: NOT writable (the cleanup raises) but readable and searchable - so the probe passes and
            // this is a LIVE root having a genuine fault.
            val s = LocalContentStore(
                root = root,
                rootName = RootName.require("extra"),
                atomics = brokenMove(root, PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
                onRootUnavailable = { marks += RootName.require("extra") },
            ).also { it.scan() }

            // Pinned, so nobody "fixes" it: converting this to a typed `Unreadable` would report a write as FAILED,
            // and a 500 claims nothing about the page while an `Unreadable` claims the write did not happen. The 500
            // is the smaller lie, and the accepted pre-existing residual.
            assertFailsWith<java.io.IOException> { s.compareAndSwapWrite(page, baseHash, "new".toByteArray(), hasher) }
            assertTrue(marks.isEmpty(), "a live root is NEVER marked - the mark is sticky and would demand a needless restart")
        }
    }

    @Test
    fun `createExclusive into a gone root is RootUnavailable, and the root is NOT recreated`() {
        withRoot { root, _, marks ->
            val s = store(root, marks, probeOnce(root))
            root.toFile().deleteRecursively()

            assertFailsWith<RootUnavailable> { s.createExclusive(TreePath.require("fresh.md"), "x".toByteArray(), hasher) }
            assertFalse(Files.exists(root), "a write must never RESURRECT the root it was refused")
            assertTrue(marks.isNotEmpty())
        }
    }

    @Test
    fun `writeAssetExclusive into a gone root is RootUnavailable - NEVER 404 PageMissing`() {
        withRoot { root, _, marks ->
            val s = store(root, marks, probeOnce(root))
            root.toFile().deleteRecursively()

            assertFailsWith<RootUnavailable> {
                s.writeAssetExclusive(grantForTests(), TreePath.require("asset.png"), byteArrayOf(1), hasher)
            }
            assertTrue(marks.isNotEmpty())
        }
    }

    // ---- the UNMOUNT: the root did not go missing, it went BLANK ----------------------------------
    //
    // Three `stat`s answer about the PATH, and an unmount does not take the path away: unmounting a volume AT the
    // root leaves the MOUNT-POINT DIRECTORY behind - present, empty, readable, executable. Reproduced exactly
    // below (move the volume aside, leave a fresh directory where it was mounted), because the only thing that
    // tells the two apart is the tree's IDENTITY - `fileKey()`, i.e. `(st_dev, st_ino)`, which is squarely the NIO
    // divergence surface this file exists for.

    @Test
    fun `an UNMOUNTED root is GONE, not empty - a different tree at the same path is never AVAILABLE`() {
        withRoot { root, store, _ ->
            assertTrue(store.available(), "the control: the tree it was constructed over is right there")

            unmount(root)

            assertFalse(
                store.available(),
                "an empty mount-point directory passed all three predicates: the pass would scan the root to zero " +
                    "files, take DELETE AUTHORITY over it, and purge its search rows and checkpoints on an unplug",
            )
        }
    }

    @Test
    fun `a read through an UNMOUNTED root is RootDown, and the store MARKS it - never the 404 that says the page is gone`() {
        withRoot { root, store, marks ->
            unmount(root)

            // NoBytes would send the classifier looking at the index; RootDown stops the question dead. The page is
            // not deleted - the disk is unplugged, and nothing about this tree may be believed.
            assertEquals(StoreRead.RootDown, store.readClassified(page))
            assertTrue(marks.isNotEmpty())
        }
    }

    @Test
    fun `a write into an UNMOUNTED root is refused - the page is never written to the mount point underneath`() {
        withRoot { root, _, marks ->
            val s = store(root, marks)
            unmount(root)

            assertFailsWith<RootUnavailable> { s.createExclusive(TreePath.require("fresh.md"), "x".toByteArray(), hasher) }
            assertFalse(
                Files.exists(root.resolve("fresh.md")),
                "the bytes would be stranded on the underlying disk, invisible once remounted",
            )
            assertTrue(marks.isNotEmpty())
        }
    }

    /** Takes the volume out from under [root] the way an unmount does: same path, same permissions, DIFFERENT tree. */
    private fun unmount(root: Path) {
        Files.move(root, root.resolveSibling("${root.fileName}-volume"))
        Files.createDirectory(root)
    }

    // ---- ...and the OTHER tree that is not the one we started on: a DEPLOY ------------------------
    //
    // `fileKey()` is `(st_dev, st_ino)`, and an unmount, a remount, an atomic-rename content release and a fresh
    // `git clone` into place ALL change it. It says a tree was REPLACED; it does not say by what. So the identity is
    // a HINT and what is THERE decides: a different tree that is BLANK is the mount point an unmount left behind; a
    // different tree WITH CONTENT is a deploy, and answering "vanished" for it would 503 a live, fully-readable root
    // - stickily, until a restart nobody should need - on the strength of an inode number.

    @Test
    fun `a REPLACED tree WITH CONTENT is a DEPLOY, not a loss - the probe rebinds and the root keeps serving`() {
        withRoot { root, store, marks ->
            // `mv site.new site` - the shape every atomic content release takes. Same path, different inode, full of
            // pages: nothing about it is an outage.
            Files.move(root, root.resolveSibling("${root.fileName}-volume"))
            Files.createDirectory(root)
            Files.writeString(root.resolve("page.md"), "# Page, redeployed\n")

            assertTrue(store.available(), "a fully-readable tree full of pages was called GONE over an inode number")
            assertTrue(marks.isEmpty(), "the mark is sticky until restart: a deploy must never demand one")
            assertTrue(store.readClassified(page) is StoreRead.Bytes, "and it serves the NEW tree's bytes, converged")
        }
    }

    @Test
    fun `the rebind STICKS - the redeployed tree can then be emptied in place, and that is a live empty root, not a loss`() {
        withRoot { root, store, marks ->
            Files.move(root, root.resolveSibling("${root.fileName}-volume"))
            Files.createDirectory(root)
            Files.writeString(root.resolve("page.md"), "# Page, redeployed\n")
            assertTrue(store.available())

            // The distinguishing case, and the only one that can prove the probe REBOUND rather than merely waving
            // the deploy through: an operator now deletes every page from the NEW tree. That is a genuine
            // full-corpus delete on a root that is right there - and a probe still comparing against the OLD tree's
            // key would find a different, EMPTY tree and cry unmount, refusing the deletion the operator asked for.
            // Whether an empty corpus is a delete is not liveness's question; it is the index's tripwire's.
            Files.delete(root.resolve("page.md"))

            assertTrue(store.available(), "an emptied-but-present tree is LIVE - the probe answers about the TREE, never the corpus")
            assertTrue(marks.isEmpty())
        }
    }

    // ---- the negative half: a LIVE root's genuine faults are UNCHANGED ----------------------------

    @Test
    fun `a READ-ONLY root is NOT root loss - reads still serve, and a write is content_unreadable, never marked`() {
        if (skipAsRoot()) return
        withRoot { root, store, marks ->
            // A read-only root is a readable, searchable directory - it passes all three probe predicates. Calling it
            // "unavailable" would be false on its face, and sticky-marking it would force a RESTART to recover from a
            // condition a `mount -o remount,rw` already fixed. It is a WRITE fault, and the honest answer says so.
            chmod(root, PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)

            assertTrue(store.readClassified(page) is StoreRead.Bytes, "every READ of a read-only root serves correct bytes")

            val outcome = store.createExclusive(TreePath.require("fresh.md"), "x".toByteArray(), hasher)
            assertTrue(outcome is CreateResult.Unreadable, "nothing landed, and it is retryable WITHOUT a restart")
            assertTrue(marks.isEmpty(), "never marked: the disk is right there, and the remedy is a remount, not a restart")
        }
    }

    @Test
    fun `a healthy write is untouched by the classifier - the ordinary path passes through byte-unchanged`() {
        withRoot { root, store, marks ->
            val created = store.createExclusive(TreePath.require("fresh.md"), "x".toByteArray(), hasher)
            assertTrue(created is CreateResult.Created)
            assertTrue(marks.isEmpty())

            val real = LocalContentStore(root).also { it.scan() }
            val cas = real.compareAndSwapWrite(page, hasher(real.read(page)!!), "new".toByteArray(), hasher)
            assertTrue(cas is CasResult.Written)
        }
    }

    private companion object {
        val ALL_PERMS = arrayOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }
}
