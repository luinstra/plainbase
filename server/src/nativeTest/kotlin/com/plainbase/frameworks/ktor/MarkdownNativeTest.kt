package com.plainbase.frameworks.ktor

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("native")
class MarkdownNativeTest {

    private val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    private val finalNewlinePageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b"
    private val markdownContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)

    private fun io.ktor.client.statement.HttpResponse.hasHeaderToken(name: String, expected: String): Boolean =
        headers.getAll(name).orEmpty()
            .flatMap { it.split(',') }
            .any { it.trim().equals(expected, ignoreCase = true) }

    @Test
    fun `Markdown preserves UTF-8 source bytes and headers in the native route`() {
        val text = "---\r\nid: $pageId\r\ntitle: Native 日本語\r\n---\r\n\r\n# Native 🦑\r\n\r\n```kotlin\r\nval x = 1\r\n```"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        val finalNewline = "---\nid: $finalNewlinePageId\ntitle: Final newline\n---\n\n# Final newline\n".toByteArray(Charsets.UTF_8)
        withRestServices(
            rawPages = mapOf("native.md" to bytes, "final-newline.md" to finalNewline),
        ) { services ->
            testApplication {
                application { plainbaseModule(services) }
                val responses = listOf(
                    client.get("/api/v1/pages/$pageId") { header(HttpHeaders.Accept, "text/markdown") } to bytes,
                    client.get("/api/v1/pages/$finalNewlinePageId") {
                        header(HttpHeaders.Accept, "text/markdown")
                    } to finalNewline,
                )
                responses.forEach { (response, expected) ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals(markdownContentType, response.contentType())
                    assertContentEquals(expected, response.bodyAsBytes())
                    assertTrue(response.hasHeaderToken(HttpHeaders.CacheControl, "no-store"))
                    assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                    assertNull(response.headers[HttpHeaders.ETag])
                }
            }
        }
    }

    @Test
    fun `Markdown uses the same replacement decode as JSON for invalid UTF-8`() {
        val bytes = "---\nid: $pageId\ntitle: Bad\n---\n\n# Bad\n\n".toByteArray() +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + " tail\n".toByteArray()
        withRestServices(
            rawPages = mapOf("bad.md" to bytes),
        ) { services ->
            testApplication {
                application { plainbaseModule(services) }
                val markdown = client.get("/api/v1/pages/$pageId") {
                    header(HttpHeaders.Accept, "text/markdown")
                }
                val json = client.get("/api/v1/pages/$pageId") {
                    header(HttpHeaders.Accept, "application/json")
                }
                val decoded = Json.parseToJsonElement(json.bodyAsText()).jsonObject
                    .getValue("markdown").jsonPrimitive.content
                assertEquals(HttpStatusCode.OK, markdown.status)
                assertContentEquals(decoded.toByteArray(Charsets.UTF_8), markdown.bodyAsBytes())
            }
        }
    }
}
