package com.plainbase.frameworks.cli

import com.plainbase.domain.root.RootedPath
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

/** Deterministic command results/errors plus the checked pre-write command-event channel. */
interface CommandOutput {
    fun result(text: String = "", newline: Boolean = true)

    fun error(text: String)

    fun intent(event: WriteIntent)
}

/** The deliberately allowlisted adoption event. It cannot carry arbitrary or secret-bearing fields. */
data class WriteIntent(val pageId: String, val page: RootedPath, val qualifyRoot: Boolean)

/** A synchronous event publisher: returning means the event bytes reached the configured stream successfully. */
fun interface CommandEventSink {
    fun publish(event: WriteIntent)
}

class StreamCommandOutput(
    private val stdout: PrintStream,
    private val stderr: PrintStream,
    private val eventSink: CommandEventSink,
) : CommandOutput {
    override fun result(text: String, newline: Boolean) {
        if (newline) stdout.println(text) else stdout.print(text)
        stdout.flush()
        stdout.requireHealthy("command result")
    }

    override fun error(text: String) {
        stderr.println(text)
        stderr.flush()
        stderr.requireHealthy("command error")
    }

    override fun intent(event: WriteIntent) = eventSink.publish(event)
}

/** The only production boundary that captures the process-global streams. */
fun systemCommandOutput(): CommandOutput {
    val stdout = System.out
    val stderr = System.err
    val sink = when (System.getProperty(COMMAND_EVENTS_PROPERTY, "plain")) {
        "plain" -> PlainCommandEventSink(stdout)
        "json" -> JsonCommandEventSink(stderr)
        else -> throw IllegalArgumentException("$COMMAND_EVENTS_PROPERTY must be plain or json")
    }
    return StreamCommandOutput(stdout, stderr, sink)
}

class PlainCommandEventSink(private val stream: PrintStream) : CommandEventSink {
    override fun publish(event: WriteIntent) {
        val target = if (event.qualifyRoot) "${event.page.root}:${event.page.path}" else event.page.path
        stream.println("intent: write id ${event.pageId} -> $target")
        stream.flush()
        stream.requireHealthy("command event")
    }
}

class JsonCommandEventSink(private val stream: PrintStream) : CommandEventSink {
    override fun publish(event: WriteIntent) {
        val value = buildJsonObject {
            put("type", "write_intent")
            put("pageId", event.pageId)
            put("root", event.page.root.value)
            put("path", event.page.path.value)
        }
        stream.println(Json.encodeToString(value))
        stream.flush()
        stream.requireHealthy("command event")
    }
}

private fun PrintStream.requireHealthy(channel: String) {
    check(!checkError()) { "failed to publish $channel" }
}

private const val COMMAND_EVENTS_PROPERTY = "plainbase.commandEvents"
