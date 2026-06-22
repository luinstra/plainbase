package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A4a login + the cookie round-trip (WI-9): correct creds → a `pb_session` cookie + a CSRF token, and the NEXT
 * request resolves to the human session (GET /api/v1/session authenticated=true); wrong/disabled → 401 with NO
 * cookie; the cookie is HttpOnly + SameSite=Lax (Secure mirrors the bind — false on loopback); the cookie payload
 * is the opaque token (not a serialized data class).
 */
class LoginFlowTest : FunSpec({

    test("correct credentials set a pb_session cookie + return a CSRF token; the next request is the human session") {
        authRouteTest { harness ->
            harness.seedUser("alice", "secret-pw", Role.EDITOR)
            val client = cookieClient()
            val login = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"secret-pw"}""")
            }
            login.status shouldBe HttpStatusCode.OK
            val setCookie = login.headers[HttpHeaders.SetCookie] ?: error("no Set-Cookie")
            setCookie shouldContain "pb_session="
            setCookie shouldContain "HttpOnly"
            setCookie shouldContain "SameSite=Lax"
            (setCookie.contains("Secure")) shouldBe false // loopback test → Secure off
            Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrf_token"]!!.jsonPrimitive.content.isNotEmpty() shouldBe true

            val session = client.get("/api/v1/session")
            val body = Json.parseToJsonElement(session.bodyAsText()).jsonObject
            body["authenticated"]!!.jsonPrimitive.content shouldBe "true"
        }
    }

    test("a wrong password → 401 invalid_credentials with no cookie") {
        authRouteTest { harness ->
            harness.seedUser("alice", "secret-pw", Role.EDITOR)
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"WRONG"}""")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
            (response.headers[HttpHeaders.SetCookie]?.contains("pb_session=") ?: false) shouldBe false
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "invalid_credentials"
        }
    }

    test("a disabled user → the SAME 401 invalid_credentials (not an oracle)") {
        authRouteTest { harness ->
            harness.seedUser("alice", "secret-pw", Role.EDITOR, disabled = true)
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"secret-pw"}""")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "invalid_credentials"
        }
    }

    test("a malformed login body → 400 invalid_auth_request, never 500") {
        authRouteTest {
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""") // missing password
            }
            response.status shouldBe HttpStatusCode.BadRequest
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "invalid_auth_request"
        }
    }
})
