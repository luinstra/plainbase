package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.service.IndexHarness
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * PB-READ-2 ENFORCED-MODE authz + existence-non-leak + happy-path coverage for the two NET-NEW agent-read endpoints
 * (`validate-links`, `metadata`) plus the `read_file` contract over the EXISTING `read_page` endpoint
 * (`GET /{id}` → verbatim `markdown`). CI/smoke run `auth.mode=off` (checkRead permits all), so the gate +
 * existence-non-leak is GATE-INVISIBLE unless an `enforced=true` route context drives it (the `ci-runs-auth-off-blind`
 * rule). Mirrors `ProposalAuthzRouteTest`'s `withApp` harness.
 */
class ReadAuthzRouteTest : FunSpec({

    // A fixture page with a frontmatter header AND body markup (a link target + a fenced code block) so the
    // `read_file` happy-path can prove the WHOLE verbatim file is served. The fence is brace-free prose-adjacent
    // Kotlin so no flexmark render-length hazard (and this is a temp corpus, not the demo-docs golden corpus anyway).
    val docSource =
        """
        ---
        title: Doc
        ---

        # Doc

        See the [target](/target) page.

        ```kotlin
        val x = 1
        ```
        """.trimIndent() + "\n"

    // A shape-valid (canonical UUIDv7) but UNINDEXED id — used to prove the existence-non-leak (same status as the
    // real page for a denied caller; never a 404, which would reveal the lookup ran).
    val absentId = "0197dead-beef-7000-8000-000000000abc"

    fun withApp(
        enforced: Boolean,
        principal: Principal?,
        seedAgentMode: AgentMode? = null,
        block: suspend (io.ktor.server.testing.ApplicationTestBuilder, IndexHarness) -> Unit,
    ) {
        val root = Files.createTempDirectory("plainbase-read-authz")
        val searchRoot = Files.createTempDirectory("read-authz-search")
        try {
            Files.writeString(root.resolve("doc.md"), docSource)
            val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)
            IndexHarness(root, contentStore = store).use { harness ->
                harness.builder.rebuild()
                val resolved: Principal = when {
                    seedAgentMode != null -> Principal.Agent(harness.apiTokens.mint(label = "ci", mode = seedAgentMode).id)
                    else -> principal ?: Principal.Anonymous
                }
                val ctx = harness.testRouteContext(
                    contentStore = store,
                    searchProvider = harness.fts(searchRoot),
                    enforced = enforced,
                    extract = fixedPrincipal(resolved),
                )
                testApplication {
                    application { plainbaseModule(ctx) }
                    block(this, harness)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
            searchRoot.toFile().deleteRecursively()
        }
    }

    suspend fun io.ktor.server.testing.ApplicationTestBuilder.docId(): String =
        Json.parseToJsonElement(client.get("/api/v1/pages/by-path/doc").bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content

    // ---- existence-non-leak: a denied caller gets the SAME status for a present AND an absent page ----

    listOf("validate-links", "metadata").forEach { suffix ->
        test("enforced: an unauthorized $suffix read DENIES present and absent ALIKE (no existence leak)") {
            withApp(enforced = true, principal = Principal.Anonymous) { app, harness ->
                // The id comes from the published snapshot, NOT an HTTP read — an Anonymous read is itself denied,
                // so we cannot discover the id over the wire (which is the whole point of the non-leak gate).
                val id = harness.builder.current.pages.single().id.value
                val present = app.client.get("/api/v1/pages/$id/$suffix")
                val absent = app.client.get("/api/v1/pages/$absentId/$suffix")
                // The checkRead deny fires BEFORE the page lookup, so present-vs-absent is indistinguishable.
                present.status shouldBe absent.status
                present.status shouldBe HttpStatusCode.Unauthorized
                // A 404 would itself reveal the lookup ran — assert it is NOT one.
                (present.status == HttpStatusCode.NotFound) shouldBe false
            }
        }

        test("enforced: a READ_ONLY agent is ALLOWED the $suffix read (200)") {
            withApp(enforced = true, principal = null, seedAgentMode = AgentMode.READ_ONLY) { app, _ ->
                val id = app.docId()
                app.client.get("/api/v1/pages/$id/$suffix").status shouldBe HttpStatusCode.OK
            }
        }

        // The AUTHORIZED error paths (an ALLOWED caller hitting the lookup), pinned to the SAME fallback the bare
        // `GET /{id}` page route uses (call.pageId() → 400 invalid_page_id; null facade result → 404 page_not_found),
        // so the two suffix routes never drift from the shared id-shape/lookup idiom.
        test("enforced: an ALLOWED $suffix read of a shape-valid UNKNOWN id is 404 page_not_found") {
            withApp(enforced = true, principal = null, seedAgentMode = AgentMode.READ_ONLY) { app, _ ->
                val resp = app.client.get("/api/v1/pages/$absentId/$suffix")
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    .getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "page_not_found"
            }
        }

        test("enforced: an ALLOWED $suffix read of a MALFORMED id is 400 invalid_page_id") {
            withApp(enforced = true, principal = null, seedAgentMode = AgentMode.READ_ONLY) { app, _ ->
                val resp = app.client.get("/api/v1/pages/1-1-1-1-1/$suffix")
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    .getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "invalid_page_id"
            }
        }
    }

    // ---- read_file: the WHOLE VERBATIM file (frontmatter header + body) via the EXISTING read_page endpoint ----

    test("read_file: GET /{id} markdown is the verbatim file — frontmatter fence AND raw body markup") {
        withApp(enforced = true, principal = null, seedAgentMode = AgentMode.READ_ONLY) { app, _ ->
            val id = app.docId()
            val body = Json.parseToJsonElement(app.client.get("/api/v1/pages/$id").bodyAsText()).jsonObject
            val markdown = body.getValue("markdown").jsonPrimitive.content
            // The FRONTMATTER header is present (NOT stripped): the file starts with the `---` fence + the title scalar.
            markdown.startsWith("---") shouldBe true
            markdown shouldContain "title: Doc"
            // Raw BODY markup is preserved (NOT rendered to HTML / stripped to plain text): the link target + a fence.
            markdown shouldContain "(/target)"
            markdown shouldContain "```kotlin"
        }
    }

    // ---- happy-path field coverage + routing precedence ----

    test("get_page_metadata happy path: id/path/url/permalink/content_hash/commit/title/headings, headings document order") {
        withApp(enforced = true, principal = null, seedAgentMode = AgentMode.READ_ONLY) { app, harness ->
            val id = app.docId()
            val meta = Json.parseToJsonElement(app.client.get("/api/v1/pages/$id/metadata").bodyAsText()).jsonObject
            meta.getValue("id").jsonPrimitive.content shouldBe id
            meta.getValue("path").jsonPrimitive.content shouldBe "doc.md"
            // url + permalink track the REAL IndexedPage computation, not a brittle literal (PageIndex.url/permalink).
            val page = harness.builder.current.byId.getValue(com.plainbase.domain.page.PageId.require(id))
            meta.getValue("url").jsonPrimitive.content shouldBe page.url
            meta.getValue("permalink").jsonPrimitive.content shouldBe "/p/$id"
            meta.getValue("permalink").jsonPrimitive.content shouldBe page.permalink
            meta.getValue("title").jsonPrimitive.content shouldBe "Doc"
            meta.getValue("content_hash").jsonPrimitive.content shouldContain "sha256:"
            // commit is PRESENT (the explicitNulls RestJson) and null off Git.
            meta.containsKey("commit") shouldBe true
            val headings = meta.getValue("headings").jsonArray
            headings.size shouldBe 1
            headings.single().jsonObject.getValue("id").jsonPrimitive.content shouldBe "doc"
        }
    }

    test("routing precedence: GET /{id}/metadata + /{id}/validate-links are NOT shadowed by GET /{id}") {
        withApp(enforced = true, principal = null, seedAgentMode = AgentMode.READ_ONLY) { app, _ ->
            val id = app.docId()
            // /metadata returns its OWN shape (a `headings` array, no `markdown` key) — not the page payload.
            val meta = Json.parseToJsonElement(app.client.get("/api/v1/pages/$id/metadata").bodyAsText()).jsonObject
            meta.containsKey("markdown") shouldBe false
            (meta["headings"] is JsonArray) shouldBe true
            // /validate-links returns its OWN shape (a `broken` array) — not the page payload.
            val links = Json.parseToJsonElement(app.client.get("/api/v1/pages/$id/validate-links").bodyAsText()).jsonObject
            (links["broken"] is JsonArray) shouldBe true
            links.containsKey("markdown") shouldBe false
        }
    }
})

/** A real FTS provider over a temp search.db so the read path resolves; the caller owns [searchRoot]'s cleanup. */
private fun IndexHarness.fts(searchRoot: java.nio.file.Path): com.plainbase.domain.search.SearchProvider {
    val searchDb = com.plainbase.frameworks.search.SearchDb(searchRoot.resolve("search.db"))
    return com.plainbase.frameworks.search.Fts5SearchProvider(searchDb)
}
