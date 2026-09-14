package com.plainbase.frameworks.lifecycle

import com.plainbase.IdentitySafeFailureAccumulator
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Small test-only owner for a probe child and its output drain. */
internal class Stage0cOwnedChild(
    private val process: Process,
    private val parent: Stage0cParentDeadline,
    private val label: String,
) : AutoCloseable {
    private val output = ArrayBlockingQueue<String>(OUTPUT_CAPACITY)
    private val outputOverflowed = AtomicBoolean()
    private val outputFailure = AtomicReference<Throwable?>()
    private val outputDrain = thread(isDaemon = true, name = "$label-output") {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!output.offer(line)) outputOverflowed.set(true)
                }
            }
        } catch (failure: Throwable) {
            outputFailure.set(failure)
        }
    }

    fun awaitProtocol(): String {
        while (process.isAlive) {
            parent.check("$label process")
            process.waitFor(parent.remainingMillis(100).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }
        check(parent.join(outputDrain, "$label output", 2_000)) { "$label output drain survived its bound" }
        outputFailure.get()?.let { throw it }
        check(!outputOverflowed.get()) { "$label output exceeded $OUTPUT_CAPACITY lines" }
        check(process.exitValue() == 0) { "$label exited ${process.exitValue()}" }
        val lines = output.toList()
        val protocol = lines.filter { it in PROTOCOL }
        check(protocol.size == 1) {
            "$label protocol was not exact: $lines"
        }
        return protocol.single()
    }

    fun isComplete(): Boolean = !process.isAlive && !outputDrain.isAlive

    override fun close() {
        val failures = IdentitySafeFailureAccumulator()
        var interrupted = false

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: InterruptedException) {
                interrupted = true
                failures.add(failure)
            } catch (failure: Throwable) {
                failures.add(failure)
            }
        }

        if (process.isAlive) attempt { process.destroy() }
        if (process.isAlive) {
            attempt {
                process.waitFor(parent.remainingMillis(1_000).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            }
        }
        if (process.isAlive) attempt { process.destroyForcibly() }
        if (process.isAlive) {
            attempt {
                process.waitFor(parent.remainingMillis(1_000).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            }
        }
        if (process.isAlive) failures.add(IllegalStateException("$label process survived termination"))

        attempt {
            process.inputStream.close()
            process.errorStream.close()
        }
        val outputJoined = try {
            parent.join(outputDrain, "$label output cleanup", 1_000)
        } catch (failure: InterruptedException) {
            interrupted = true
            failures.add(failure)
            false
        }
        if (!outputJoined || outputDrain.isAlive) {
            failures.add(IllegalStateException("$label output drain survived cleanup"))
        }
        if (interrupted) Thread.currentThread().interrupt()
        failures.failure?.let { throw it }
    }

    private companion object {
        const val OUTPUT_CAPACITY = 16
        val PROTOCOL = setOf("HELD", "AVAILABLE")
    }
}
