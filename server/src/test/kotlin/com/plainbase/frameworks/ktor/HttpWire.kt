package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.filesystem.Fixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import io.ktor.server.cio.CIO as ServerCIO

private val httpWireLogger = KotlinLogging.logger {}

internal data class WireHead(val statusLine: String, val headers: Map<String, List<String>>, val raw: String) {
    fun header(name: String): List<String>? = headers[name.lowercase()]
}

/** What follows the first response on the same keep-alive socket: body bytes before the next status line betray a framing bug. */
internal sealed interface WireOutcome {
    data class NextResponse(val leftover: Int, val statusLine: String) : WireOutcome
    data class Unparseable(val raw: String, val reason: String) : WireOutcome
}

internal data class WireObservation(val head: WireHead, val outcome: WireOutcome, val raw: String)

private fun InputStream.headerBlock(): String {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() < 16_384) {
        val next = read()
        if (next == -1) break
        bytes.write(next)
        val raw = bytes.toString(Charsets.US_ASCII)
        if (raw.endsWith("\r\n\r\n")) return raw
    }
    error("No complete HTTP header block: ${bytes.toString(Charsets.US_ASCII)}")
}

/** Sends one request, reads only its header block, then pipelines `GET /healthz` and records everything before that response. */
internal suspend fun observeWire(port: Int, method: String, path: String): WireObservation = withContext(Dispatchers.IO) {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        val output = socket.getOutputStream()
        output.write("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        val input = socket.getInputStream()
        val headerBlock = input.headerBlock()
        val lines = headerBlock.removeSuffix("\r\n\r\n").split("\r\n")
        val head = WireHead(
            lines.first(),
            lines.drop(1).groupBy({ it.substringBefore(':').lowercase() }, { it.substringAfter(':').trim() }),
            headerBlock,
        )
        output.write("GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        val ended = try {
            var count = input.read(buffer)
            while (count != -1) {
                bytes.write(buffer, 0, count)
                count = input.read(buffer)
            }
            true
        } catch (_: SocketTimeoutException) {
            false
        }
        val raw = bytes.toString(Charsets.US_ASCII)
        val status = Regex("HTTP/1\\.1 [0-9]{3} [^\\r\\n]*\\r\\n").find(raw)
        val outcome = if (ended && status != null) {
            WireOutcome.NextResponse(status.range.first, status.value.removeSuffix("\r\n"))
        } else {
            val reason = when {
                !ended -> "socket timed out before connection closed"
                method == "HEAD" -> "connection closed after HEAD before a second response"
                else -> "connection closed before a second response"
            }
            WireOutcome.Unparseable(raw, reason)
        }
        WireObservation(head, outcome, raw).also { observation ->
            httpWireLogger.info { "$method $path: $observation" }
        }
    }
}

/** A real CIO server over the production module, so the bytes on the socket are what a client sees. */
internal suspend fun wireServer(block: suspend (Int) -> Unit) {
    RestHarness(Fixtures.demoDocs, authMode = AuthMode.OFF).use { harness ->
        val ctx = harness.services
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) { plainbaseModule(ctx) }
        try {
            server.start(wait = false)
            block(server.engine.resolvedConnectors().first().port)
        } finally {
            server.stopSuspend()
        }
    }
}
