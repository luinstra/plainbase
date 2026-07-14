package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.WatchCoverage
import com.plainbase.domain.root.BreakCause
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The C2 break machinery against the REAL `WatchService` and the REAL filesystem - the divergence surfaces
 * CLAUDE.md's native-tag policy names (NIO edge behavior, the stat/permission ladder), where the JDK's own
 * semantics are what the epoch's safety rests on and a mock would simply agree with whatever it was told.
 *
 * An observation epoch turns "the last scan saw this page and this one does not" into a DELETE. Everything here
 * is about the honesty of the "and nothing happened in between" half of that sentence.
 */
@Tag("native")
class ObservationBreakNativeTest {

    private fun <T> withTree(block: (Path) -> T): T {
        val root = Files.createTempDirectory("pb-native-break")
        return try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * **B1: a stale WHOLE must not be able to clobber a fresh PARTIAL.**
     *
     * The constructing thread used to report coverage AFTER starting the worker - and the worker can re-register and
     * re-report from its very first tick, so the two raced and the CONSTRUCTOR's report was the staler of the pair.
     * A stale WHOLE landing on top of a fresh PARTIAL used to cost a slow rebuild. Now it poisons the OBSERVATION
     * EPOCH, which would take delete authority over a tree it is not fully watching - so the ordering is a
     * correctness constraint and it is pinned as one.
     *
     * The invariant is checked where it actually lives: the report must be issued BEFORE the worker exists at all.
     * With no worker there is nothing to race, which is a stronger thing to assert than "the race did not happen to
     * fire this time" - and it is deterministic, where a race is not.
     */
    @Test
    fun `watch coverage is reported BEFORE the worker starts, so no report of its can be raced by a stale one`() {
        withTree { root ->
            val unwatchable = Files.createDirectory(root.resolve("locked"))
            if (!supportsPosix(root)) return@withTree
            Files.setPosixFilePermissions(unwatchable, PosixFilePermissions.fromString("---------"))
            if (Files.isReadable(unwatchable)) return@withTree // running as root: the permission drop is inert

            val workerAliveAtReport = ConcurrentLinkedQueue<Boolean>()
            val coverage = ConcurrentLinkedQueue<WatchCoverage>()
            val breaks = ConcurrentLinkedQueue<BreakCause>()
            try {
                FileWatcher(
                    root = root,
                    ignoreRules = IgnoreRules(),
                    excluded = emptyList(),
                    onChange = {},
                    onCoverage = {
                        workerAliveAtReport += watcherWorkerExists()
                        coverage += it
                    },
                    onBreak = { breaks += it },
                ).use {
                    assertEquals(listOf(WatchCoverage.PARTIAL), coverage.toList(), "an unregisterable subtree is PARTIAL coverage")
                    assertFalse(
                        workerAliveAtReport.any { it },
                        "coverage was reported while the worker thread was already running - it can therefore be " +
                            "clobbered by a report the worker issues first, and a stale WHOLE now poisons the epoch",
                    )
                    // ...and losing coverage is a BREAK, not merely a slower convergence: edits under a subtree
                    // nobody is watching raise NO event, so from here on this watcher samples the tree.
                    assertContains(breaks.toList(), BreakCause.COVERAGE_LOST)
                }
            } finally {
                Files.setPosixFilePermissions(unwatchable, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    /**
     * **`ScanResult.complete` has to be HONEST, and this is the case that used to make it a lie.**
     *
     * `r--` on a directory is the nastiest shape the filesystem offers here: the read bit still LISTS the names, and
     * the missing search bit means nothing under them can be opened - so the walk sees the entries and cannot stat a
     * single one. The old code asked `isDirectory`, got `false` from a swallowed IOException, and filed a whole
     * subtree as a regular FILE; the scan then returned `complete = true` for a tree it demonstrably had not seen.
     * A pass would have handed that to an epoch as evidence.
     */
    @Test
    fun `a child the walk can NAME but not STAT makes the scan INCOMPLETE, rather than silently disappearing`() {
        withTree { root ->
            if (!supportsPosix(root)) return@withTree
            val locked = Files.createDirectory(root.resolve("locked"))
            Files.writeString(locked.resolve("hidden.md"), "# Hidden\n")
            Files.writeString(root.resolve("visible.md"), "# Visible\n")

            val store = LocalContentStore(root)
            assertTrue(store.scan().complete, "the control: an ordinary readable tree is COMPLETE")

            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r--r--r--"))
            try {
                if (Files.isExecutable(root)) return@withTree // running as root: the permission drop is inert
                val scan = store.scan()
                assertFalse(scan.complete, "a walk that could not stat its own children must NOT claim it saw the whole tree")
            } finally {
                Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    /**
     * A DEPLOY (`mv site.new site`) leaves a healthy, fully readable root that serves every byte - and it is a new
     * universe. Everything an epoch witnessed, it witnessed against the OLD inodes, which are what the watches track,
     * so the store's rebind has to be REPORTED or a release would reap the site it had just replaced.
     *
     * Driven through `available()`, which is the SAME public probe the rebuild and the watcher's liveness tick call -
     * so this is the real production path and not a seam. The paired negative is what makes it a test rather than a
     * tautology: an UNCHANGED tree must not cry rebind, or every tick would break the epoch and nothing would ever
     * converge. Native-tagged because `fileKey()` is `(st_dev, st_ino)` on Unix and null on filesystems that cannot
     * key a directory - the exact divergence the whole rebind rule is built on.
     */
    @Test
    fun `the store reports a REBIND when its tree is swapped, and stays silent when it is not`() {
        withTree { parent ->
            val root = Files.createDirectory(parent.resolve("site"))
            Files.writeString(root.resolve("page.md"), "# Page\n")

            val rebinds = ConcurrentLinkedQueue<Unit>()
            val store = LocalContentStore(root = root, onIdentityRebind = { rebinds += Unit })

            assertTrue(store.available())
            assertTrue(store.available())
            assertEquals(0, rebinds.size, "an unchanged tree is not a rebind - a probe that cried wolf would break every epoch")

            // The release: a DIFFERENT, populated tree at the same path.
            val fresh = Files.createDirectory(parent.resolve("site.new"))
            Files.writeString(fresh.resolve("page.md"), "# Fresh\n")
            root.toFile().deleteRecursively()
            Files.move(fresh, root)

            assertTrue(store.available(), "a populated replacement is a healthy root: it SERVES, and it is still a new universe")
            assertEquals(1, rebinds.size, "the swap must be reported exactly once - one rebind is one break")

            repeat(3) { store.available() }
            assertEquals(1, rebinds.size, "and it must not be re-reported on every subsequent tick")
        }
    }

    /**
     * **A tree swapped at the same path ALWAYS breaks the epoch - by one detector or the other.**
     *
     * This asserts the SAFETY PROPERTY, not a mechanism, because no single mechanism holds on both platforms and
     * an epoch that survives a swap reaps the corpus it had witnessed:
     *
     *  - **Linux/ext4:** `rm -rf site && mkdir site` REUSES the directory's inode, so `fileKey` compares EQUAL and
     *    the store's identity probe is BLIND. The `WatchService` (inotify) cancels the key on the deleted directory,
     *    and that cancellation is the break. CI proved the need for this: the domain-level swap test passed on macOS
     *    and FAILED on Linux, where the epoch stayed alive and would have reaped the 20 pages it had witnessed.
     *  - **macOS/APFS:** the recreated directory gets a NEW inode, so the probe reports the rebind - while the JDK's
     *    `WatchService` is a POLLER there and does not reliably cancel the key.
     *
     * So the two detectors are not redundant, they are complementary, and PRODUCTION WIRES BOTH (`LocalContentStore`'s
     * `onIdentityRebind` and `FileWatcher`'s `onBreak`). Asserting either-fires is the only honest cross-platform
     * statement of the invariant the corpus actually depends on. It is also why an epoch REQUIRES a live watcher:
     * no watcher, no coverage, no epoch, no proof, no reap.
     */
    @Test
    fun `a tree swapped at the same path ALWAYS breaks the epoch - the probe and the watcher cover each other`() {
        withTree { parent ->
            val root = Files.createDirectory(parent.resolve("site"))
            Files.writeString(root.resolve("page.md"), "# Page\n")

            val breaks = ConcurrentLinkedQueue<String>()
            val store = LocalContentStore(root = root, onIdentityRebind = { breaks += "IDENTITY_REBIND" })

            FileWatcher(
                root = root,
                ignoreRules = IgnoreRules(),
                excluded = emptyList(),
                onChange = {},
                onBreak = { breaks += it.name },
            ).use {
                assertTrue(store.available())

                // The shape CI caught: delete the tree and recreate it at the SAME path. On ext4 the inode is reused.
                root.toFile().deleteRecursively()
                Files.createDirectory(root)
                Files.writeString(root.resolve("decoy.md"), "# Decoy\n")

                store.available() // drives the probe, exactly as the rebuild and the liveness tick do

                val deadline = System.nanoTime() + 20_000_000_000L
                while (breaks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)

                assertTrue(
                    breaks.isNotEmpty(),
                    "a swapped tree MUST break the epoch, or a decoy reaps the corpus it never saw. " +
                        "Neither detector holds on both platforms - the inode probe is blind to ext4's reuse, and " +
                        "the macOS poller does not cancel the key - so production wires BOTH and this asserts the " +
                        "invariant they jointly guarantee.",
                )
            }
        }
    }

    private fun supportsPosix(path: Path): Boolean =
        path.fileSystem.supportedFileAttributeViews().contains("posix")

    private fun watcherWorkerExists(): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == "plainbase-file-watcher" && it.isAlive }
}
