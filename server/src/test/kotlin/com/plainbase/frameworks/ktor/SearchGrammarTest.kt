package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Socket

/**
 * The frozen §A1 parameter grammar, pinned at the route (the violation ENVELOPES are pinned by the
 * goldens in [SearchGoldenTest]; this suite pins the accept/reject boundary itself and the decoding
 * rules): trim-then-validate `q` with the 512-UTF-16-unit limit exactly at the boundary, the
 * `limit`/`offset` ranges inclusive of their endpoints, `+`-decodes-to-space (a query parameter,
 * not a path segment — PB-LINK-1 does not apply), and unknown parameters ignored.
 */
class SearchGrammarTest : FunSpec({

    suspend fun HttpResponse.jsonBody(): JsonObject {
        status shouldBe HttpStatusCode.OK
        return Json.parseToJsonElement(bodyAsText()).jsonObject
    }

    test("q boundary: 512 UTF-16 code units is accepted, 513 is rejected (§A1)") {
        restTest(Fixtures.demoDocs) {
            val ok = client.get("/api/v1/search?q=${"a".repeat(512)}").jsonBody()
            ok.getValue("query").jsonPrimitive.content.length shouldBe 512

            client.get("/api/v1/search?q=${"a".repeat(513)}").status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("q is trimmed before validation and echoed trimmed (§A2 query field)") {
        restTest(Fixtures.demoDocs) {
            val body = client.get("/api/v1/search?q=%20deploy%20").jsonBody()
            body.getValue("query").jsonPrimitive.content shouldBe "deploy"
        }
    }

    test("a + in q decodes to a space — form encoding, adjudicated and frozen (§A1)") {
        restTest(Fixtures.demoDocs) {
            val body = client.get("/api/v1/search?q=incident+runbook").jsonBody()
            body.getValue("query").jsonPrimitive.content shouldBe "incident runbook"
        }
    }

    test("limit range is 1–100 inclusive with default 20; offset is 0–10000 inclusive with default 0 (§A1)") {
        restTest(Fixtures.demoDocs) {
            val defaults = client.get("/api/v1/search?q=deploy").jsonBody()
            defaults.getValue("limit").jsonPrimitive.int shouldBe 20
            defaults.getValue("offset").jsonPrimitive.int shouldBe 0

            val floor = client.get("/api/v1/search?q=deploy&limit=1&offset=0").jsonBody()
            floor.getValue("limit").jsonPrimitive.int shouldBe 1
            floor.getValue("offset").jsonPrimitive.int shouldBe 0
        }
    }

    test("limit/offset ceilings are accepted exactly (limit=100, offset=10000)") {
        restTest(Fixtures.demoDocs) {
            val body = client.get("/api/v1/search?q=deploy&limit=100&offset=10000").jsonBody()
            body.getValue("limit").jsonPrimitive.int shouldBe 100
            body.getValue("offset").jsonPrimitive.int shouldBe 10000
        }
    }

    test("a repeated q takes the FIRST value — pinned de facto behavior") {
        restTest(Fixtures.demoDocs) {
            val body = client.get("/api/v1/search?q=alpha&q=beta").jsonBody()
            body.getValue("query").jsonPrimitive.content shouldBe "alpha"
        }
    }

    test("lenient int forms — pinned de facto behavior of the strict-integer rule") {
        restTest(Fixtures.demoDocs) {
            // Kotlin's toIntOrNull accepts a leading zero and an explicit sign…
            client.get("/api/v1/search?q=deploy&limit=020").jsonBody().getValue("limit").jsonPrimitive.int shouldBe 20
            client.get("/api/v1/search?q=deploy&limit=%2B20").jsonBody().getValue("limit").jsonPrimitive.int shouldBe 20
            // …but a RAW + in the query string is form-encoding for a SPACE (" 20"), which is not
            // an integer — 400, the same + rule q follows.
            client.get("/api/v1/search?q=deploy&limit=+20").status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("the 512-unit limit measures the TRIMMED q — trailing whitespace beyond 512 raw units is fine") {
        restTest(Fixtures.demoDocs) {
            // 512 units + 3 encoded spaces = 515 raw units, trimming to exactly 512: accepted.
            val body = client.get("/api/v1/search?q=${"a".repeat(512)}%20%20%20").jsonBody()
            body.getValue("query").jsonPrimitive.content.length shouldBe 512
        }
    }

    test("unknown parameters are ignored — additive evolution (§A1)") {
        restTest(Fixtures.demoDocs) {
            val body = client.get("/api/v1/search?q=deploy&syntax=lucene&foo=bar").jsonBody()
            body.getValue("query").jsonPrimitive.content shouldBe "deploy"
        }
    }

    test("operator characters in q are plain text, never an engine syntax error (§A1 forever rule)") {
        restTest(Fixtures.demoDocs) {
            // Adversarial corpus: FTS5 operator grammar, lone quotes/parens, column-filter colons,
            // a lone %, control chars, non-BMP. Always 200 (they are valid PLAIN-TEXT queries),
            // never a 5xx, never an engine error.
            val adversarial = listOf(
                """"deploy"""",
                "(deploy OR rollback)",
                "deploy AND NOT rollback",
                "title:deploy",
                "deploy*",
                "deploy^2",
                "NEAR(deploy rollback)",
                "%",
                "${Char(1)}${Char(2)}deploy", // embedded C0 control characters (constructed — never raw in source)
                "🚀 rocket",
            )
            adversarial.forEach { q ->
                val response = client.get("/api/v1/search") { url.parameters.append("q", q) }
                response.status shouldBe HttpStatusCode.OK
            }
        }
    }

    test("raw malformed percent-escapes in the query string answer 400 invalid_query — never a 500 (§A6 lone-% rule)") {
        // The ktor CLIENT refuses to build such URLs, so this runs against the production CIO
        // engine over a raw socket (the RestErrorContractTest pattern) — exactly what a hostile or
        // sloppy client delivers. Without the route's defensive decode, Ktor's LAZY queryParameters
        // decode throws URLDecodeException PAST the Phase-1 BadRequestException mapping (which only
        // covers the routing PATH decode) and the catch-all answers 500 (ktor#2559).
        RestHarness(Fixtures.demoDocs).use { harness ->
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { plainbaseModule(harness.services) }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().first().port
                fun rawGet(target: String): String = Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                }

                fun expectInvalidQuery(target: String) {
                    val response = rawGet(target)
                    response.lineSequence().first() shouldContain " 400 "
                    response shouldContain "\"code\":\"invalid_query\""
                    response shouldNotContain "internal_error" // the reproduced failure mode: a 500 from the catch-all
                }

                expectInvalidQuery("/api/v1/search?q=%")
                expectInvalidQuery("/api/v1/search?q=100%") // the realistic one: an unencoded percent sign
                expectInvalidQuery("/api/v1/search?q=%GG")
                // Same eager-decode path for the int params — they must not 500 either.
                expectInvalidQuery("/api/v1/search?q=ok&limit=%")
                expectInvalidQuery("/api/v1/search?q=ok&offset=%G1")
                // A malformed UNKNOWN parameter would be ignored by §A1, but Ktor decodes the
                // whole query string eagerly — undecodable as delivered is still a 400, never a 500.
                expectInvalidQuery("/api/v1/search?q=ok&junk=%")
                // The StatusPages net covers every route: a malformed query string on a non-search
                // endpoint must not 500 either.
                expectInvalidQuery("/api/v1/tree?x=%")
            } finally {
                server.stop()
            }
        }
    }
})
