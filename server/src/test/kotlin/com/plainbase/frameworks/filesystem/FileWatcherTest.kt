package com.plainbase.frameworks.filesystem

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.IdentitySafeFailureAccumulator
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.service.localRoot
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.lifecycle.Stage0cParentDeadline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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

    test("watch registration excludes a nested DATA_DIR spelled through a root alias") {
        val base = Files.createTempDirectory("pb-watch-data-alias")
        try {
            val real = Files.createDirectories(base.resolve("real"))
            val alias = base.resolve("alias")
            try {
                Files.createSymbolicLink(alias, real)
            } catch (_: IOException) {
                return@test
            }
            Files.createDirectories(real.resolve("state"))
            val docs = Files.createDirectories(real.resolve("docs"))
            val registrations = ConcurrentLinkedQueue<Path>()

            FileWatcher(
                root = real,
                ignoreRules = IgnoreRules(),
                excluded = listOf(alias.resolve("state")),
                onChange = {},
                registerDirectory = { directory, service ->
                    registrations.add(directory)
                    directory.register(
                        service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                    )
                },
            ).use {
                registrations.toSet() shouldBe setOf(real, docs)
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    test("a legacy ignore glob matching the empty relative path does not skip the root registration") {
        withTempTree(seed = { root -> Files.createDirectories(root.resolve("ignored")) }) { root ->
            val registrations = ConcurrentLinkedQueue<Path>()

            FileWatcher(
                root = root,
                ignoreRules = IgnoreRules(listOf("*")),
                excluded = emptyList(),
                onChange = {},
                registerDirectory = { directory, service ->
                    registrations.add(directory)
                    directory.register(
                        service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                    )
                },
            ).use {
                registrations.toSet() shouldBe setOf(root)
            }
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

    test("configured traversal watches admitted directories and ignores excluded churn") {
        withTempTree(seed = { root ->
            Files.createDirectories(root.resolve("docs/remove"))
            Files.createDirectories(root.resolve(".crew/reviews"))
            Files.createDirectories(root.resolve("outside"))
            writePage(root, "docs/remove/page.md", "# Remove\n")
            writePage(root, "docs/_folder.yaml", "title: Docs\n")
        }) { root ->
            val ignoreRules = IgnoreRules()
            val rootConfig = localRoot("docs", root).copy(
                includes = listOf("docs/**", ".crew/**"),
                excludes = listOf(".crew/reviews/**"),
            )
            val membership = localContentPathPolicy(rootConfig, root, ignoreRules, emptyList())
            val registrations = ConcurrentLinkedQueue<String>()
            val seen = ConcurrentLinkedQueue<TreePath>()
            val docsSeen = CountDownLatch(1)
            val crewSeen = CountDownLatch(1)
            val metadataSeen = CountDownLatch(1)
            val directoryDeleteSeen = CountDownLatch(1)

            FileWatcher(
                root = root,
                ignoreRules = ignoreRules,
                excluded = emptyList(),
                onChange = { path ->
                    seen += path
                    when (path.value) {
                        "docs/sentinel.md" -> docsSeen.countDown()
                        ".crew/keep.md" -> crewSeen.countDown()
                        "docs" -> metadataSeen.countDown()
                        "docs/remove" -> directoryDeleteSeen.countDown()
                    }
                },
                registerDirectory = { directory, service ->
                    registrations += root.relativize(directory).joinToString("/")
                    directory.register(
                        service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                    )
                },
                policy = membership,
            ).use {
                registrations.toSet() shouldBe setOf("", "docs", "docs/remove", ".crew")

                writePage(root, ".crew/reviews/ignored.md", "# Ignored\n")
                writePage(root, "outside/ignored.md", "# Ignored\n")
                writePage(root, ".crew/keep.md", "# Keep\n")
                writePage(root, "docs/sentinel.md", "# Sentinel\n")
                Files.writeString(root.resolve("docs/_folder.yaml"), "title: Documentation\n")
                Files.delete(root.resolve("docs/remove/page.md"))
                Files.delete(root.resolve("docs/remove"))

                docsSeen.await(90, TimeUnit.SECONDS).shouldBeTrue()
                crewSeen.await(90, TimeUnit.SECONDS).shouldBeTrue()
                metadataSeen.await(90, TimeUnit.SECONDS).shouldBeTrue()
                directoryDeleteSeen.await(90, TimeUnit.SECONDS).shouldBeTrue()
            }

            pathsSeen(seen, ".crew/reviews").shouldBeEmpty()
            pathsSeen(seen, "outside").shouldBeEmpty()
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

    test("should close the raw WatchService when real partial registration coverage callback fails") {
        val root = Files.createTempDirectory("plainbase-watch-partial")
        Files.createDirectory(root.resolve("partial"))
        var retain = false
        var raw: WatchService? = null
        val failure = IllegalStateException("partial coverage callback failed")
        val cleanup = IllegalStateException("watch service close failed")
        var testFailure: Throwable? = null
        try {
            try {
                val actual = shouldThrow<IllegalStateException> {
                    FileWatcher(
                        root = root,
                        ignoreRules = IgnoreRules(),
                        excluded = emptyList(),
                        onChange = {},
                        watchServiceFactory = {
                            root.fileSystem.newWatchService().also { raw = it }
                        },
                        registerDirectory = { directory, service ->
                            if (directory.fileName.toString() == "partial") throw IOException("registration failed")
                            directory.register(
                                service,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                            )
                        },
                        onCoverage = { coverage ->
                            coverage shouldBe com.plainbase.domain.content.WatchCoverage.PARTIAL
                            throw failure
                        },
                        closeWatchService = { service ->
                            service.close()
                            throw cleanup
                        },
                    )
                }

                actual shouldBe failure
                shouldThrow<ClosedWatchServiceException> { requireNotNull(raw).poll() }
                actual.suppressed.single() shouldBe cleanup
            } catch (caught: Throwable) {
                testFailure = caught
                throw caught
            } finally {
                if (!closeRetainedWatchService(raw, testFailure)) retain = true
            }
        } finally {
            if (!retain) root.toFile().deleteRecursively()
        }
    }

    test("W2: a service-close failure still joins the real callback worker and preserves the original failure") {
        val parent = Stage0cParentDeadline(20_000)
        val root = Files.createTempDirectory("plainbase-stage0c-w2-stop-error")
        val target = TreePath.require("w2-callback.md")
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val stopCalled = CountDownLatch(1)
        val callbackDone = AtomicBoolean(false)
        val closeFailure = AtomicReference<Throwable?>()
        val stopFailure = IllegalStateException("W2 watch-service close failed")
        var watcher: FileWatcher? = null
        var closer: Thread? = null
        var worker: Thread? = null
        var retain = false
        var cleanupInterrupted = false
        val failures = IdentitySafeFailureAccumulator()
        fun joinOwned(current: Thread, description: String, maxMillis: Long): Boolean = try {
            parent.join(current, description, maxMillis)
        } catch (interrupted: InterruptedException) {
            cleanupInterrupted = true
            Thread.interrupted()
            failures.add(interrupted)
            false
        }
        fun startCloser(name: String): Thread = thread(name = name) {
            try {
                requireNotNull(watcher).close()
            } catch (failure: Throwable) {
                closeFailure.compareAndSet(null, failure)
            }
        }
        try {
            writePage(root, "seed.md", "# Seed\n")
            watcher = FileWatcher(
                root = root,
                ignoreRules = IgnoreRules(),
                excluded = emptyList(),
                onChange = { path ->
                    if (path == target) {
                        callbackEntered.countDown()
                        while (true) {
                            try {
                                if (callbackRelease.await(10, TimeUnit.MILLISECONDS)) break
                            } catch (_: InterruptedException) {
                                // The callback intentionally remains in-flight until the test releases it.
                            }
                        }
                        callbackDone.set(true)
                    }
                },
                closeWatchService = { service ->
                    service.close()
                    stopCalled.countDown()
                    throw stopFailure
                },
            )
            worker = requireNotNull(watcher.workerForTest())
            writePage(root, target.value, "# Callback\n")
            parent.await(callbackEntered, "W2 callback entry", 90_000) shouldBe true
            closer = startCloser("plainbase-stage0c-w2-watcher-close")
            parent.await(stopCalled, "W2 service stop entry", 5_000) shouldBe true
            worker.isAlive shouldBe true
            closer.isAlive shouldBe true
            Thread.sleep(250)
            parent.check("W2 stop-error pending interval")
            closeFailure.get() shouldBe null
            callbackRelease.countDown()
            parent.join(closer, "W2 stop-error cleanup", 10_000) shouldBe true
            closer.isAlive shouldBe false
            closeFailure.get() shouldBe stopFailure
            callbackDone.get() shouldBe true
            parent.join(worker, "W2 callback worker cleanup", 10_000) shouldBe true
            worker.isAlive shouldBe false
            watcher.isClosedForTest() shouldBe true
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                cleanupInterrupted = true
                Thread.interrupted()
            }
            failures.add(failure)
        } finally {
            callbackRelease.countDown()
            val cleanupCloser = closer ?: watcher?.let { startCloser("plainbase-stage0c-w2-stop-error-cleanup") }
            closer = cleanupCloser
            if (cleanupCloser != null && !joinOwned(cleanupCloser, "W2 stop-error failure cleanup", 10_000)) retain = true
            if (cleanupCloser != null && !cleanupCloser.isAlive && watcher != null && !watcher.isClosedForTest()) {
                val retryCloser = startCloser("plainbase-stage0c-w2-stop-error-retry")
                if (!joinOwned(retryCloser, "W2 stop-error watcher cleanup", 10_000)) retain = true
            }
            worker?.let { if (!joinOwned(it, "W2 stop-error callback worker cleanup", 10_000)) retain = true }
            worker?.let { if (it.isAlive) retain = true }
            if (watcher != null && !watcher.isClosedForTest()) retain = true
            closeFailure.get()?.let { if (it !== stopFailure) failures.add(it) }
            if (retain) failures.add(IllegalStateException("W2 stop-error fixture retained a surviving watcher or worker"))
            if (!retain) {
                runCatching { root.toFile().deleteRecursively() }.onFailure(failures::add)
            }
            if (cleanupInterrupted) Thread.currentThread().interrupt()
        }
        failures.failure?.let { throw it }
    }

    test("W2: a callback held beyond the old 10s join keeps real close pending") {
        val parent = Stage0cParentDeadline(20_000)
        val root = Files.createTempDirectory("plainbase-stage0c-w2-bounded-join")
        val target = TreePath.require("w2-bounded-callback.md")
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val stopCalled = CountDownLatch(1)
        val callbackDone = AtomicBoolean(false)
        val closeStartedAtNanos = AtomicLong()
        val closeFailure = AtomicReference<Throwable?>()
        var watcher: FileWatcher? = null
        var closer: Thread? = null
        var worker: Thread? = null
        var retain = false
        var cleanupInterrupted = false
        val failures = IdentitySafeFailureAccumulator()
        fun joinOwned(current: Thread, description: String, maxMillis: Long): Boolean = try {
            parent.join(current, description, maxMillis)
        } catch (interrupted: InterruptedException) {
            cleanupInterrupted = true
            Thread.interrupted()
            failures.add(interrupted)
            false
        }
        fun startCloser(name: String): Thread = thread(name = name) {
            try {
                requireNotNull(watcher).close()
            } catch (failure: Throwable) {
                closeFailure.compareAndSet(null, failure)
            }
        }
        try {
            writePage(root, "seed.md", "# Seed\n")
            watcher = FileWatcher(
                root = root,
                ignoreRules = IgnoreRules(),
                excluded = emptyList(),
                onChange = { path ->
                    if (path == target) {
                        callbackEntered.countDown()
                        callbackRelease.await()
                        callbackDone.set(true)
                    }
                },
                closeWatchService = { service ->
                    closeStartedAtNanos.set(System.nanoTime())
                    service.close()
                    stopCalled.countDown()
                },
            )
            worker = requireNotNull(watcher.workerForTest())
            writePage(root, target.value, "# Callback\n")
            parent.await(callbackEntered, "W2 bounded callback entry", 90_000) shouldBe true
            closer = startCloser("plainbase-stage0c-w2-bounded-close")
            parent.await(stopCalled, "W2 bounded service stop entry", 5_000) shouldBe true
            val closeStart = closeStartedAtNanos.get()
            check(closeStart > 0L) { "W2 close entry was not recorded" }
            parent.waitUntilElapsed(closeStart, 10_500, "W2 old bounded join discriminator")
            closer.isAlive shouldBe true
            worker.isAlive shouldBe true
            callbackDone.get() shouldBe false
            callbackRelease.countDown()
            parent.join(closer, "W2 bounded cleanup", 10_000) shouldBe true
            closer.isAlive shouldBe false
            closeFailure.get() shouldBe null
            callbackDone.get() shouldBe true
            parent.join(worker, "W2 bounded callback worker cleanup", 10_000) shouldBe true
            worker.isAlive shouldBe false
            watcher.isClosedForTest() shouldBe true
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                cleanupInterrupted = true
                Thread.interrupted()
            }
            failures.add(failure)
        } finally {
            callbackRelease.countDown()
            val cleanupCloser = closer ?: watcher?.let { startCloser("plainbase-stage0c-w2-bounded-cleanup") }
            closer = cleanupCloser
            if (cleanupCloser != null && !joinOwned(cleanupCloser, "W2 bounded failure cleanup", 10_000)) retain = true
            if (cleanupCloser != null && !cleanupCloser.isAlive && watcher != null && !watcher.isClosedForTest()) {
                val retryCloser = startCloser("plainbase-stage0c-w2-bounded-retry")
                if (!joinOwned(retryCloser, "W2 bounded watcher cleanup", 10_000)) retain = true
            }
            worker?.let { if (!joinOwned(it, "W2 bounded callback worker cleanup", 10_000)) retain = true }
            worker?.let { if (it.isAlive) retain = true }
            if (watcher != null && !watcher.isClosedForTest()) retain = true
            failures.add(closeFailure.get())
            if (retain) failures.add(IllegalStateException("W2 bounded fixture retained a surviving watcher or worker"))
            if (!retain) {
                runCatching { root.toFile().deleteRecursively() }.onFailure(failures::add)
            }
            if (cleanupInterrupted) Thread.currentThread().interrupt()
        }
        failures.failure?.let { throw it }
    }
})

private fun closeRetainedWatchService(service: WatchService?, primary: Throwable?): Boolean {
    if (service == null || watchServiceClosed(service)) return true
    try {
        service.close()
        check(watchServiceClosed(service)) { "retained WatchService remained open" }
        return true
    } catch (cleanup: Throwable) {
        if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        return watchServiceClosed(service)
    }
}

private fun watchServiceClosed(service: WatchService): Boolean = try {
    service.poll()
    false
} catch (_: ClosedWatchServiceException) {
    true
}

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
