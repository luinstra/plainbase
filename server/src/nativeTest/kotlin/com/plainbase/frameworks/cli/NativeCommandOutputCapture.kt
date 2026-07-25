package com.plainbase.frameworks.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Native-safe injected capture: JDK/Kotlin primitives only, no reflection or mocking. */
object NativeCommandOutputCapture {
    private data class Streams(val stdout: PrintStream, val stderr: PrintStream)

    private val streams = ThreadLocal.withInitial { quietStreams() }

    val current: CommandOutput
        get() = streams.get().let { StreamCommandOutput(it.stdout, it.stderr, PlainCommandEventSink(it.stdout)) }

    fun captureStdout(block: () -> Unit): String = capture(stdout = true, block)

    fun captureStderr(block: () -> Unit): String = capture(stdout = false, block)

    private fun capture(stdout: Boolean, block: () -> Unit): String {
        val previous = streams.get()
        val buffer = ByteArrayOutputStream()
        val stream = PrintStream(buffer, true, Charsets.UTF_8)
        streams.set(if (stdout) previous.copy(stdout = stream) else previous.copy(stderr = stream))
        return try {
            block()
            buffer.toString(Charsets.UTF_8)
        } finally {
            streams.set(previous)
        }
    }

    private fun quietStreams() = Streams(
        PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
        PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
    )
}
