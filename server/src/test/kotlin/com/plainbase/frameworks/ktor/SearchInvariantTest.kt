package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The §A6 tier-3 invariants on the embedded engine, asserted at the route (the engine-agnostic
 * promises every ENABLED engine must keep, as opposed to the tier-1 exact-value goldens):
 * scores finite and non-increasing; highlight offsets well-formed per §A3 INCLUDING the
 * surrogate-boundary rule over a non-BMP fixture; citation coherence against a concurrent
 * `GET /pages/{id}`; and `url`/`title` agreement with the tree endpoint — the §B7 same-snapshot
 * properties PB-SEARCH-1 promises forever.
 */
class SearchInvariantTest : FunSpec({

    suspend fun HttpClient.searchHits(q: String): Pair<JsonObject, List<JsonObject>> {
        val response = get("/api/v1/search?q=$q")
        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body to body.getValue("hits").jsonArray.map { it.jsonObject }
    }

    test("scores are finite and non-increasing in hit order (§A4)") {
        restTest(Fixtures.demoDocs) {
            val (_, hits) = client.searchHits("deploy")
            hits.shouldNotBeEmpty()
            val scores = hits.map { hit ->
                val primitive = hit.getValue("score").jsonPrimitive
                primitive.isString shouldBe false // .double alone would accept a JSON STRING "4.27"
                primitive.double
            }
            scores.all { it.isFinite() }.shouldBeTrue()
            scores.zipWithNext().all { (a, b) -> a >= b }.shouldBeTrue()
        }
    }

    test("highlight offsets are well-formed per §A3, incl. the surrogate-boundary rule (non-BMP fixture)") {
        // The non-BMP fixture: matched tokens flanked by astral-plane characters, so well-formed
        // offsets must skip surrogate pairs correctly and never split one.
        withTempTree(seed = { root ->
            writePage(root, "rockets.md", "# Rocket Fleet\n\nlaunch🚀launch pad and 🚀 launch site🚀.\n")
        }) { root ->
            restTest(root) {
                val (_, hits) = client.searchHits("launch")
                hits.shouldNotBeEmpty()
                hits.forEach { hit ->
                    val snippet = hit.getValue("snippet").jsonPrimitive.content
                    val ranges = hit.getValue("highlights").jsonArray.map { range ->
                        range.jsonObject.getValue("start").jsonPrimitive.int to range.jsonObject.getValue("end").jsonPrimitive.int
                    }
                    ranges.forEach { (start, end) ->
                        withClue("range [$start, $end) in snippet of length ${snippet.length}") {
                            (start in 0..<end && end <= snippet.length).shouldBeTrue()
                            snippet.isCodePointBoundary(start).shouldBeTrue()
                            snippet.isCodePointBoundary(end).shouldBeTrue()
                        }
                    }
                    // Ascending and non-overlapping (§A3).
                    ranges.zipWithNext().all { (a, b) -> a.second <= b.first }.shouldBeTrue()
                }
            }
        }
    }

    test("citation coherence: a search citation verifies against the concurrent GET /pages/{id} (§B7)") {
        restTest(Fixtures.demoDocs) {
            val (_, hits) = client.searchHits("deploy")
            hits.shouldNotBeEmpty()
            hits.forEach { hit ->
                val citation = hit.getValue("citation").jsonObject
                val pageId = citation.getValue("page_id").jsonPrimitive.content
                val page = Json.parseToJsonElement(client.get("/api/v1/pages/$pageId").bodyAsText()).jsonObject
                citation.getValue("content_hash") shouldBe page.getValue("content_hash")
                citation.getValue("path") shouldBe page.getValue("path")
            }
        }
    }

    test("url and title agree with the tree endpoint (§B7: snapshot fields, never engine copies)") {
        restTest(Fixtures.demoDocs) {
            val tree = Json.parseToJsonElement(client.get("/api/v1/tree").bodyAsText()).jsonObject
            val byId = buildMap {
                fun walk(node: JsonObject) {
                    when (node.getValue("type").jsonPrimitive.content) {
                        "page" -> put(node.getValue("id").jsonPrimitive.content, node)
                        else -> node.getValue("children").jsonArray.forEach { walk(it.jsonObject) }
                    }
                }
                walk(tree.getValue("root").jsonObject)
            }

            val (_, hits) = client.searchHits("deploy")
            hits.shouldNotBeEmpty()
            hits.forEach { hit ->
                val node = byId.getValue(hit.getValue("page_id").jsonPrimitive.content)
                hit.getValue("url") shouldBe (node["url"] ?: JsonNull)
                hit.getValue("title") shouldBe node.getValue("title")
            }
        }
    }
})

/** §A3 boundary rule: an offset never lands between the halves of a surrogate pair. */
private fun String.isCodePointBoundary(index: Int): Boolean =
    index == 0 || index == length || !(this[index - 1].isHighSurrogate() && this[index].isLowSurrogate())
