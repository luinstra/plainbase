package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * An IDLE root's disappearance - the one root-loss shape nothing else in the system can see.
 *
 * Every OTHER detector is driven by traffic (a write's probe, a rebuild's probe), so a root nobody writes to has
 * none; and a deleted or unmounted directory may raise no child event at all, while the event platforms DO raise
 * arrives as a silently-invalidated `WatchKey`. Undetected, that is not a slow 503 - it is a permanent 200 over
 * carried-forward bytes, with `health` reporting `available: true` forever. So: zero writes, zero events, and the
 * root still has to be MARKED.
 *
 * `@Tag("native")` + kotlin.test: `WatchService` is the NIO divergence surface itself (inotify on Linux, a polling
 * implementation on macOS, a closed-world provider under the native image), and what is under test here is
 * precisely what the JDK does NOT promise to tell us.
 */
@Tag("native")
class RootLossWatcherNativeTest {

    /**
     * The production wiring's own probe/mark/converge closure ([LocalContentStore.watch]), with a short liveness tick
     * so the bound is testable in seconds rather than the operational 5.
     *
     * The probe is a REAL store's `available()` - the same three-predicate check production hands the watcher. A
     * hand-rolled `isDirectory && isReadable` here would be a parallel implementation of the very predicate the
     * detection rests on, and it would pass whether or not the store's own was ever wired in.
     */
    private fun watch(root: Path, marks: MutableList<RootName>, converged: ConcurrentLinkedQueue<TreePath>) =
        FileWatcher(
            root = root,
            ignoreRules = IgnoreRules(),
            excluded = emptyList(),
            onChange = { converged += it },
            rootIsAlive = LocalContentStore(root)::available,
            onRootLost = {
                marks += RootName.require("extra")
                converged += ContentStore.OVERFLOW
            },
            livenessInterval = 200.milliseconds,
        )

    @Test
    fun `an IDLE root RENAMED away is marked - the shape that raises no event at all, on any platform`() {
        // A rename (a `mv`, an unmount) is the HARD shape and the honest one: it touches no child, so no ENTRY_DELETE
        // is raised, no rebuild is scheduled, and on Linux the JDK does not even invalidate the key - every inode is
        // still exactly where it was, and only the PATH is gone. An `rm -rf` would instead delete the pages first and
        // let their child events schedule the rebuild whose probe marks the root, which is a test that passes against
        // a watcher with no root-loss detection whatsoever.
        val root = Files.createTempDirectory("pb-rootloss-idle")
        Files.writeString(root.resolve("page.md"), "# Page\n")
        val marks = mutableListOf<RootName>()
        val converged = ConcurrentLinkedQueue<TreePath>()

        try {
            watch(root, marks, converged).use {
                Files.move(root, root.resolveSibling("${root.fileName}-unmounted"))

                awaitUntil("an idle root's loss was never detected: it would serve stale 200s forever") { marks.isNotEmpty() }
            }
            assertEquals(listOf(RootName.require("extra")), marks, "marked exactly once - the D5 status is sticky")
            assertTrue(
                ContentStore.OVERFLOW in converged,
                "the loss must also SCHEDULE the converging pass, or the tree/health memo keeps answering from the last publication",
            )
        } finally {
            root.resolveSibling("${root.fileName}-unmounted").toFile().deleteRecursively()
        }
    }

    @Test
    fun `an IDLE root DELETED out from under the watcher is marked too - the other shape, and it must not need a rebuild`() {
        val root = Files.createTempDirectory("pb-rootloss-deleted")
        val marks = mutableListOf<RootName>()
        val converged = ConcurrentLinkedQueue<TreePath>()

        watch(root, marks, converged).use {
            root.toFile().deleteRecursively() // empty: nothing inside to raise a child event either

            awaitUntil("a deleted root with no pages raises no child event; the probe is the only detector") { marks.isNotEmpty() }
        }
        assertEquals(listOf(RootName.require("extra")), marks)
    }

    @Test
    fun `the watch loop does not wedge when its last key dies - the liveness tick is what wakes it`() {
        // A root deleted out from under the watcher leaves the WatchService with no valid keys at all. A take()-based
        // loop would block there forever with nothing left to signal it; the poll timeout is what keeps the worker
        // alive long enough to notice, and then it exits on purpose (D5 unavailability is sticky - there is nothing
        // left to watch, and a surviving loop could only re-detect the same loss every interval).
        val root = Files.createTempDirectory("pb-rootloss-wedge")
        val lost = CountDownLatch(1)
        val watcher = FileWatcher(
            root = root,
            ignoreRules = IgnoreRules(),
            excluded = emptyList(),
            onChange = {},
            rootIsAlive = LocalContentStore(root)::available,
            onRootLost = { lost.countDown() },
            livenessInterval = 200.milliseconds,
        )

        root.toFile().deleteRecursively()

        assertTrue(lost.await(60, TimeUnit.SECONDS), "the worker never woke: a wedged loop is an undetectable outage")
        watcher.close() // and it still closes cleanly, on a worker that has already exited
    }

    @Test
    fun `a LIVE root is never marked - the tick must not cry wolf on a tree that is simply quiet`() {
        // The negative control the first test cannot be trusted without: a sticky mark on a healthy root would
        // demand a restart to recover from nothing at all.
        val root = Files.createTempDirectory("pb-rootloss-live")
        val marks = mutableListOf<RootName>()
        val converged = ConcurrentLinkedQueue<TreePath>()
        try {
            watch(root, marks, converged).use {
                Thread.sleep(1_000) // many liveness ticks, all of which must answer "still here"
            }
            assertTrue(marks.isEmpty(), "an idle-but-present root is available; the probe is the authority, not the silence")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Polls [condition] until true, failing with [message] after a generous deadline (the latch idiom, for a plain flag). */
    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, message)
            Thread.sleep(25)
        }
    }
}
