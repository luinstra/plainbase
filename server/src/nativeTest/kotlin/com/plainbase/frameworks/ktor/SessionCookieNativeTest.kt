package com.plainbase.frameworks.ktor

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionSerializer
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Native proof (§D / R1-finding-7) that `ktor-server-sessions` installs and round-trips a cookie inside the
 * closed-world image WITH the OPAQUE-STRING serializer A4a ships — the random token id is an opaque [String],
 * NOT a data class, so the install needs NO reflection serializer (the default reflection-based path is the
 * native-crash hazard this proof exists to avoid). kotlin.test + @Tag("native") only.
 */
@Tag("native")
class SessionCookieNativeTest {

    // The session payload is the random token id: an opaque String round-tripped by identity — no kotlin.reflect.
    private object OpaqueStringSerializer : SessionSerializer<String> {
        override fun serialize(session: String): String = session
        override fun deserialize(text: String): String = text
    }

    @Test
    fun `sessions install and round-trip an opaque-string cookie under native`() {
        testApplication {
            application {
                install(Sessions) {
                    cookie<String>("pb_session") {
                        cookie.httpOnly = true
                        serializer = OpaqueStringSerializer
                    }
                }
                routing {
                    get("/set") {
                        call.sessions.set("pb_session", "token-abc123")
                        call.respondText("set")
                    }
                    get("/read") {
                        call.respondText(call.sessions.get("pb_session") as? String ?: "none")
                    }
                }
            }
            val client = createClient { install(HttpCookies) }

            assertEquals(HttpStatusCode.OK, client.get("/set").status)
            assertEquals("token-abc123", client.get("/read").bodyAsText())
        }
    }
}
