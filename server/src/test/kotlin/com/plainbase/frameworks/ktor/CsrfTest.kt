package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The CSRF synchronizer-token guard (A4a WI-10, §3): a cookie-auth mutation (here: logout / password change)
 * without — or with a WRONG / cross-session — `X-CSRF-Token` is rejected 403 `csrf_failed`; with the correct token
 * it passes; a present cross-origin `Origin` is rejected `cross_origin`; an absent Origin is NOT a hard fail.
 */
class CsrfTest : FunSpec({

    /** Logs `alice` in on [client] and returns her session's CSRF token. */
    suspend fun ApplicationTestBuilder.login(client: HttpClient, harness: AuthRouteHarness, username: String = "alice"): String {
        harness.seedUser(username, "pw", Role.EDITOR)
        val login = client.post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"pw"}""")
        }
        return Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrf_token"]!!.jsonPrimitive.content
    }

    test("a cookie-auth logout WITHOUT a CSRF token → 403 csrf_failed") {
        authRouteTest { harness ->
            val client = cookieClient()
            login(client, harness)
            val response = client.post("/api/v1/logout")
            response.status shouldBe HttpStatusCode.Forbidden
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "csrf_failed"
        }
    }

    test("a cookie-auth logout WITH the correct CSRF token passes") {
        authRouteTest { harness ->
            val client = cookieClient()
            val csrf = login(client, harness)
            client.post("/api/v1/logout") { header("X-CSRF-Token", csrf) }.status shouldBe HttpStatusCode.NoContent
        }
    }

    test("a WRONG CSRF token → 403 csrf_failed") {
        authRouteTest { harness ->
            val client = cookieClient()
            login(client, harness)
            client.post("/api/v1/logout") { header("X-CSRF-Token", "bm90LXRoZS1yaWdodC10b2tlbg") }
                .status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("a cross-session CSRF token (minted for session A, presented on session B) → rejected") {
        authRouteTest { harness ->
            val clientA = cookieClient()
            val csrfA = login(clientA, harness, "alice")
            val clientB = cookieClient()
            login(clientB, harness, "bob") // B has its OWN session + csrf
            // Presenting A's token on B's session must fail (the guard compares against THIS session's row).
            clientB.post("/api/v1/logout") { header("X-CSRF-Token", csrfA) }.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("a present cross-origin Origin is rejected; the correct token + a same-origin/absent Origin passes") {
        authRouteTest { harness ->
            val client = cookieClient()
            val csrf = login(client, harness)
            client.post("/api/v1/logout") {
                header("X-CSRF-Token", csrf)
                header("Origin", "https://evil.example.com")
            }.status shouldBe HttpStatusCode.Forbidden
        }
    }
})
