package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.WatchCoverage
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Native-image smoke for the §B1 watcher: `WatchService` is plain JDK I/O and is EXPECTED to work
 * under native-image, but the gate proves it rather than assumes it (§B1) — without this test the
 * closed-world image would not even compile the `FileWatcher`/`ContentStore.watch` path. One watch
 * over a temp tree, one file touched, one callback: the schedule trigger fires natively.
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image. The
 * generous timeout covers macOS's polling WatchService when the JVM test task runs this suite.
 */
@Tag("native")
class FileWatcherNativeTest {

    @Test
    fun `watching a temp tree sees a touched file and would schedule a rebuild`() {
        val root = Files.createTempDirectory("pb-native-watch")
        try {
            val fired = CountDownLatch(1)
            LocalContentStore(root).watch(onChange = { fired.countDown() }).use {
                Files.writeString(root.resolve("touched.md"), "# Touched\n")
                assertTrue(fired.await(90, TimeUnit.SECONDS), "watch event never arrived")
            }
        } finally {
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `real partial registration callback failure closes the raw WatchService`() {
        val root = Files.createTempDirectory("pb-native-watch-partial")
        Files.createDirectories(root.resolve("partial"))
        var raw: WatchService? = null
        var testFailure: Throwable? = null
        try {
            val failure = IllegalStateException("native partial coverage failed")
            val cleanup = IllegalStateException("native watch close failed")
            val actual = assertFailsWith<IllegalStateException> {
                FileWatcher(
                    root = root,
                    ignoreRules = IgnoreRules(),
                    excluded = emptyList(),
                    onChange = {},
                    watchServiceFactory = { root.fileSystem.newWatchService().also { raw = it } },
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
                        assertEquals(WatchCoverage.PARTIAL, coverage)
                        throw failure
                    },
                    closeWatchService = { service ->
                        service.close()
                        throw cleanup
                    },
                )
            }
            assertSame(failure, actual)
            assertFailsWith<ClosedWatchServiceException> { requireNotNull(raw).poll() }
            assertSame(cleanup, actual.suppressed.single())
        } catch (caught: Throwable) {
            testFailure = caught
            throw caught
        } finally {
            val closed = closeRetainedWatchService(raw, testFailure)
            if (closed) root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native watcher close joins a held real callback worker`() {
        val root = Files.createTempDirectory("pb-native-watch-close")
        val target = root.resolve("held.md")
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val callbackDone = AtomicBoolean(false)
        val closeFailure = AtomicReference<Throwable?>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var primary: Throwable? = null
        var interrupted = false
        fun addFailure(failure: Throwable?) {
            if (failure == null || !seen.add(failure)) return
            val current = primary
            if (current == null) primary = failure else current.addSuppressed(failure)
        }
        fun joinOwned(thread: Thread, millis: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            while (thread.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    thread.join(minOf(100L, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)))
                } catch (failure: InterruptedException) {
                    interrupted = true
                    Thread.interrupted()
                    addFailure(failure)
                    return false
                }
            }
            return true
        }
        var watcher: FileWatcher? = null
        var worker: Thread? = null
        var closeThread: Thread? = null
        var survivor = false
        try {
            val currentWatcher = LocalContentStore(root).watch(
                onChange = { path ->
                    if (path.value == "held.md") {
                        callbackEntered.countDown()
                        while (true) {
                            try {
                                if (callbackRelease.await(10, TimeUnit.MILLISECONDS)) break
                            } catch (_: InterruptedException) {
                                // The callback intentionally remains live until the test releases it.
                            }
                        }
                        callbackDone.set(true)
                    }
                },
            )
            watcher = currentWatcher as FileWatcher
            worker = requireNotNull(watcher.workerForTest())
            Files.writeString(target, "# held\n")
            assertTrue(callbackEntered.await(90, TimeUnit.SECONDS), "native callback never arrived")

            val currentCloser = thread(name = "plainbase-native-watch-close") {
                try {
                    currentWatcher.close()
                } catch (failure: Throwable) {
                    closeFailure.compareAndSet(null, failure)
                }
            }
            closeThread = currentCloser
            assertFalse(joinOwned(currentCloser, 500), "close returned during the short observation")
            assertTrue(currentCloser.isAlive, "close returned while the callback was held")
            assertTrue(requireNotNull(worker).isAlive, "watch worker died before the callback was released")
            assertFalse(callbackDone.get())

            callbackRelease.countDown()
            assertTrue(joinOwned(currentCloser, 10_000), "close thread did not terminate")
            assertTrue(joinOwned(requireNotNull(worker), 10_000), "watch worker did not terminate")
            assertFalse(currentCloser.isAlive)
            assertFalse(requireNotNull(worker).isAlive)
            assertTrue(callbackDone.get())
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
            addFailure(failure)
        } finally {
            callbackRelease.countDown()
            val cleanupCloser = closeThread ?: watcher?.let { currentWatcher ->
                thread(name = "plainbase-native-watch-cleanup") {
                    try {
                        currentWatcher.close()
                    } catch (failure: Throwable) {
                        closeFailure.compareAndSet(null, failure)
                    }
                }
            }
            closeThread = cleanupCloser
            if (cleanupCloser != null && !joinOwned(cleanupCloser, 10_000)) survivor = true
            if (cleanupCloser != null && !cleanupCloser.isAlive && watcher?.isClosedForTest() == false) {
                val retryCloser = thread(name = "plainbase-native-watch-retry") {
                    try {
                        requireNotNull(watcher).close()
                    } catch (failure: Throwable) {
                        closeFailure.compareAndSet(null, failure)
                    }
                }
                if (!joinOwned(retryCloser, 10_000)) survivor = true
            }
            worker?.let { if (!joinOwned(it, 10_000)) survivor = true }
            if (worker?.isAlive == true || watcher?.isClosedForTest() == false) survivor = true
            addFailure(closeFailure.get())
            if (survivor) addFailure(IllegalStateException("native watcher fixture retained a surviving closer or worker"))
            if (!survivor) {
                runCatching { root.toFile().deleteRecursively() }.onFailure(::addFailure)
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        primary?.let { throw it }
    }
}

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
