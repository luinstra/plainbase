package com.plainbase.frameworks.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
 * Native-image smoke for the PB-WRITE-1 (W2) `POST /api/v1/pages` route: the closed-world image would
 * otherwise never compile the `CreatePageRequest` JSON decode, the create's `WrittenResponse` serializer
 * encode, or the YAML-safe compose — HTTP + serialization is the classic native reflection trap (the
 * `WriteRouteNativeTest` rationale). `withRestServices` threads the hoisted single `idMap` into the
 * `WritePipeline.create`. kotlin.test + @Tag("native") only.
 */
@Tag("native")
class WriteRouteCreateNativeTest {

    private val json = ContentType.Application.Json

    private fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

    @Test
    fun `post creates a page and the created page reads back, natively`() {
        withRestServices { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = noRedirectClient()

                val post = client.post("/api/v1/pages") {
                    contentType(json)
                    setBody("""{"root":"docs","folder":"guides","title":"Native Create","body":"# n\n\nx\n"}""")
                }
                assertEquals(HttpStatusCode.Created, post.status)
                val body = Json.parseToJsonElement(post.bodyAsText()).jsonObject
                assertNotNull(body["content_hash"]?.jsonPrimitive?.content)

                // The page is reachable at the §A4 path (the create indexed it via rebuild()).
                val got = client.get("/api/v1/pages/by-path/docs/guides/native-create")
                assertEquals(HttpStatusCode.OK, got.status)
                val page = Json.parseToJsonElement(got.bodyAsText()).jsonObject
                assertEquals("guides/native-create.md", page["path"]?.jsonPrimitive?.content)
                assertEquals("Native Create", page["title"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `typed POST decodes its optional field and preserves astral metadata natively`() {
        withRestServices { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = noRedirectClient()
                val created = client.post("/api/v1/pages") {
                    contentType(json)
                    setBody(
                        """{"root":"docs","title":"Typed Native","type":"Type \uD83D\uDE00","body":"unicode \uD83D\uDE00"}""",
                    )
                }
                assertEquals(HttpStatusCode.Created, created.status)
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                val read = client.get("/api/v1/pages/$id")
                assertEquals(HttpStatusCode.OK, read.status)
                val page = Json.parseToJsonElement(read.bodyAsText()).jsonObject
                assertEquals("Type 😀", page.getValue("frontmatter").jsonObject.getValue("type").jsonPrimitive.content)
                val rejected = client.post("/api/v1/pages") {
                    contentType(json)
                    setBody("""{"root":"docs","title":"Bad Native","type":"Reference","body":"\uD800"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, rejected.status)
                assertEquals(
                    "invalid_create_request",
                    Json.parseToJsonElement(rejected.bodyAsText()).jsonObject.getValue("error").jsonObject
                        .getValue("code").jsonPrimitive.content,
                )
            }
        }
    }
}
