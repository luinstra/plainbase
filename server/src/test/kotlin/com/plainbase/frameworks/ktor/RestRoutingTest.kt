package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The §A4 routing matrix (decision log #7): `/p/{id}` permalinks (302, trailing segment ignored),
 * the `/docs` SPA shell (200), by-path canonical AND alias resolution, the collision-loser
 * permalink reading, and the per-snapshot tree-JSON memoization (§C4).
 */
class RestRoutingTest : FunSpec({

    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
    }

    test("GET /p/{id} 302s to the current canonical /docs URL; a trailing stale slug is ignored") {
        restTest(Fixtures.demoDocs, seed) {
            val client = restClient()

            val bare = client.get("/p/$deployGuideId")
            bare.status shouldBe HttpStatusCode.Found // 302 — the target moves with the page
            bare.headers[HttpHeaders.Location] shouldBe "/docs/guides/deploy-guide"

            val withStaleSlug = client.get("/p/$deployGuideId/stale-slug")
            withStaleSlug.status shouldBe HttpStatusCode.Found
            withStaleSlug.headers[HttpHeaders.Location] shouldBe "/docs/guides/deploy-guide"
        }
    }

    test("permalink id parsing: shape-invalid -> 400, shape-valid unknown (v4) -> 404, uppercase resolves") {
        restTest(Fixtures.demoDocs, seed) {
            val client = restClient()
            client.get("/p/1-1-1-1-1").status shouldBe HttpStatusCode.BadRequest
            client.get("/p/a3bb189e-8bf9-4888-9912-ace4e6543002").status shouldBe HttpStatusCode.NotFound
            client.get("/p/${deployGuideId.uppercase()}").headers[HttpHeaders.Location] shouldBe "/docs/guides/deploy-guide"
        }
    }

    test("GET /docs/guides/deploy-guide serves the SPA shell with 200 (the SPA fetches via by-path)") {
        restTest(Fixtures.demoDocs, seed) {
            val response = client.get("/docs/guides/deploy-guide")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "<div id=\"root\">"
            // Unknown paths serve the shell too — in-app not-found is the SPA's job (§A4 matrix).
            client.get("/docs/no/such/page").status shouldBe HttpStatusCode.OK
        }
    }

    test("by-path resolves canonical AND alias paths; the alias response carries the CURRENT canonical url") {
        restTest(Fixtures.demoDocs, seed) {
            // deploy-guide.md declares redirect_from: [/old/deployment.md] -> alias `old/deployment`.
            val aliased = client.get("/api/v1/pages/by-path/old/deployment")
            aliased.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(aliased.bodyAsText()).jsonObject
            body.getValue("id").jsonPrimitive.content shouldBe deployGuideId
            body.getValue("url").jsonPrimitive.content shouldBe "/docs/guides/deploy-guide"

            client.get("/api/v1/pages/by-path/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            client.get("/api/v1/pages/by-path/no/such/page").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("tree JSON is memoized per snapshot and invalidated by a rescan (§C4)") {
        restTest(Fixtures.demoDocs, seed) { harness ->
            val first = harness.services.treeJson.current()
            harness.services.treeJson.current() shouldBeSameInstanceAs first

            client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.OK
            val second = harness.services.treeJson.current()
            second shouldNotBeSameInstanceAs first
            second shouldBe first // same tree content; only the snapshot identity changed
        }
    }

    test("a slug-collision loser keeps url=null yet stays reachable at /p/{id} (serves the shell)") {
        withTempTree(seed = { root ->
            // Both slugify to `a-b`; raw-byte order makes `a b.md` (0x20) win over `a-b.md` (0x2D).
            writePage(root, "a b.md", "---\ntitle: Winner\n---\n\n# Winner\n")
            writePage(root, "a-b.md", "---\ntitle: Loser\n---\n\n# Loser\n")
        }) { root ->
            restTest(root) { harness ->
                val client = restClient()
                val loser = harness.builder.current.byPath.getValue(TreePath.require("a-b.md"))
                loser.url.shouldBeNull()

                // /p/{id} cannot redirect (no canonical path exists) — the permalink IS the loser's
                // only human URL, so it serves the SPA shell directly (documented chunk-6 reading).
                val permalink = client.get("/p/${loser.id.value}")
                permalink.status shouldBe HttpStatusCode.OK
                permalink.bodyAsText() shouldContain "<div id=\"root\">"

                // The API surface resolves the loser regardless, with the frozen present-null url.
                val api = Json.parseToJsonElement(client.get("/api/v1/pages/${loser.id.value}").bodyAsText()).jsonObject
                api.containsKey("url") shouldBe true // present-null guaranteed (§A4)
                api.getValue("url") shouldBe JsonNull

                // /browse of the loser's FILE path redirects to the permalink — its one durable URL.
                val browse = client.get("/browse/a-b.md")
                browse.status shouldBe HttpStatusCode.Found
                browse.headers[HttpHeaders.Location] shouldBe "/p/${loser.id.value}"

                val winner = harness.builder.current.byPath.getValue(TreePath.require("a b.md"))
                winner.url.shouldNotBeNull()
            }
        }
    }
})
