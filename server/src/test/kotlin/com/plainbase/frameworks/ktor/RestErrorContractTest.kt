package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
