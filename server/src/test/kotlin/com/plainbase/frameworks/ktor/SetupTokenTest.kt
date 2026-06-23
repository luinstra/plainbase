package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
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
 * Bootstrap-token consume over HTTP (A4a WI-9/WI-13): consuming a (CLI-minted) bootstrap token via the POST body
 * creates the first admin AND logs them in (cookie set, 201); a second consume of the same token fails (single-use)
 * 400 `setup_token_invalid`; a token presented via the URL/query is NOT accepted (only the body). The SECRET comes
 * only from the harness's mint (the CLI surface); the boot path never mints (asserted in SecretHygieneTest).
 */
class SetupTokenTest : FunSpec({

    fun authOf(body: String) = Json.parseToJsonElement(body).jsonObject["authenticated"]!!.jsonPrimitive.content

    test("consuming a bootstrap token creates the first admin and logs them in") {
        authRouteTest(enforced = true) { harness ->
            val token = harness.mintBootstrapToken()
            val client = cookieClient()
            val consume = client.post("/api/v1/setup/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","username":"root","password":"strong-pw"}""")
            }
            consume.status shouldBe HttpStatusCode.Created
            // The new admin is now logged in (cookie set), so a session read is authenticated.
            authOf(client.get("/api/v1/session").bodyAsText()) shouldBe "true"
        }
    }

    test("a second consume of the same token fails — single-use") {
        authRouteTest(enforced = true) { harness ->
            val token = harness.mintBootstrapToken()
            client.post("/api/v1/setup/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","username":"root","password":"pw1"}""")
            }.status shouldBe HttpStatusCode.Created
            val second = client.post("/api/v1/setup/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","username":"other","password":"pw2"}""")
            }
            second.status shouldBe HttpStatusCode.BadRequest
            Json.parseToJsonElement(second.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "setup_token_invalid"
        }
    }

    test("a token presented via the URL/query is NOT accepted (only the body)") {
        authRouteTest(enforced = true) { harness ->
            val token = harness.mintBootstrapToken()
            // No body token — the route reads ONLY the body; a query token is ignored (malformed body → 400).
            client.post("/api/v1/setup/consume?token=$token") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"root","password":"pw"}""") // token missing from the body
            }.status shouldBe HttpStatusCode.BadRequest
        }
    }
})
