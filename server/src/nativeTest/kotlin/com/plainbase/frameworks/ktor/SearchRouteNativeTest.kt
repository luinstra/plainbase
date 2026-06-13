package com.plainbase.frameworks.ktor

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Native-image smoke for the S4 search surface: the closed-world image would otherwise never
 * compile the `SearchResponse`/`SearchHitDto` serializers, the route's `Dispatchers.IO` hop, or
 * the §B7 assembly under TEST — the same HTTP+serialization trap the chunk-6 smoke guards, now
 * over the search endpoint. kotlin.test + @Tag("native") only.
 */
@Tag("native")
class SearchRouteNativeTest {

    private val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"

    @Test
    fun `search endpoint serves assembled hits and the invalid_query envelope natively`() {
        val page = "---\nid: $pageId\ntitle: Native Search\n---\n\n# Native Search\n\n## Usage\n\nfind the flux capacitor here.\n"
        withRestServices(pages = mapOf("guides/search-smoke.md" to page)) { services ->
            testApplication {
                application { plainbaseModule(services) }

                val response = client.get("/api/v1/search?q=capacitor")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("embedded", body["engine"]?.jsonPrimitive?.content)
                assertEquals("capacitor", body["query"]?.jsonPrimitive?.content)
                assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())

                // §B7 assembly: snapshot fields, heading breadcrumb, and the coherent citation.
                val hit = assertNotNull(body["hits"]?.jsonArray?.single()?.jsonObject)
                assertEquals(pageId, hit["page_id"]?.jsonPrimitive?.content)
                assertEquals("/docs/guides/search-smoke", hit["url"]?.jsonPrimitive?.content)
                assertEquals("Native Search", hit["title"]?.jsonPrimitive?.content)
                assertEquals("usage", hit["heading_id"]?.jsonPrimitive?.content)
                val citation = assertNotNull(hit["citation"]?.jsonObject)
                assertEquals("usage", citation["heading_id"]?.jsonPrimitive?.content)

                // The frozen §A5 envelope for an §A1 violation, natively too.
                val invalid = client.get("/api/v1/search?q=")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                val error = Json.parseToJsonElement(invalid.bodyAsText()).jsonObject["error"]?.jsonObject
                assertEquals("invalid_query", error?.get("code")?.jsonPrimitive?.content)
            }
        }
    }
}
