package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * The WI-9 secure-context gate + non-blank guard for the PUBLIC pre-auth routes whose credential rides the BODY
 * (login / setup-consume / reset-consume): the credential-conditional seam ([PrincipalExtraction]) only fires when a
 * `pb_` bearer / `pb_session` cookie is PRESENT, so a body credential must be transport-gated explicitly via
 * [com.plainbase.frameworks.ktor.routes.refuseIfInsecureContext] BEFORE it is read + verified.
 *
 * The REFUSAL decision itself — `isSecureContext` false for a non-loopback plaintext request (no `X-Forwarded-Proto`,
 * no trusted proxy), true for loopback / an allowlisted-proxy-https context — is unit-tested in [SecureContextTest]
 * (the body routes call the SAME credential-agnostic predicate + the SAME socket-peer source the seam uses).
 * `testApplication` always presents a LOOPBACK socket peer (it cannot spoof a routable remote — the identical limit
 * `CookieRefusal421Test` documents), so here we prove the WIRING: over loopback the gate PASSES and the request
 * reaches the service (it is not over-blocked to 421), and a blank credential field is rejected 400 before the
 * service is ever called.
 */
class BodyCredentialGateTest : FunSpec({

    fun errorCode(body: String) =
        Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    // ---- WI-9 secure-context gate is wired (loopback → reaches the service, never an over-eager 421) -------------

    test("login over loopback passes the secure-context gate and reaches the service (401, not 421)") {
        authRouteTest { harness ->
            harness.seedUser("alice", "secret-pw", Role.EDITOR)
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"WRONG"}""")
            }
            response.status shouldBe HttpStatusCode.Unauthorized // the verify ran (gate passed); not the 421 refusal
            errorCode(response.bodyAsText()) shouldBe "invalid_credentials"
        }
    }

    test("setup/consume over loopback passes the gate and reaches the service (token_invalid, not 421)") {
        authRouteTest {
            val response = client.post("/api/v1/setup/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"pb_bogus","username":"root","password":"pw"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "setup_token_invalid"
        }
    }

    test("reset/consume over loopback passes the gate and reaches the service (token_invalid, not 421)") {
        authRouteTest {
            val response = client.post("/api/v1/password/reset/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"pb_bogus","new_password":"pw"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "setup_token_invalid"
        }
    }

    // ---- WI-9 non-blank field guard → 400 invalid_auth_request BEFORE the service (the documented contract) ------

    test("a blank login username → 400 invalid_auth_request (service not called — no credential oracle)") {
        authRouteTest { harness ->
            harness.seedUser("alice", "secret-pw", Role.EDITOR)
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"","password":"secret-pw"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "invalid_auth_request"
        }
    }

    test("a blank login password → 400 invalid_auth_request") {
        authRouteTest { harness ->
            harness.seedUser("alice", "secret-pw", Role.EDITOR)
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":""}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "invalid_auth_request"
        }
    }

    test("a blank setup/consume token → 400 invalid_auth_request (not setup_token_invalid — the body is malformed)") {
        authRouteTest {
            val response = client.post("/api/v1/setup/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"","username":"root","password":"pw"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "invalid_auth_request"
        }
    }

    test("a blank reset/consume new_password → 400 invalid_auth_request") {
        authRouteTest {
            val response = client.post("/api/v1/password/reset/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"pb_x","new_password":""}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "invalid_auth_request"
        }
    }

    test("a blank admin-create username → 400 invalid_auth_request") {
        authRouteTest(enforced = true) { harness ->
            harness.seedUser("boss", "pw", Role.ADMIN)
            val client = cookieClient()
            val login = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"boss","password":"pw"}""")
            }
            val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrf_token"]!!.jsonPrimitive.content
            val response = client.post("/api/v1/admin/users") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"","role":"editor"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "invalid_auth_request"
        }
    }
})
