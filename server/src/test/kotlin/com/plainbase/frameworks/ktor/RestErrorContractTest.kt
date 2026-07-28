package com.plainbase.frameworks.ktor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.net.Socket

/**
 * The hostile-input edges of the §A4 error contract: malformed percent-escapes (rejected by Ktor's
 * ROUTING decode before any handler — mapped in `StatusPages`, never a 500), unknown `/api/...`
 * paths (404 in the envelope, never the SPA shell), and bare `{path...}` mount points (a clean 400
 * naming the expected form, not an echo of the request URI).
 */
class RestErrorContractTest : FunSpec({

    suspend fun HttpResponse.errorBody(): Pair<String, String> {
        contentType()?.withoutParameters() shouldBe ContentType.Application.Json
        val error = Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject
        return error.getValue("code").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    test("a malformed percent-escape answers 400 invalid_path in the envelope — never a 500") {
        // The ktor CLIENT refuses to even build a URL containing `%GG`, so this runs against a
        // real CIO server (the production engine) over a raw socket — exactly what a hostile or
        // buggy client delivers on the wire.
        RestHarness(Fixtures.demoDocs).use { harness ->
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { plainbaseModule(harness.services) }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().first().port
                fun rawGet(target: String): String = Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                }

                fun expectInvalidPath(target: String) {
                    val response = rawGet(target)
                    response.lineSequence().first() shouldContain " 400 "
                    response shouldContain "\"code\":\"invalid_path\""
                    response shouldNotContain "internal_error" // the old failure mode: a 500 from the catch-all
                }

                expectInvalidPath("/assets/%GG")
                // by-path with a bare malformed escape (loop-minor A2: previously untested).
                expectInvalidPath("/api/v1/pages/by-path/%")
                // Under /docs/ the shell-serving handler never sees the request either: routing
                // percent-decodes path parameters BEFORE any handler runs, so a malformed escape
                // is a 400 here too — pinned deliberately (200 + shell is unreachable).
                expectInvalidPath("/docs/%GG")
            } finally {
                server.stop()
            }
        }
    }

    test("unknown /api/* paths answer 404 not_found in the envelope — never 200 + the SPA shell") {
        restTest(Fixtures.demoDocs) {
            val typo = client.get("/api/v1/page/a3bb189e-8bf9-4888-9912-ace4e6543002") // pages misspelled
            typo.status shouldBe HttpStatusCode.NotFound
            typo.errorBody().first shouldBe "not_found"

            val trailingSlash = client.get("/api/v1/pages/a3bb189e-8bf9-4888-9912-ace4e6543002/")
            trailingSlash.status shouldBe HttpStatusCode.NotFound
            trailingSlash.errorBody().first shouldBe "not_found"

            val misspelledSub = client.get("/api/v1/pages/a3bb189e-8bf9-4888-9912-ace4e6543002/htlm")
            misspelledSub.status shouldBe HttpStatusCode.NotFound
            misspelledSub.errorBody().first shouldBe "not_found"

            // The bare handle covers every method, so non-GETs get the same honest 404.
            client.head("/api/v1/page/anything").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("a CANCELLED call still answers the frozen envelope, never Ktor's stack-trace page") {
        // The real shape is an SSE client (the in-binary MCP transport) hanging up, which cancels the
        // serving coroutine and reaches the `exception<Throwable>` catch-all. A route that throws is
        // the honest stand-in: the catch-all is the code under test, not CIO.
        //
        // This pins the TRAP: treating cancellation as "control flow, so answer NOTHING" looks right
        // and is worse, because a StatusPages handler that writes no response hands the call to Ktor's
        // own error page, which renders the exception and a full STACK TRACE onto the wire. That
        // inverts §A4 (details go to the log, never the wire) while looking like a tidy-up. The log
        // SEVERITY is a separate contract and has its own row below.
        RestHarness(Fixtures.demoDocs).use { harness ->
            testApplication {
                application {
                    plainbaseModule(harness.services)
                    routing { get("/__cancelled-probe") { throw CancellationException("client disconnected") } }
                }
                val response = client.get("/__cancelled-probe")
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = response.bodyAsText()
                body shouldContain "\"code\":\"internal_error\""
                body shouldNotContain "CancellationException" // the leak: Ktor's dev page renders the cause
                body shouldNotContain "Stack Trace"
            }
        }
    }

    test("a cancelled call logs NO operator ERROR, while a genuine failure still does") {
        // The severity half of the cancellation contract. A client hanging up is the expected end of an
        // SSE session, and agents connect and disconnect constantly, so logging it at ERROR buries real
        // failures. `plainbase spike` is where that was visible (`ERROR unhandled error serving
        // /api/v1/mcp` over a PASSING check), but the spike is not a gate, so this pins it.
        //
        // The `/__boom-probe` half is the ANTI-VACUITY check and is not decoration: asserting only the
        // ABSENCE of an ERROR would pass just as happily against a detached appender, a renamed logger,
        // or a message that no longer matches. One arm proves capture works; the other proves the demotion.
        RestHarness(Fixtures.demoDocs).use { harness ->
            val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                testApplication {
                    application {
                        plainbaseModule(harness.services)
                        routing {
                            get("/__cancelled-probe2") { throw CancellationException("client disconnected") }
                            get("/__boom-probe") { throw IllegalStateException("a genuine fault") }
                        }
                    }
                    client.get("/__cancelled-probe2")
                    fun errorsMentioning(uri: String) = appender.list
                        .filter { it.level == Level.ERROR && it.formattedMessage.contains(uri) }

                    errorsMentioning("/__cancelled-probe2").shouldBeEmpty()
                    client.get("/__boom-probe")
                    errorsMentioning("/__boom-probe").shouldNotBeEmpty()
                }
            } finally {
                root.detachAppender(appender)
            }
        }
    }

    test("a bare {path...} mount point answers a clean 400 naming the expected form, not the URI") {
        restTest(Fixtures.demoDocs) {
            suspend fun expectCleanInvalidPath(requested: String, expectedMessage: String) {
                val response = client.get(requested)
                response.status shouldBe HttpStatusCode.BadRequest
                val (code, message) = response.errorBody()
                code shouldBe "invalid_path"
                // The exact message pins the fix: the old bug echoed the mount point as a quoted
                // candidate path ("Not a valid asset path: '/assets'").
                message shouldBe expectedMessage
                message shouldNotContain "'$requested'"
            }

            expectCleanInvalidPath("/assets", "Expected an asset path: /assets/{path}")
            expectCleanInvalidPath("/assets/", "Expected an asset path: /assets/{path}")
            expectCleanInvalidPath("/browse", "Expected a content file path: /browse/{file-path}")
            expectCleanInvalidPath("/api/v1/pages/by-path", "Expected a page path: /api/v1/pages/by-path/{path}")
        }
    }
})
