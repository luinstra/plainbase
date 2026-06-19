package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Socket

/**
 * PB-SEARCH-1 golden shapes (§A2/§A5/§A6 tier 1, FROZEN FOREVER): the sentinel single-hit query,
 * the zero-hit query, and the `invalid_query` error envelopes, as parsed-JSON-tree snapshots under
 * `/golden/rest/` against the fixture corpus. Phase-1 conventions throughout: stable UUID literals
 * seeded into the id_map, `content_hash` recomputed from the fixture bytes at test time, and —
 * the §A6 addition — `score` values normalized to the `{{score}}` placeholder AFTER type-checking
 * them finite, because score VALUES are deliberately NOT frozen (§A4) while everything else is.
 *
 * Sentinel-term discipline (§A6): the query term is chosen to occur in exactly ONE fixture section,
 * and the test PROVES that before comparing — if a future fixture edit makes "blameless" ambiguous,
 * this fails as a broken precondition, never as a mysterious golden drift. No CJK-sensitive terms
 * (tokenization of CJK is engine/tokenizer-specific — §A6 golden guidance).
 */
class SearchGoldenTest : FunSpec({

    // The §A6 sentinel: occurs once in the whole corpus (infra/incident-runbook.md, the
    // "Postmortems" section) — proven below, never assumed. Its section body is shorter than the
    // engine's snippet window, so the snippet has no fragment-selection freedom to drift within.
    val sentinelTerm = "blameless"
    val runbookId = "0197c4f0-3d2e-7a18-9b6c-5e4f3a2b1c0d"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("infra/incident-runbook.md"), PageId.require(runbookId), materialized = false)
    }
    val runbookHash = RestGolden.contentHashOf(Fixtures.demoDocs.resolve("infra/incident-runbook.md"))

    fun goldenTest(block: suspend ApplicationTestBuilder.(RestHarness) -> Unit) = restTest(Fixtures.demoDocs, seed, block = block)

    test("GET /api/v1/search matches the frozen sentinel single-hit golden (score-normalized)") {
        goldenTest { harness ->
            // Self-validating precondition: the sentinel occurs in exactly one section document of
            // the corpus, across EVERY searchable field (substring check, so a prefix-star match
            // against some other token cannot sneak past it either).
            val splitter = SectionSplitter()
            val matching = harness.builder.current.pages
                .flatMap { splitter.split(it).sections }
                .count { doc ->
                    val haystack = (listOf(doc.title, doc.heading, doc.body, doc.owner) + doc.tags + doc.aliases)
                        .filterNotNull().joinToString("\n").lowercase()
                    sentinelTerm in haystack
                }
            matching shouldBe 1

            val response = client.get("/api/v1/search?q=$sentinelTerm")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            normalizeScores(body) shouldBe RestGolden.load("search-sentinel-hit.json", mapOf("content_hash" to runbookHash))
            // Belt-and-suspenders beside the golden: commit is PRESENT and null (§A2 present-null).
            body.getValue("hits").jsonArray.single().jsonObject
                .getValue("citation").jsonObject
                .getValue("commit") shouldBe JsonNull
        }
    }

    test("GET /api/v1/search matches the frozen zero-hit golden (empty result is success, §A1)") {
        goldenTest {
            val response = client.get("/api/v1/search?q=xyzzyplugh")
            response.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(response.bodyAsText()) shouldBe RestGolden.load("search-zero-hit.json")
        }
    }

    test("invalid_query error envelopes match the frozen goldens (§A1/§A5; messages name the rule)") {
        goldenTest {
            suspend fun envelope(url: String): JsonElement {
                val response = client.get(url)
                response.status shouldBe HttpStatusCode.BadRequest
                return Json.parseToJsonElement(response.bodyAsText())
            }
            // Missing q and whitespace-only q violate the same rule — one frozen envelope.
            envelope("/api/v1/search") shouldBe RestGolden.load("error-invalid-query-blank-q.json")
            envelope("/api/v1/search?q=%20%20") shouldBe RestGolden.load("error-invalid-query-blank-q.json")
            envelope("/api/v1/search?q=${"a".repeat(513)}") shouldBe RestGolden.load("error-invalid-query-overlong-q.json")
            envelope("/api/v1/search?q=ok&limit=0") shouldBe RestGolden.load("error-invalid-query-limit.json")
            envelope("/api/v1/search?q=ok&limit=101") shouldBe RestGolden.load("error-invalid-query-limit.json")
            envelope("/api/v1/search?q=ok&limit=x") shouldBe RestGolden.load("error-invalid-query-limit.json")
            envelope("/api/v1/search?q=ok&offset=-1") shouldBe RestGolden.load("error-invalid-query-offset.json")
        }
    }

    test("the malformed-percent-encoding envelope matches the frozen golden (§A1 family; raw socket — never a 500)") {
        // The ktor test client refuses to build a URL containing a bare `%`, so this envelope is
        // pinned against the production CIO engine over a raw socket (the RestErrorContractTest
        // pattern) — the exact wire form that used to fall through to the 500 catch-all.
        RestHarness(Fixtures.demoDocs, seed).use { harness ->
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { plainbaseModule(harness.services) }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().first().port
                val response = Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream()
                        .write("GET /api/v1/search?q=% HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                }
                response.lineSequence().first() shouldBe "HTTP/1.1 400 Bad Request"
                val body = response.substringAfter("\r\n\r\n")
                Json.parseToJsonElement(body) shouldBe RestGolden.load("error-invalid-query-encoding.json")
            } finally {
                server.stop()
            }
        }
    }

    test("nullable hit fields serialize present-and-NULL on the wire (§A2: url, heading_id, heading_text, commit)") {
        // A same-parent slug collision makes the loser's url null, and a heading-less page makes
        // its only hit page-level (null heading fields) — one corpus exercises every §A2 nullable.
        withTempTree(seed = { root ->
            writePage(root, "a/zebra.md", "---\nslug: clash\n---\nquagga herd basics.\n")
            writePage(root, "a/zulu.md", "---\nslug: clash\n---\nquagga herd basics.\n")
        }) { root ->
            restTest(root) {
                val response = client.get("/api/v1/search?q=quagga")
                response.status shouldBe HttpStatusCode.OK
                val hits = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("hits").jsonArray.map { it.jsonObject }

                hits.map { it.getValue("url") } shouldContainExactlyInAnyOrder listOf(JsonPrimitive("/docs/a/clash"), JsonNull)
                hits.forEach { hit ->
                    hit.getValue("heading_id") shouldBe JsonNull
                    hit.getValue("heading_text") shouldBe JsonNull
                    hit.getValue("heading_path") shouldBe JsonArray(emptyList())
                    hit.getValue("citation").jsonObject.getValue("commit") shouldBe JsonNull
                    hit.getValue("citation").jsonObject.getValue("heading_id") shouldBe JsonNull
                }
            }
        }
    }
})

/**
 * The §A6 score normalization: every hit's `score` is asserted to be a FINITE JSON number (the
 * type check half), then replaced by the `{{score}}` placeholder the golden carries (the
 * not-frozen-value half) — exactly the Phase-1 `{{html}}` move, applied per hit.
 */
private fun normalizeScores(body: JsonObject): JsonObject {
    val hits = body.getValue("hits").jsonArray.map { hit ->
        val obj = hit.jsonObject
        val primitive = obj.getValue("score").jsonPrimitive
        check(!primitive.isString) { "score is a JSON STRING, not a number: $primitive" } // doubleOrNull alone would accept "4.27"
        val score = checkNotNull(primitive.doubleOrNull) { "score is not a JSON number: $primitive" }
        check(score.isFinite()) { "score is not finite: $score" }
        JsonObject(obj + ("score" to JsonPrimitive("{{score}}")))
    }
    return JsonObject(body + ("hits" to JsonArray(hits)))
}
