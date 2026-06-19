package com.plainbase.frameworks.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native-image smoke for the W3b `POST /api/v1/preview` route: HTTP + kotlinx.serialization + flexmark
 * is the classic native reflection trap (the `WriteRouteNativeTest` rationale), so a per-route smoke
 * proving THIS route's `PreviewResponse` serializer + `renderPreview` (flexmark) survive the closed
 * world is the established house pattern. kotlin.test + @Tag("native") only.
 */
@Tag("native")
class PreviewNativeTest {

    private val markdown = ContentType.parse("text/markdown")

    private fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

    @Test
    fun `preview renders a submitted buffer with headings, natively`() {
        withRestServices { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = noRedirectClient()

                val resp = client.post("/api/v1/preview") {
                    contentType(markdown)
                    setBody("# Native Preview\n\nbody text.\n")
                }
                assertEquals(HttpStatusCode.OK, resp.status)
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                assertTrue(body["html"]!!.jsonPrimitive.content.isNotEmpty())
                val headings = body["headings"]!!.jsonArray
                assertTrue(headings.isNotEmpty())
                assertEquals("Native Preview", headings.first().jsonObject["text"]!!.jsonPrimitive.content)
            }
        }
    }
}
