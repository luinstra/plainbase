package com.plainbase.frameworks.filesystem

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
