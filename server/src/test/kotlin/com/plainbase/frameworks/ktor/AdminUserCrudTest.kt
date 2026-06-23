package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
 * Admin user CRUD (A4a WI-9), GATED through the `checkManage`-gated AdminFacade: an Admin creates a user (+ a role
 * grant + a one-time reset token), lists, disables, and reset-issues; a non-Admin → 403; no list/create response
 * carries a secret. The admin acts via a cookie session, so each mutation carries the session CSRF token.
 */
class AdminUserCrudTest : FunSpec({

    /** Logs a seeded [role] user in and returns (client, csrf). */
    suspend fun ApplicationTestBuilder.loginAs(harness: AuthRouteHarness, username: String, role: Role): Pair<HttpClient, String> {
        harness.seedUser(username, "pw", role)
        val client = cookieClient()
        val login = client.post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"pw"}""")
        }
        return client to Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrf_token"]!!.jsonPrimitive.content
    }

    test("an Admin creates a user (with a role + a one-time reset token), lists, disables, and resets") {
        authRouteTest(enforced = true) { harness ->
            val (admin, csrf) = loginAs(harness, "boss", Role.ADMIN)

            val created = admin.post("/api/v1/admin/users") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"newbie","display_name":"New B","role":"editor"}""")
            }
            created.status shouldBe HttpStatusCode.Created
            val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val newId = createdBody["id"]!!.jsonPrimitive.content
            createdBody["reset_token"]!!.jsonPrimitive.content.isNotEmpty() shouldBe true

            val list = admin.get("/api/v1/admin/users")
            list.status shouldBe HttpStatusCode.OK
            val listBody = list.bodyAsText()
            listBody.shouldNotContain("password")
            listBody.shouldNotContain("hash")

            admin.post("/api/v1/admin/users/$newId/disable") { header("X-CSRF-Token", csrf) }.status shouldBe HttpStatusCode.NoContent
            admin.post("/api/v1/admin/users/$newId/reset") { header("X-CSRF-Token", csrf) }.status shouldBe HttpStatusCode.OK
        }
    }

    test("a non-Admin (editor) → 403 on admin user CRUD") {
        authRouteTest(enforced = true) { harness ->
            val (editor, csrf) = loginAs(harness, "ed", Role.EDITOR)
            editor.post("/api/v1/admin/users") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"x","role":"editor"}""")
            }.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("creating a user with a taken username → 409 username_exists") {
        authRouteTest(enforced = true) { harness ->
            val (admin, csrf) = loginAs(harness, "boss", Role.ADMIN)
            harness.seedUser("taken", "pw", Role.VIEWER)
            admin.post("/api/v1/admin/users") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"taken","role":"editor"}""")
            }.status shouldBe HttpStatusCode.Conflict
        }
    }
})
