package com.plainbase.frameworks.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CommandOutputFixture(eventSink: CommandEventSink = CommandEventSink {}) {
    private val stdoutBuffer = ByteArrayOutputStream()
    private val stderrBuffer = ByteArrayOutputStream()

    val output: CommandOutput = StreamCommandOutput(
        PrintStream(stdoutBuffer, true, Charsets.UTF_8),
        PrintStream(stderrBuffer, true, Charsets.UTF_8),
        eventSink,
    )

    val stdout: String get() = stdoutBuffer.toString(Charsets.UTF_8)
    val stderr: String get() = stderrBuffer.toString(Charsets.UTF_8)
}

/** Nested, thread-local capture for legacy command tests while keeping JVM-global streams untouched. */
object CommandOutputCapture {
    private data class Streams(
        val stdout: PrintStream,
        val stderr: PrintStream,
    )

    private val streams = ThreadLocal.withInitial {
        Streams(
            PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
            PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
        )
    }

    val current: CommandOutput
        get() = streams.get().let { StreamCommandOutput(it.stdout, it.stderr, PlainCommandEventSink(it.stdout)) }

    fun captureStdout(block: () -> Unit): String {
        val previous = streams.get()
        val buffer = ByteArrayOutputStream()
        streams.set(previous.copy(stdout = PrintStream(buffer, true, Charsets.UTF_8)))
        return try {
            block()
            buffer.toString(Charsets.UTF_8)
        } finally {
            streams.set(previous)
        }
    }

    fun captureStderr(block: () -> Unit): String {
        val previous = streams.get()
        val buffer = ByteArrayOutputStream()
        streams.set(previous.copy(stderr = PrintStream(buffer, true, Charsets.UTF_8)))
        return try {
            block()
            buffer.toString(Charsets.UTF_8)
        } finally {
            streams.set(previous)
        }
    }
}
