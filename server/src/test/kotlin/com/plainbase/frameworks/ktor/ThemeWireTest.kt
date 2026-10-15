package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.filesystem.Fixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import io.ktor.server.cio.CIO as ServerCIO

private val themeWireLogger = KotlinLogging.logger {}

private data class ThemeWireHead(val statusLine: String, val headers: Map<String, List<String>>, val raw: String) {
    fun header(name: String): List<String>? = headers[name.lowercase()]
}

private sealed interface ThemeWireOutcome {
    data class NextResponse(val leftover: Int, val statusLine: String) : ThemeWireOutcome
    data class Unparseable(val raw: String, val reason: String) : ThemeWireOutcome
}

private data class ThemeWireObservation(val head: ThemeWireHead, val outcome: ThemeWireOutcome, val raw: String)

private fun InputStream.themeHeaderBlock(): String {
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

private suspend fun observeThemeWire(port: Int, method: String, path: String): ThemeWireObservation = withContext(Dispatchers.IO) {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        val output = socket.getOutputStream()
        output.write("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        val input = socket.getInputStream()
        val headerBlock = input.themeHeaderBlock()
        val lines = headerBlock.removeSuffix("\r\n\r\n").split("\r\n")
        val head = ThemeWireHead(
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
            ThemeWireOutcome.NextResponse(status.range.first, status.value.removeSuffix("\r\n"))
        } else {
            val reason = when {
                !ended -> "socket timed out before connection closed"
                method == "HEAD" -> "connection closed after HEAD before a second response"
                else -> "connection closed before a second response"
            }
            ThemeWireOutcome.Unparseable(raw, reason)
        }
        ThemeWireObservation(head, outcome, raw).also { observation ->
            themeWireLogger.info { "$method $path: $observation" }
        }
    }
}

private suspend fun themeWireServer(block: suspend (Int) -> Unit) {
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

class ThemeWireTest : FunSpec({
    test("GET detector observes the complete namespace body before the next response") {
        themeWireServer { port ->
            val observed = observeThemeWire(port, "GET", "/_plainbase/nope.css")
            withClue(observed) {
                val next = observed.outcome.shouldBeInstanceOf<ThemeWireOutcome.NextResponse>()
                assertSoftly {
                    observed.head.statusLine shouldBe "HTTP/1.1 404 Not Found"
                    next.statusLine shouldBe "HTTP/1.1 200 OK"
                    (next.leftover > 0) shouldBe true
                    observed.head.header("Content-Length") shouldBe listOf(next.leftover.toString())
                }
            }
        }
    }
    listOf(
        "/_plainbase",
        "/_plainbase/",
        "/_plainbase/nope.css",
        "/_plainbase/plainbase-theme-light.css",
        "/_plainbase/plainbase-theme-dark.css",
    ).forEach { path ->
        test("HEAD $path matches GET metadata without body bytes before the next response") {
            themeWireServer { port ->
                val get = observeThemeWire(port, "GET", path)
                val head = observeThemeWire(port, "HEAD", path)
                withClue("GET=$get; HEAD=$head") {
                    val getNext = get.outcome.shouldBeInstanceOf<ThemeWireOutcome.NextResponse>()
                    val headNext = head.outcome.shouldBeInstanceOf<ThemeWireOutcome.NextResponse>()
                    assertSoftly {
                        get.head.statusLine shouldBe "HTTP/1.1 404 Not Found"
                        head.head.statusLine shouldBe get.head.statusLine
                        get.head.header("Content-Type") shouldBe listOf("application/json")
                        get.head.header("Cache-Control") shouldBe listOf("no-store")
                        get.head.header("X-Content-Type-Options") shouldBe listOf("nosniff")
                        get.head.header("Content-Length") shouldBe listOf(getNext.leftover.toString())
                        (getNext.leftover > 0) shouldBe true
                        listOf("Content-Type", "Cache-Control", "X-Content-Type-Options", "Content-Length").forEach { name ->
                            withClue(name) { head.head.header(name) shouldBe get.head.header(name) }
                        }
                        getNext.statusLine shouldBe "HTTP/1.1 200 OK"
                        headNext.statusLine shouldBe "HTTP/1.1 200 OK"
                        withClue("HEAD leftover") { headNext.leftover shouldBe 0 }
                    }
                }
            }
        }
    }

    listOf("/_plainbase/theme-light.css", "/_plainbase/theme-dark.css", "/_plainbase/custom.css").forEach { path ->
        test("HEAD $path refuses the method without body bytes before the next response") {
            themeWireServer { port ->
                val observed = observeThemeWire(port, "HEAD", path)
                withClue(observed) {
                    val next = observed.outcome.shouldBeInstanceOf<ThemeWireOutcome.NextResponse>()
                    assertSoftly {
                        observed.head.statusLine shouldBe "HTTP/1.1 405 Method Not Allowed"
                        observed.head.header("Allow") shouldBe listOf("GET")
                        observed.head.header("Cache-Control") shouldBe listOf("no-store")
                        observed.head.header("X-Content-Type-Options") shouldBe listOf("nosniff")
                        observed.head.header("Content-Length") shouldBe listOf("0")
                        next.statusLine shouldBe "HTTP/1.1 200 OK"
                        next.leftover shouldBe 0
                    }
                }
            }
        }
    }
})
