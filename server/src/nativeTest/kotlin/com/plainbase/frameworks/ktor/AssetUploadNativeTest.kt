package com.plainbase.frameworks.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native-image smoke for the W3b `POST /api/v1/pages/{id}/assets` route: proves the asset route +
 * `writeAssetExclusive` + the post-write `rebuild()` + the `AssetUploadResponse` serializer survive the
 * closed-world native image (HTTP + serialization is the native reflection trap — the
 * `WriteRouteCreateNativeTest` rationale). A page is seeded so the upload targets a real folder.
 * kotlin.test + @Tag("native") only.
 */
@Tag("native")
class AssetUploadNativeTest {

    private val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"

    private fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

    @Test
    fun `an asset uploads and serves back, natively`() {
        val page = "---\nid: $pageId\ntitle: Native Asset\n---\n\n# Native Asset\n"
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        withRestServices(pages = mapOf("guides/native.md" to page)) { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = noRedirectClient()

                val post = client.post("/api/v1/pages/$pageId/assets?filename=n.png") { setBody(bytes) }
                assertEquals(HttpStatusCode.Created, post.status)
                val body = Json.parseToJsonElement(post.bodyAsText()).jsonObject
                assertTrue(body["url"]!!.jsonPrimitive.content.isNotEmpty())
                assertEquals("/assets/guides/n.png", body["url"]!!.jsonPrimitive.content)

                val served = client.get("/assets/guides/n.png")
                assertEquals(HttpStatusCode.OK, served.status)
                assertContentEquals(bytes, served.bodyAsBytes())
            }
        }
    }
}
