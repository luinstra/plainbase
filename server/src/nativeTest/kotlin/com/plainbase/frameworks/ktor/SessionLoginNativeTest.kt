package com.plainbase.frameworks.ktor

import io.ktor.client.plugins.cookies.HttpCookies
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
 * The A4a end-to-end native gate: `POST /api/v1/login` over the real `plainbaseModule` (with the `Sessions` plugin
 * + the shared opaque-string serializer) against a seeded builtin admin sets a `pb_session` cookie, and the NEXT
 * request resolves to the human session (GET /api/v1/session authenticated=true). Proves argon2-verify +
 * SecureRandom session mint + `ktor-server-sessions`-with-the-opaque-serializer + the cookie round-trip all work
 * in the closed-world image. kotlin.test + @Tag("native") only (the `SessionCookieNativeTest` is the dependency
 * -level proof; this is the end-to-end consumer).
 */
@Tag("native")
class SessionLoginNativeTest {

    @Test
    fun `login over the real module sets a session cookie and the next request is the human session`() {
        withRestServices(seedAdmin = "alice" to "secret-pw") { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = createClient { install(HttpCookies) }

                val login = client.post("/api/v1/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"alice","password":"secret-pw"}""")
                }
                assertEquals(HttpStatusCode.OK, login.status)
                assertTrue(login.bodyAsText().contains("csrf_token"), "login should return a CSRF token")

                val session = client.get("/api/v1/session")
                assertTrue(session.bodyAsText().contains("\"authenticated\":true"), "next request should be the human session")
            }
        }
    }
}
