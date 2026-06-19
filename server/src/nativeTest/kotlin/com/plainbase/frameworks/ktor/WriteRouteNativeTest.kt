package com.plainbase.frameworks.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Native-image smoke for the PB-WRITE-1 `PUT /api/v1/pages/{id}` route: the closed-world image would
 * otherwise never compile the variant write DTO serializers, the scoped `RestJson` encode of the
 * write shapes, or the streaming body read — HTTP + serialization is the classic native reflection
 * trap (the `RestApiNativeTest` rationale). A matching `If-Match` raw PUT writes; a stale one is a
 * 409 drift. kotlin.test + @Tag("native") only.
 */
@Tag("native")
class WriteRouteNativeTest {

    private val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    private val markdown = ContentType.parse("text/markdown")

    private fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

    @Test
    fun `put writes on a matching If-Match and conflicts on a stale one, natively`() {
        val original = "---\nid: $pageId\ntitle: Native Save\n---\n\n# Native Save\n\nbody.\n"
        withRestServices(pages = mapOf("guides/native.md" to original)) { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = noRedirectClient()

                // GET yields the strong-tag ETag that the next PUT requires.
                val got = client.get("/api/v1/pages/$pageId")
                assertEquals(HttpStatusCode.OK, got.status)
                val tag = assertNotNull(got.headers[HttpHeaders.ETag])

                // A matching PUT writes; the variant DTO serializer must survive the closed world.
                val edited = original + "\nnative edit.\n"
                val put = client.put("/api/v1/pages/$pageId") {
                    header(HttpHeaders.IfMatch, tag)
                    contentType(markdown)
                    setBody(edited)
                }
                assertEquals(HttpStatusCode.OK, put.status)
                val body = Json.parseToJsonElement(put.bodyAsText()).jsonObject
                assertNotNull(body["content_hash"]?.jsonPrimitive?.content)

                // A stale If-Match (the original tag, now wrong) is a 409 content_changed drift.
                val stale = client.put("/api/v1/pages/$pageId") {
                    header(HttpHeaders.IfMatch, tag)
                    contentType(markdown)
                    setBody(original + "\nother.\n")
                }
                assertEquals(HttpStatusCode.Conflict, stale.status)
                val error = Json.parseToJsonElement(stale.bodyAsText()).jsonObject["error"]?.jsonObject
                assertEquals("conflict", error?.get("code")?.jsonPrimitive?.content)
                assertEquals("content_changed", error?.get("reason")?.jsonPrimitive?.content)
            }
        }
    }
}
