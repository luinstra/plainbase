package com.plainbase.frameworks.ktor

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §0.12 GATING proof: the A4a auth DTOs round-trip through the REAL routes over the scoped `RestJson` (manual
 * encode/decode — NOT Ktor content-negotiation) in the closed-world image, so the absence of a `reflect-config`
 * triple is PROVEN safe. A `LoginRequest` (decode) + a `LoginResponse`/`SessionResponse` (encode) survive the
 * native image; a malformed body maps to 400 (the route's contract), never a reflection crash. If this test ever
 * fails with a reflection error, that is the signal a DTO is reached via content-negotiation and needs its triple.
 * kotlin.test + @Tag("native") only.
 */
@Tag("native")
class AuthDtoContentNegotiationNativeTest {

    @Test
    fun `login + session DTOs round-trip through RestJson natively with no reflect-config triple`() {
        withRestServices(seedAdmin = "alice" to "secret-pw") { services ->
            testApplication {
                application { plainbaseModule(services) }

                // Decode a LoginRequest + encode a LoginResponse through the real route, natively.
                val login = client.post("/api/v1/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"alice","password":"secret-pw"}""")
                }
                assertEquals(HttpStatusCode.OK, login.status)
                assertTrue(login.bodyAsText().contains("csrf_token"))

                // Encode a SessionResponse (anonymous read — no cookie on this client).
                val session = client.get("/api/v1/session")
                assertEquals(HttpStatusCode.OK, session.status)
                assertTrue(session.bodyAsText().contains("\"authenticated\":false"))

                // A malformed body maps to 400 invalid_auth_request, never a 500/reflection crash.
                val bad = client.post("/api/v1/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"alice"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, bad.status)
            }
        }
    }
}
