package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A4b WI-10: the `manage`-gated, mode-INDEPENDENT admin token/audit/role surface. Mint → list (no secret in the list)
 * → revoke; a non-admin → 403; the audit read returns recent rows; role list/grant. The surface is reachable under
 * BOTH a builtin and a proxy harness. These use a FIXED admin principal (CSRF-exempt, like any test principal); the
 * proxy CSRF double-submit on these same routes is exercised in [ProxyCsrfRouteTest].
 */
class AdminTokenRoutesTest : FunSpec({

    val admin = fixedPrincipal(Principal.Human("builtin", "admin-id"))
    val viewer = fixedPrincipal(Principal.Human("builtin", "viewer-id"))

    test("mint → list (no secret in the list) → revoke") {
        authRouteTest(enforced = true, extract = admin) { harness ->
            harness.grantRole("builtin", "admin-id", Role.ADMIN)
            val mint = client.post("/api/v1/admin/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"label":"ci","mode":"commit"}""")
            }
            mint.status shouldBe HttpStatusCode.Created
            val created = Json.parseToJsonElement(mint.bodyAsText()).jsonObject
            val id = created["id"]!!.jsonPrimitive.content
            val plaintext = created["plaintext"]!!.jsonPrimitive.content
            (plaintext.startsWith("pb_")) shouldBe true

            val list = client.get("/api/v1/admin/tokens")
            list.status shouldBe HttpStatusCode.OK
            val listBody = list.bodyAsText()
            listBody shouldContain id
            (listBody.contains(plaintext)) shouldBe false // the one-time plaintext never rides the list
            (listBody.contains("secret")) shouldBe false

            client.post("/api/v1/admin/tokens/$id/revoke").status shouldBe HttpStatusCode.NoContent
        }
    }

    test("a non-admin (viewer) → 403 on mint") {
        authRouteTest(enforced = true, extract = viewer) { harness ->
            harness.grantRole("builtin", "viewer-id", Role.VIEWER)
            val mint = client.post("/api/v1/admin/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"label":"ci","mode":"commit"}""")
            }
            mint.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("the audit read returns recent decision rows") {
        authRouteTest(enforced = true, extract = admin) { harness ->
            harness.grantRole("builtin", "admin-id", Role.ADMIN)
            // A mint records a MANAGE decision row; the audit read surfaces it.
            client.post("/api/v1/admin/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"label":"ci","mode":"read-only"}""")
            }
            val audit = client.get("/api/v1/admin/audit?limit=10")
            audit.status shouldBe HttpStatusCode.OK
            (Json.parseToJsonElement(audit.bodyAsText()).jsonObject["entries"]!!.jsonArray.isNotEmpty()) shouldBe true
        }
    }

    test("role list + grant") {
        authRouteTest(enforced = true, extract = admin) { harness ->
            harness.grantRole("builtin", "admin-id", Role.ADMIN)
            client.post("/api/v1/admin/roles") {
                contentType(ContentType.Application.Json)
                setBody("""{"issuer":"proxy","external_id":"carol","role":"editor"}""")
            }.status shouldBe HttpStatusCode.NoContent

            val roles = client.get("/api/v1/admin/roles")
            roles.status shouldBe HttpStatusCode.OK
            roles.bodyAsText() shouldContain "carol"
        }
    }

    test("the token surface is reachable under a PROXY harness too (mode-independent)") {
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = "s", extract = admin) { harness ->
            harness.grantRole("builtin", "admin-id", Role.ADMIN)
            client.get("/api/v1/admin/tokens").status shouldBe HttpStatusCode.OK
        }
    }
})
