package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path

/** Boots a server wired with ONE enabled engine over [root] and runs [block] against its HTTP client. */
fun interface ServeSearchRoute {
    fun serve(root: Path, block: suspend (HttpClient) -> Unit)
}

/**
 * The route-level half of the §A6 engine-agnosticism invariant (plan line 369 — the S3 leg, folded
 * into S4): every ENABLED engine serves the IDENTICAL PB-SEARCH-1 response SHAPE — field presence,
 * JSON types, and §A2 present-null — under the same HTTP assertions. Engine-BLIND like the
 * provider contract: the spec takes its server wiring as a [ServeSearchRoute] hook, never imports
 * an engine, and asserts SHAPE, never values (exact values are the embedded engine's tier-1
 * goldens; ranking/snippet content are the deliberately unfrozen ledger). A second engine joins by
 * adding one leaf class with its own wiring — before it ships, per the frozen invariant.
 */
abstract class SearchRouteShapeContract(serve: ServeSearchRoute) : FunSpec({

    // Engine-neutral micro-corpus (plain lowercase ASCII terms — no CJK, no tokenizer edges):
    // a heading hit, a page-level hit (heading-less page), and a same-parent slug collision so
    // the loser's null url exercises §A2 present-null deterministically.
    fun withCorpus(block: suspend (HttpClient) -> Unit) = withTempTree(seed = { root ->
        writePage(root, "a/zebra.md", "---\nslug: clash\n---\n# Zebra Notes\n\n## Care\n\nquagga grooming basics.\n")
        writePage(root, "a/zulu.md", "---\nslug: clash\n---\nquagga grooming basics.\n")
    }) { root -> serve.serve(root, block) }

    suspend fun HttpClient.search(query: String): JsonObject {
        val response = get("/api/v1/search?q=$query")
        response.status shouldBe HttpStatusCode.OK
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    test("a hit response carries every frozen §A2 field with its frozen type, nullables present-null") {
        withCorpus { client ->
            val body = client.search("quagga")
            body.assertTopLevelShape(expectedQuery = "quagga")
            body.getValue("total").jsonPrimitive.longOrNull.shouldNotBeNull()

            val hits = body.getValue("hits").jsonArray.map { it.jsonObject }
            // Both corpus pages match a unique ASCII token; any enabled engine surfaces both
            // (matching THIS is the provider contract's floor — here it guarantees the shape
            // sweep below covers a heading hit, a page-level hit, and a null-url hit).
            hits.map { it.string("path") } shouldContainExactlyInAnyOrder listOf("a/zebra.md", "a/zulu.md")

            hits.forEach { hit -> hit.assertHitShape() }
            hits.map { it.getValue("url") }.count { it == JsonNull } shouldBe 1 // the collision loser, present-null
            hits.count { it.getValue("heading_id") == JsonNull } shouldBe 1 // the heading-less page's hit
        }
    }

    test("a zero-hit response keeps the identical top-level shape with an empty hits array") {
        withCorpus { client ->
            val body = client.search("xyzzyplugh")
            body.assertTopLevelShape(expectedQuery = "xyzzyplugh")
            body.getValue("total").jsonPrimitive.longOrNull shouldBe 0L
            body.getValue("hits") shouldBe JsonArray(emptyList())
        }
    }

    test("an §A1 violation answers the frozen error-envelope shape with the cross-engine code") {
        withCorpus { client ->
            val response = client.get("/api/v1/search?q=")
            response.status shouldBe HttpStatusCode.BadRequest
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            error.string("code") shouldBe "invalid_query" // the CODE is frozen vocabulary; the message text is the embedded golden's
            error.string("message")
        }
    }
})

// ---- shape assertions (presence + JSON types only — values stay engine-owned) ------------------

private fun JsonObject.assertTopLevelShape(expectedQuery: String) {
    string("query") shouldBe expectedQuery // the one frozen VALUE: the trimmed q echoed back
    string("engine")
    int("limit")
    int("offset")
    (getValue("hits") is JsonArray).shouldBeTrue()
}

private fun JsonObject.assertHitShape() = withClue("hit shape: $this") {
    string("page_id")
    string("path")
    nullableString("url")
    string("title")
    val headingId = nullableString("heading_id")
    val headingText = nullableString("heading_text")
    (headingText == null) shouldBe (headingId == null) // §A2: null iff page-level
    val headingPath = getValue("heading_path").jsonArray
    headingPath.forEach { (it as JsonPrimitive).isString.shouldBeTrue() }
    if (headingId == null) headingPath shouldBe JsonArray(emptyList())

    val snippet = string("snippet")
    getValue("highlights").jsonArray.forEach { range ->
        val start = range.jsonObject.int("start")
        val end = range.jsonObject.int("end")
        (start in 0..<end && end <= snippet.length).shouldBeTrue()
    }
    val score = getValue("score").jsonPrimitive
    score.isString shouldBe false
    score.doubleOrNull.shouldNotBeNull().isFinite().shouldBeTrue()

    val citation = getValue("citation").jsonObject
    citation.string("page_id")
    citation.nullableString("heading_id") shouldBe headingId
    citation.string("path")
    citation.string("content_hash")
    citation.getValue("commit") shouldBe JsonNull // present-null until Phase 3
    citation.string("uri")
}

private fun JsonObject.string(key: String): String {
    val primitive = getValue(key).jsonPrimitive
    withClue("$key must be a JSON string") { primitive.isString.shouldBeTrue() }
    return primitive.content
}

private fun JsonObject.nullableString(key: String): String? {
    val value = getValue(key) // present — getValue throws on an ABSENT key (the present-null rule)
    if (value == JsonNull) return null
    withClue("$key must be a JSON string or null") { value.jsonPrimitive.isString.shouldBeTrue() }
    return value.jsonPrimitive.content
}

private fun JsonObject.int(key: String): Int {
    val primitive = getValue(key).jsonPrimitive
    return withClue("$key must be a JSON integer") {
        primitive.isString shouldBe false
        primitive.intOrNull.shouldNotBeNull()
    }
}
