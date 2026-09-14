package com.plainbase.frameworks.filesystem

import org.junit.jupiter.api.Tag
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Lean native coverage for the real-channel Error cleanup boundary. */
@Tag("native")
class DataDirLockNativeTest {

    @Test
    fun `real failed acquisition closes its channel while preserving Error identity`() {
        val dataDir = Files.createTempDirectory("pb-native-lock-error")
        var channel: FileChannel? = null
        var testFailure: Throwable? = null
        try {
            val failure = AssertionError("native tryLock failure")
            val actual = try {
                DataDirLock.tryAcquireWith(
                    dataDir = dataDir,
                    tryLock = { opened ->
                        channel = opened
                        throw failure
                    },
                )
                error("expected the injected Error")
            } catch (caught: AssertionError) {
                caught
            }
            assertSame(failure, actual)
            assertFalse(requireNotNull(channel).isOpen)
        } catch (caught: Throwable) {
            testFailure = caught
            throw caught
        } finally {
            val closed = closeRetainedChannel(channel, testFailure)
            if (closed) dataDir.toFile().deleteRecursively()
        }
    }
}

private fun closeRetainedChannel(channel: FileChannel?, primary: Throwable?): Boolean {
    if (channel?.isOpen != true) return true
    try {
        channel.close()
        check(!channel.isOpen) { "retained DATA_DIR lock channel remained open" }
        return true
    } catch (cleanup: Throwable) {
        if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        return !channel.isOpen
    }
}
