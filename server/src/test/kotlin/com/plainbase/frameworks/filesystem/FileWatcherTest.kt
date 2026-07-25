package com.plainbase.frameworks.filesystem

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * The §B1 watcher filters, proven against a REAL `WatchService` with positive controls: an event
 * for ordinary content always arrives (so "nothing arrived" can never pass vacuously), while
 * `.git` churn and the excluded DATA_DIR-nested-in-CONTENT_DIR subtree never reach the callback —
 * zero callbacks ⇒ zero scheduled rebuilds (§B2: events only schedule). Latch timeouts are
 * generous on purpose: macOS's polling WatchService delivers in multi-second batches (§B1); the
 * test asserts WHAT arrives, never how fast.
 */
class FileWatcherTest : FunSpec({

    fun pathsSeen(events: Iterable<TreePath>, prefix: String) = events.map { it.value }.filter { it.startsWith(prefix) }

    test(".git churn and the excluded nested DATA_DIR never reach the callback; content events do; the exclusion warns") {
        withTempTree(seed = { root ->
            Files.createDirectories(root.resolve(".git/objects"))
            Files.writeString(root.resolve(".git/config"), "[core]\n")
            Files.createDirectories(root.resolve("data"))
            writePage(root, "docs/page.md", "# Page\n")
        }) { root ->
            val warnings = captureWarnings {
                val seen = ConcurrentLinkedQueue<TreePath>()
                val sentinelArrived = CountDownLatch(1)
                val sentinel = TreePath.require("docs/sentinel.md")
                FileWatcher(
                    root = root,
                    ignoreRules = IgnoreRules(),
                    excluded = listOf(root.resolve("data")),
                    onChange = { path ->
                        seen += path
                        if (path == sentinel) sentinelArrived.countDown()
                    },
                ).use {
                    // Churn the filtered subtrees FIRST, then touch real content. Any filtered
                    // event would be delivered in the same (or an earlier) batch as the sentinel.
                    repeat(5) { n -> Files.writeString(root.resolve(".git/objects/blob-$n"), "git internals $n\n") }
                    Files.writeString(root.resolve(".git/config"), "[core]\n\tbare = false\n")
                    Files.writeString(root.resolve("data/search.db"), "app-owned state\n")
                    writePage(root, "docs/sentinel.md", "# Sentinel\n")

                    sentinelArrived.await(90, TimeUnit.SECONDS).shouldBeTrue()
                }
                pathsSeen(seen, ".git").shouldBeEmpty()
                pathsSeen(seen, "data").shouldBeEmpty()
            }
            warnings.single { it.contains("data") } shouldContain "excluded from the watch"
        }
    }

    test("a directory created after watch start is registered on sight: a later edit inside it is seen") {
        withTempTree(seed = { root -> writePage(root, "seed.md", "# Seed\n") }) { root ->
            val nested = TreePath.require("newdir/nested.md")
            val dirSeen = CountDownLatch(1)
            val nestedSeen = CountDownLatch(1)
            FileWatcher(root = root, ignoreRules = IgnoreRules(), excluded = emptyList(), onChange = { path ->
                if (path.value == "newdir") dirSeen.countDown()
                if (path == nested) nestedSeen.countDown()
            }).use {
                writePage(root, "newdir/nested.md", "# Nested\n")
                // Wait for the directory-creation event (which triggers registration) before the
                // edit, so the MODIFY below provably comes from the NEW directory's own key.
                dirSeen.await(90, TimeUnit.SECONDS).shouldBeTrue()
                writePage(root, "newdir/nested.md", "# Nested, edited\n")
                nestedSeen.await(90, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }

    test("an ANCESTOR DATA_DIR (CONTENT_DIR nested inside it) never excludes the tree: the watcher still fires") {
        // DATA_DIR=/x, CONTENT_DIR=/x/content is a valid config; applied naively, the exclusion
        // would match every content path and register ZERO watch keys — a silently dead watcher.
        withTempTree(seed = { dataDir -> writePage(dataDir, "content/page.md", "# Page\n") }) { dataDir ->
            val fired = CountDownLatch(1)
            FileWatcher(
                root = dataDir.resolve("content"),
                ignoreRules = IgnoreRules(),
                excluded = listOf(dataDir),
                onChange = { fired.countDown() },
            ).use {
                writePage(dataDir, "content/touched.md", "# Touched\n")
                fired.await(90, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }

    test("registration-failure classification: a vanished directory stays quiet; anything else WARNs the consequence") {
        // The real failure modes (inotify watch limit, permissions) are not cheaply fakeable
        // through a WatchService, so the classification policy is unit-tested directly.
        val warnings = captureWarnings {
            FileWatcher.logRegistrationFailure(Path.of("/tmp/vanished"), NoSuchFileException("/tmp/vanished"))
            FileWatcher.logRegistrationFailure(Path.of("/tmp/huge-tree/sub"), IOException("User limit of inotify watches reached"))
        }
        warnings.single() shouldContain "/tmp/huge-tree/sub"
        warnings.single() shouldContain "will NOT trigger rebuilds"
    }

    test("a cancelled key over a DELETED directory is no gap; one over a directory STILL STANDING is (C2)") {
        // The same shape as the row above, and for the same reason: a key cancellation cannot be provoked through a
        // real WatchService portably (inotify cancels on delete, macOS's poller does not cancel at all), so the
        // CLASSIFICATION - which is the part the epoch's safety rests on - is driven directly.
        //
        // An ordinary `rm -rf subdir` delivers every child's ENTRY_DELETE on that subdirectory's own key BEFORE the
        // key dies, and leaves no directory behind: those deletes were OBSERVED, the next scan confirms them, and the
        // epoch may honestly reap them. A key that dies while the directory is STILL THERE is the opposite animal -
        // an unmounted submount (the mountpoint stays behind), a rename-flip that swapped the subtree out from under
        // the watched inode. Those deliver NO child deletes at all, so their pages would vanish from the next scan
        // with nothing having been seen, which is the one inference this whole design forbids.
        withTempTree({}) { root ->
            val standing = Files.createDirectory(root.resolve("still-here"))

            FileWatcher.cancellationIsAGap(root.resolve("deleted-subdir")).shouldBeFalse()
            FileWatcher.cancellationIsAGap(standing).shouldBeTrue()
            withClue("an unknown key cannot be exonerated - it errs toward the break, which costs a re-earned epoch") {
                FileWatcher.cancellationIsAGap(null).shouldBeTrue()
            }
        }
    }

    test("an unexpected liveness-probe failure breaks the epoch before reporting the watcher dead") {
        withTempTree({}) { root ->
            val notifications = ConcurrentLinkedQueue<String>()
            val failed = CountDownLatch(1)

            FileWatcher(
                root = root,
                ignoreRules = IgnoreRules(),
                excluded = emptyList(),
                onChange = {},
                onBreak = { notifications += "break:$it" },
                rootIsAlive = { error("probe failed") },
                onFailure = {
                    notifications += "failure:${it.message}"
                    failed.countDown()
                },
                livenessInterval = 10.milliseconds,
            ).use {
                failed.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }

            notifications.toList() shouldBe listOf("break:${BreakCause.WATCHER_DIED}", "failure:probe failed")
        }
    }

    test("a root deleted before registration is reported lost instead of crashing watcher construction") {
        withTempTree({}) { parent ->
            val missing = parent.resolve("already-gone")
            val lost = CountDownLatch(1)

            FileWatcher(
                root = missing,
                ignoreRules = IgnoreRules(),
                excluded = emptyList(),
                onChange = {},
                rootIsAlive = { false },
                onRootLost = { lost.countDown() },
                livenessInterval = 10.milliseconds,
            ).use {
                lost.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }
})

/** Runs [block] with a list appender attached to the [FileWatcher] logger; returns the WARN messages. */
private fun captureWarnings(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(FileWatcher::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
}
