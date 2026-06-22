package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session lifecycle over the HTTP surface (A4a WI-9): fixation (login mints a FRESH session — an inbound
 * attacker-supplied cookie does NOT survive login), and revocation (logout → next request anonymous; a password
 * change → the session is revoked).
 */
class SessionFlowTest : FunSpec({

    fun csrfOf(body: String) = Json.parseToJsonElement(body).jsonObject["csrf_token"]!!.jsonPrimitive.content
    fun authOf(body: String) = Json.parseToJsonElement(body).jsonObject["authenticated"]!!.jsonPrimitive.content

    test("login mints a FRESH session; an inbound attacker cookie does not survive (fixation)") {
        authRouteTest { harness ->
            harness.seedUser("alice", "pw", Role.EDITOR)
            val client = cookieClient()
            // Present a forged pb_session cookie, then log in: the post-login principal is bound to the NEW minted
            // session, never the attacker's value (SessionService.create never adopts an inbound id).
            val login = client.post("/api/v1/login") {
                header("Cookie", "pb_session=attacker-chosen-value")
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"pw"}""")
            }
            login.status shouldBe HttpStatusCode.OK
            // The attacker's value never authenticates (the session row is keyed by the minted token's hash).
            val attacker = createClient { }
            attacker.get("/api/v1/session") { header("Cookie", "pb_session=attacker-chosen-value") }
                .let { authOf(it.bodyAsText()) } shouldBe "false"
            // The real logged-in client IS authenticated.
            authOf(client.get("/api/v1/session").bodyAsText()) shouldBe "true"
        }
    }

    test("logout revokes the session; the next request is anonymous") {
        authRouteTest { harness ->
            harness.seedUser("alice", "pw", Role.EDITOR)
            val client = cookieClient()
            val csrf = csrfOf(
                client.post("/api/v1/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"alice","password":"pw"}""")
                }.bodyAsText(),
            )
            client.post("/api/v1/logout") { header("X-CSRF-Token", csrf) }.status shouldBe HttpStatusCode.NoContent
            authOf(client.get("/api/v1/session").bodyAsText()) shouldBe "false"
        }
    }

    test("a password change revokes the session (force re-login)") {
        authRouteTest { harness ->
            harness.seedUser("alice", "old-pw", Role.EDITOR)
            val client = cookieClient()
            val csrf = csrfOf(
                client.post("/api/v1/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"alice","password":"old-pw"}""")
                }.bodyAsText(),
            )
            client.post("/api/v1/password/change") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"current_password":"old-pw","new_password":"new-pw"}""")
            }.status shouldBe HttpStatusCode.NoContent
            // All sessions (incl. this one) are revoked.
            authOf(client.get("/api/v1/session").bodyAsText()) shouldBe "false"
        }
    }
})
