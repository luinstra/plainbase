package com.plainbase.frameworks.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Native-image smoke test for the chunk-6 REST surface: the closed-world image would otherwise
 * never compile the routes, the sealed tree-DTO serializers, or the scoped `RestJson` encode path
 * — HTTP + serialization is the classic native reflection trap, so the automated native gate must
 * cover it the way it covers adopt and the index pass. kotlin.test + @Tag("native") only.
 *
 * Also the native-runtime gate for the chunk-3 `updated` validator: the tree build runs
 * `kotlinx.datetime.LocalDate.parse` only when a page actually carries an `updated` frontmatter, so
 * one fixture page holds a valid date (must round-trip) and one holds an impossible date (must reject
 * to null) — proving `LocalDate.parse` *executes* under the closed-world image, not merely links.
 */
@Tag("native")
class RestApiNativeTest {

    private val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"

    private fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

    @Test
    fun `rest surface serves page json, tree, error envelope, and permalink natively`() {
        val page = "---\nid: $pageId\ntitle: Native Page\nupdated: 2026-05-30\n---\n\n# Native Page\n\nBody.\n"
        // Impossible calendar date — LocalDate.parse must reject it to null, natively.
        val stale = "---\ntitle: Stale\nupdated: 2026-02-30\n---\n\n# Stale\n\nBody.\n"
        withRestServices(pages = mapOf("guides/native.md" to page, "guides/stale.md" to stale)) { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = noRedirectClient()

                // Page payload: lowercase id from an UPPERCASE path param, citation present.
                val response = client.get("/api/v1/pages/${pageId.uppercase()}")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(pageId, body["id"]?.jsonPrimitive?.content)
                assertEquals("/docs/guides/native", body["url"]?.jsonPrimitive?.content)
                val citation = assertNotNull(body["citation"]?.jsonObject)
                assertEquals(pageId, citation["page_id"]?.jsonPrimitive?.content)

                // Tree: the sealed polymorphic DTO serializer must survive the closed world.
                val tree = Json.parseToJsonElement(client.get("/api/v1/tree").bodyAsText()).jsonObject
                val root = tree["root"]?.jsonObject
                assertEquals("folder", root?.get("type")?.jsonPrimitive?.content)
                // Folder nodes carry the additive url prefix (ADR-0003) — natively too.
                val guides = root?.get("children")?.jsonArray?.first()?.jsonObject
                assertEquals("/docs/guides", guides?.get("url")?.jsonPrimitive?.content)

                // chunk-3 `updated` validator runs `LocalDate.parse` HERE, inside the native image:
                // a valid date round-trips; an impossible one (2026-02-30) is rejected to explicit null.
                val pagesByTitle = guides?.get("children")?.jsonArray
                    ?.map { it.jsonObject }
                    ?.filter { it["type"]?.jsonPrimitive?.content == "page" }
                    ?.associateBy { it["title"]?.jsonPrimitive?.content }
                    .orEmpty()
                assertEquals("2026-05-30", pagesByTitle["Native Page"]?.get("updated")?.jsonPrimitive?.content)
                assertEquals(JsonNull, pagesByTitle["Stale"]?.get("updated"))

                // Error envelope: the §A4 shape gate (regex, not UUID.fromString) natively too.
                val invalid = client.get("/api/v1/pages/1-1-1-1-1")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                val error = Json.parseToJsonElement(invalid.bodyAsText()).jsonObject["error"]?.jsonObject
                assertEquals("invalid_page_id", error?.get("code")?.jsonPrimitive?.content)

                // Permalink: 302 to the canonical path URL; rescan keeps it live after a rebuild.
                val permalink = client.get("/p/$pageId")
                assertEquals(HttpStatusCode.Found, permalink.status)
                assertEquals("/docs/guides/native", permalink.headers[HttpHeaders.Location])
                assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/rescan").status)
                assertEquals("/docs/guides/native", client.get("/p/$pageId").headers[HttpHeaders.Location])
            }
        }
    }
}
