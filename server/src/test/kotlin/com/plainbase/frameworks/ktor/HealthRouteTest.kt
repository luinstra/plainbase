package com.plainbase.frameworks.ktor

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRouteTest {

    @Test
    fun `healthz returns ok with version`() = testApplication {
        application { plainbaseModule() }

        val response = client.get("/healthz")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals("0.1.0", body["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `spa shell is served at root`() = testApplication {
        application { plainbaseModule() }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertEquals(true, html.contains("<div id=\"root\">"), "expected SPA shell HTML, got: $html")
    }
}
