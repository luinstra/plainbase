package com.plainbase.frameworks.ktor

import com.plainbase.BuildInfo
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

// Lives in the `nativeTest` source set: runs in the JVM `test` suite AND as native code via
// `nativeTest`. The root-route check validates the explicit primary-root redirect in the
// native test image. On kotlin.test (not Kotest/MockK) because only that can run in a closed-world
// native image; @Tag("native") documents the intent and keeps the smoke set greppable.
@Tag("native")
class HealthRouteTest {

    @Test
    fun `healthz returns ok with version`() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }

            val response = client.get("/healthz")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("ok", body["status"]?.jsonPrimitive?.content)
            // The SAME single source of truth the app reports (C5 item 8) — never a hardcoded literal,
            // which would break the moment a dev build self-reports `0.1.0-SNAPSHOT`.
            assertEquals(BuildInfo.VERSION, body["version"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `root redirects to the primary root`() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }

            val noRedirectClient = createClient { followRedirects = false }
            val response = noRedirectClient.get("/")

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("/docs", response.headers[HttpHeaders.Location])
        }
    }
}
