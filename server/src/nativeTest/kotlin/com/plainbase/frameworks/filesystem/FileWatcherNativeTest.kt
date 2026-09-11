package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.WatchCoverage
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
