package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
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
        idMap.bind(
            RootedPath(RootName.MAIN, TreePath.require("guides/deploy-guide.md")),
            PageId.require(deployGuideId),
            materialized = false,
        )
    }

    suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    test("the shared permalink dispatcher classifies bare, rooted, decorative trailing, and malformed paths") {
        restTest(Fixtures.demoDocs, seed) {
            val client = restClient()

            val bare = client.get("/p/$deployGuideId")
            bare.status shouldBe HttpStatusCode.Found
            bare.headers[HttpHeaders.Location] shouldBe "/docs/main/guides/deploy-guide"

            val rooted = client.get("/p/main/$deployGuideId")
            rooted.status shouldBe HttpStatusCode.Found
            rooted.headers[HttpHeaders.Location] shouldBe "/docs/main/guides/deploy-guide"

            val badId = client.get("/p/main/not-a-uuid")
            badId.status shouldBe HttpStatusCode.BadRequest
            badId.bodyAsText() shouldContain "not-a-uuid"

            client.get("/p/$deployGuideId/stale-slug").status shouldBe HttpStatusCode.Found
            client.get("/p/main/$deployGuideId/stale-slug").status shouldBe HttpStatusCode.Found

            listOf(
                "/p/$deployGuideId/",
                "/p/$deployGuideId//",
                "/p/$deployGuideId///",
                "/p/main/$deployGuideId//",
            ).forEach { path ->
                val response = client.get(path)
                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "/docs/main/guides/deploy-guide"
            }

            listOf(
                "/p/main//$deployGuideId",
                "/p//$deployGuideId",
                "/p/main/$deployGuideId//extra",
            ).forEach { path ->
                val response = client.get(path)
                response.status shouldBe HttpStatusCode.BadRequest
                response.errorCode() shouldBe "invalid_page_id"
            }
        }
    }

    test("the shared permalink dispatcher pins invalid, mount, deleted, and raw-canonical cases") {
        restTest(Fixtures.demoDocs, seed) {
            val client = restClient()

            val invalidBare = client.get("/p/not-a-uuid")
            invalidBare.status shouldBe HttpStatusCode.BadRequest
            invalidBare.errorCode() shouldBe "invalid_page_id"
            invalidBare.bodyAsText() shouldContain "not-a-uuid"

            client.get("/p/a3bb189e-8bf9-4888-9912-ace4e6543002").status shouldBe HttpStatusCode.NotFound
            client.get("/p/${deployGuideId.uppercase()}").headers[HttpHeaders.Location] shouldBe
                "/docs/main/guides/deploy-guide"

            listOf("/p", "/p/").forEach { path ->
                val response = client.get(path)
                response.status shouldBe HttpStatusCode.BadRequest
                response.errorCode() shouldBe "invalid_page_id"
            }

            val deleted = client.get("/p/" + "r/main/$deployGuideId")
            deleted.status shouldBe HttpStatusCode.BadRequest
            deleted.errorCode() shouldBe "invalid_page_id"

            val encodedSlash = client.get("/p/main%2F$deployGuideId")
            encodedSlash.status shouldBe HttpStatusCode.BadRequest
            encodedSlash.errorCode() shouldBe "invalid_page_id"

            val encodedId = client.get("/p/%30${deployGuideId.drop(1)}")
            encodedId.status shouldBe HttpStatusCode.BadRequest
            encodedId.errorCode() shouldBe "invalid_page_id"
        }
    }

    test("GET /docs/main/guides/deploy-guide serves the SPA shell with 200 (the SPA fetches via by-path)") {
        restTest(Fixtures.demoDocs, seed) {
            val response = client.get("/docs/main/guides/deploy-guide")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "<div id=\"root\">"
            // Unknown paths under a known root serve the shell too - in-app not-found is the SPA's
            // job (§A4 matrix); a rootless unknown path 301s first (RootUrlGrammarTest owns that).
            client.get("/docs/main/no/such/page").status shouldBe HttpStatusCode.OK
        }
    }

    test("by-path resolves canonical AND alias paths; the alias response carries the CURRENT canonical url") {
        restTest(Fixtures.demoDocs, seed) {
            // deploy-guide.md declares redirect_from: [/old/deployment.md] -> alias `old/deployment`.
            val aliased = client.get("/api/v1/pages/by-path/old/deployment")
            aliased.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(aliased.bodyAsText()).jsonObject
            body.getValue("id").jsonPrimitive.content shouldBe deployGuideId
            body.getValue("url").jsonPrimitive.content shouldBe "/docs/main/guides/deploy-guide"

            // Both the root-qualified and the legacy rootless tail resolve (D-C3-3).
            client.get("/api/v1/pages/by-path/main/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            client.get("/api/v1/pages/by-path/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            client.get("/api/v1/pages/by-path/no/such/page").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("a folder's URL prefix stays out of by-path space (ADR-0003: landing views are client-rendered)") {
        restTest(Fixtures.demoDocs, seed) {
            // `guides` is a folder with a tree-node url of /docs/main/guides - but by-path semantics
            // are unchanged: only PAGES resolve; the SPA's folder landing kicks in on this very 404.
            client.get("/api/v1/pages/by-path/main/guides").status shouldBe HttpStatusCode.NotFound
            // The routing matrix still serves the shell at the folder URL, like every /docs path.
            client.get("/docs/main/guides").status shouldBe HttpStatusCode.OK
        }
    }

    test("tree JSON is memoized per snapshot and invalidated by a rescan (§C4)") {
        restTest(Fixtures.demoDocs, seed) { harness ->
            val first = harness.treeJson.current()
            harness.treeJson.current() shouldBeSameInstanceAs first

            client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.OK
            val second = harness.treeJson.current()
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
                val loser = harness.builder.current.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("a-b.md")))
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
                browse.headers[HttpHeaders.Location] shouldBe "/p/main/${loser.id.value}"

                val winner = harness.builder.current.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("a b.md")))
                winner.url.shouldNotBeNull()
            }
        }
    }
})
