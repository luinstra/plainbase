package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PB-REST-1 golden shapes (§A4, FROZEN): JSON snapshots under `/golden/rest/` rendered against the
 * editorially frozen fixture pages `guides/deploy-guide.md` and `index.md` — editing either file
 * means deliberate, reviewed golden regeneration. Stable ids are injected by seeding the id_map
 * with UUID literals before the first rebuild; comparison is parsed-JSON-tree (key set + types +
 * values); `content_hash` is recomputed from the fixture bytes at test time (see [RestGolden]).
 */
class RestGoldenTest : FunSpec({

    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val welcomeId = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
        idMap.bind(TreePath.require("index.md"), PageId.require(welcomeId), materialized = false)
    }
    val deployGuideHash = RestGolden.contentHashOf(Fixtures.demoDocs.resolve("guides/deploy-guide.md"))
    val welcomeHash = RestGolden.contentHashOf(Fixtures.demoDocs.resolve("index.md"))

    // The §A4 citation-uri grammar: plainbase://{lowercase-uuid}["#"heading]"@"(git-sha | sha256:hex).
    val uriGrammar = Regex(
        "^plainbase://[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
            "(#[^@]+)?@([0-9a-f]{7,40}|sha256:[0-9a-f]{64})$",
    )

    fun goldenTest(block: suspend ApplicationTestBuilder.(RestHarness) -> Unit) = restTest(Fixtures.demoDocs, seed, block = block)

    suspend fun HttpResponse.jsonBody(): JsonObject {
        status shouldBe HttpStatusCode.OK
        return Json.parseToJsonElement(bodyAsText()).jsonObject
    }

    test("GET /api/v1/pages/{id} matches the frozen golden (deploy-guide)") {
        goldenTest {
            val body = client.get("/api/v1/pages/$deployGuideId").jsonBody()

            body shouldBe RestGolden.load("page-deploy-guide.json", mapOf("content_hash" to deployGuideHash))
            // Belt-and-suspenders beside the golden: the recompute really is the response value,
            // commit is PRESENT and null, and the citation uri matches the §A4 grammar.
            body.getValue("content_hash").jsonPrimitive.content shouldBe deployGuideHash
            body.getValue("commit") shouldBe JsonNull
            val citation = body.getValue("citation").jsonObject
            citation.getValue("commit") shouldBe JsonNull
            citation.getValue("heading_id") shouldBe JsonNull
            citation.getValue("uri").jsonPrimitive.content shouldMatch uriGrammar
        }
    }

    test("GET /api/v1/pages/{id} matches the frozen golden (index.md)") {
        goldenTest {
            val body = client.get("/api/v1/pages/$welcomeId").jsonBody()
            body shouldBe RestGolden.load("page-index.json", mapOf("content_hash" to welcomeHash))
        }
    }

    test("an UPPERCASE {id} path parameter resolves to the same page; the response carries lowercase") {
        goldenTest {
            val body = client.get("/api/v1/pages/${deployGuideId.uppercase()}").jsonBody()
            body shouldBe RestGolden.load("page-deploy-guide.json", mapOf("content_hash" to deployGuideHash))
            body.getValue("id").jsonPrimitive.content shouldBe deployGuideId
        }
    }

    test("GET /api/v1/pages/by-path/{path} returns the IDENTICAL shape and values as by-id") {
        goldenTest {
            val byPath = client.get("/api/v1/pages/by-path/guides/deploy-guide").jsonBody()
            val byId = client.get("/api/v1/pages/$deployGuideId").jsonBody()

            byPath shouldBe byId
            byPath shouldBe RestGolden.load("page-deploy-guide.json", mapOf("content_hash" to deployGuideHash))
        }
    }

    test("GET /api/v1/pages/{id}/html matches the frozen golden (html markup itself is non-frozen)") {
        goldenTest {
            val body = client.get("/api/v1/pages/$deployGuideId/html").jsonBody()

            // The html CONTENT is governed by PB-SLUG-1/PB-LINK-1 — assert those invariants —
            // but its markup details are explicitly non-frozen, so the golden carries a
            // placeholder rather than freezing flexmark's exact output.
            val html = body.getValue("html").jsonPrimitive.content
            html.shouldNotBeBlank()
            html shouldContain "id=\"deploy-guide\""
            html shouldContain "id=\"prerequisites\""
            html shouldContain "href=\"/docs/infra/kubernetes\"" // §A2: hrefs are path URLs
            html shouldContain "src=\"/assets/infra/assets/diagram.svg\""

            val normalized = JsonObject(body + ("html" to JsonPrimitive("{{html}}")))
            normalized shouldBe RestGolden.load("page-html-deploy-guide.json", mapOf("content_hash" to deployGuideHash))
        }
    }

    test("GET /api/v1/tree matches the SHAPE-scoped golden (M4: types, membership, field values incl url)") {
        goldenTest {
            val body = client.get("/api/v1/tree").jsonBody()

            val seededIds = setOf(deployGuideId, welcomeId)
            val actual = RestGolden.normalizeTree(body, seededIds)
            val golden = RestGolden.normalizeTree(RestGolden.load("tree.json"), seededIds)
            actual shouldBe golden
        }
    }

    test("tree child ORDERING matches the documented (not frozen) collation — updatable with review") {
        goldenTest {
            val body = client.get("/api/v1/tree").jsonBody()

            fun walk(node: JsonObject, out: MutableList<String>) {
                val type = node.getValue("type").jsonPrimitive.content
                out += "$type:${node.getValue("path").jsonPrimitive.content}"
                if (type == "folder") (node.getValue("children") as JsonArray).forEach { walk(it.jsonObject, out) }
            }
            val served = buildList { walk(body.getValue("root").jsonObject, this) }

            val expected = checkNotNull(javaClass.getResourceAsStream("/golden/rest/tree-order.txt"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
            served shouldContainExactly expected
        }
    }

    test("error envelopes match the frozen goldens (§A4 codes; regex decides 400-vs-404, never JDK leniency)") {
        goldenTest {
            // Fails the canonical-shape regex (UUID.fromString would accept it) -> 400 invalid_page_id.
            val invalid = client.get("/api/v1/pages/1-1-1-1-1")
            invalid.status shouldBe HttpStatusCode.BadRequest
            Json.parseToJsonElement(invalid.bodyAsText()) shouldBe RestGolden.load("error-invalid-page-id.json")

            // Shape-valid unknown id of ANY version (a v4 literal) -> 404 page_not_found (opaque ids).
            val unknown = client.get("/api/v1/pages/a3bb189e-8bf9-4888-9912-ace4e6543002")
            unknown.status shouldBe HttpStatusCode.NotFound
            Json.parseToJsonElement(unknown.bodyAsText()) shouldBe RestGolden.load("error-page-not-found.json")

            // Unknown by-path -> 404 page_not_found.
            val unknownPath = client.get("/api/v1/pages/by-path/no/such/page")
            unknownPath.status shouldBe HttpStatusCode.NotFound
            Json.parseToJsonElement(unknownPath.bodyAsText()) shouldBe RestGolden.load("error-by-path-not-found.json")

            // Encoded traversal against assets -> 400 invalid_path (decode-once, then TreePath law).
            val traversal = client.get("/assets/%2e%2e/secrets")
            traversal.status shouldBe HttpStatusCode.BadRequest
            Json.parseToJsonElement(traversal.bodyAsText()) shouldBe RestGolden.load("error-invalid-path.json")
        }
    }
})
