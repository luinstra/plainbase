package com.plainbase.frameworks.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** One shared, terminal close attempt for a resource that Koin and the runtime path both may reach. */
internal class CloseOnce(
    private val name: String,
    private val closeAction: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val completed = CountDownLatch(1)

    @Suppress("TooGenericExceptionCaught")
    fun close() {
        if (started.compareAndSet(false, true)) {
            try {
                closeAction()
            } catch (failure: Throwable) {
                logger.warn(failure) { "closing $name failed" }
            } finally {
                completed.countDown()
            }
            return
        }

        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
