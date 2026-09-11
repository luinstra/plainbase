package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootBinding
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Native divergence twin for the real OBJECT poll callback/worker close barrier. */
@Tag("native")
class ObjectPollerLifecycleNativeTest {

    @Test
    fun `native object close joins a held real poll callback`() {
        val dataDir = Files.createTempDirectory("pb-native-object-close")
        val mirrorRoot = Files.createDirectories(dataDir.resolve("mirror"))
        val stateFile = dataDir.resolve("mirror-state")
        val client = NativeObjectClient()
        val mirror = LocalContentStore(root = mirrorRoot, ignoreRules = IgnoreRules())
        val store = ObjectContentStore(
            client = client,
            mirror = mirror,
            state = MirrorState(stateFile),
            binding = RootBinding("https://native.example|docs|"),
            keyPrefix = "",
            pollSeconds = 1,
            dirtyPaths = { emptySet() },
            mirrorRoot = mirrorRoot,
            ignoreRules = IgnoreRules(),
        )
        val target = TreePath.require("held.md")
        client.seed(target.value, "# held\n".toByteArray())
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
        var watcher: AutoCloseable? = null
        var poller: Thread? = null
        var closeThread: Thread? = null
        var survivor = false
        try {
            val currentWatcher = store.watch(
                onChange = { path ->
                    if (path == target) {
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
                onFailure = {},
                onCoverage = {},
                onBreak = {},
            )
            watcher = currentWatcher
            poller = requireNotNull(store.pollerForTest())
            assertTrue(callbackEntered.await(10, TimeUnit.SECONDS), "native object callback never arrived")

            val currentCloser = thread(name = "plainbase-native-object-close") {
                try {
                    currentWatcher.close()
                } catch (failure: Throwable) {
                    closeFailure.compareAndSet(null, failure)
                }
            }
            closeThread = currentCloser
            assertFalse(joinOwned(currentCloser, 500), "object close returned during the short observation")
            assertTrue(currentCloser.isAlive, "object close returned while the callback was held")
            assertTrue(requireNotNull(poller).isAlive, "object poller died before the callback was released")
            assertFalse(callbackDone.get())

            callbackRelease.countDown()
            assertTrue(joinOwned(currentCloser, 10_000), "object close thread did not terminate")
            assertTrue(joinOwned(requireNotNull(poller), 10_000), "object poller did not terminate")
            assertFalse(currentCloser.isAlive)
            assertFalse(requireNotNull(poller).isAlive)
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
                thread(name = "plainbase-native-object-cleanup") {
                    try {
                        currentWatcher.close()
                    } catch (failure: Throwable) {
                        closeFailure.compareAndSet(null, failure)
                    }
                }
            }
            closeThread = cleanupCloser
            if (cleanupCloser != null && !joinOwned(cleanupCloser, 10_000)) survivor = true
            if (cleanupCloser != null && !cleanupCloser.isAlive && poller?.isAlive == true) {
                val retryCloser = thread(name = "plainbase-native-object-retry") {
                    try {
                        requireNotNull(watcher).close()
                    } catch (failure: Throwable) {
                        closeFailure.compareAndSet(null, failure)
                    }
                }
                if (!joinOwned(retryCloser, 10_000)) survivor = true
            }
            poller?.let { if (!joinOwned(it, 10_000)) survivor = true }
            if (poller?.isAlive == true) survivor = true
            addFailure(closeFailure.get())
            if (survivor) addFailure(IllegalStateException("native object fixture retained a surviving closer or poller"))
            if (!survivor) {
                runCatching { store.close() }.onFailure(::addFailure)
                runCatching { client.close() }.onFailure(::addFailure)
                runCatching { deleteRecursively(dataDir) }.onFailure(::addFailure)
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        primary?.let { throw it }
    }
}

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
