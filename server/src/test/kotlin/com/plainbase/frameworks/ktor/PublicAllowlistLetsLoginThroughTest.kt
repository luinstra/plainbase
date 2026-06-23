package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
 * The debate-REQUIRED test (synthesis §"WIDEN" 6 / §6, THE SLEEPER): under ENFORCED (builtin) auth, an anonymous
 * `POST /api/v1/login` / `GET /api/v1/session` / `POST /api/v1/setup/consume` is NOT refused by the authZ choke
 * point (PolicyService denies Anonymous under enforced mode — so routing these through `check*` would make auth
 * impossible). Each reaches its OWN handler (returns its handler's status, never the `unauthorized` envelope). A
 * GATED route (PUT a page) anonymous → 401. And login is NOT an authz bypass — it touches no content mutator.
 */
class PublicAllowlistLetsLoginThroughTest : FunSpec({

    fun errorCode(body: String): String? =
        runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()

    test("anonymous login reaches the handler under enforced mode (its own 401-bad-creds, not the authZ unauthorized)") {
        authRouteTest(enforced = true) {
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"ghost","password":"pw"}""")
            }
            // The login HANDLER answers (bad creds → invalid_credentials), NOT the choke point's `unauthorized`.
            response.status shouldBe HttpStatusCode.Unauthorized
            errorCode(response.bodyAsText()) shouldBe "invalid_credentials"
        }
    }

    test("anonymous GET /api/v1/session reaches the handler (authenticated=false), never a 401 from check*") {
        authRouteTest(enforced = true) {
            val response = client.get("/api/v1/session")
            response.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["authenticated"]!!.jsonPrimitive.content shouldBe "false"
        }
    }

    test("anonymous setup/consume reaches the handler (bad token → setup_token_invalid), not the authZ unauthorized") {
        authRouteTest(enforced = true) {
            val response = client.post("/api/v1/setup/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"nope","username":"alice","password":"pw"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            errorCode(response.bodyAsText()) shouldBe "setup_token_invalid"
        }
    }

    test("a GATED route (PUT a page) anonymous → 401 unauthorized") {
        authRouteTest(enforced = true) { harness ->
            val (id, hash) = harness.seedPage("doc.md", "---\ntitle: Doc\n---\n\nbody.\n")
            val response = client.put("/api/v1/pages/$id") {
                contentType(ContentType.parse("text/markdown"))
                header(HttpHeaders.IfMatch, "\"$hash\"")
                setBody("---\nid: $id\ntitle: Doc\n---\n\nx.\n")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
            errorCode(response.bodyAsText()) shouldBe "unauthorized"
        }
    }

    // WI-7: in OFF/PROXY the builtin LOGIN/SETUP/USER-CRUD surface is NOT registered — a leftover builtin login path
    // must be ABSENT (404), never a live bypass of the proxy/off identity. A4b WIDENS this: `/session` is now PUBLIC
    // in proxy mode (the CSRF-bootstrap read, 200 for an anonymous proxy request), while login/setup/admin-users STAY
    // 404. The §A4 routing matrix's api-fallback answers an unknown /api path with a 404 envelope.
    test("under proxy mode: login/setup/admin-users STAY 404, but /session is PUBLIC (200, authenticated=false)") {
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = "s") {
            val login = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"ghost","password":"pw"}""")
            }
            login.status shouldBe HttpStatusCode.NotFound

            val setup = client.post("/api/v1/setup/consume") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"nope","username":"alice","password":"pw"}""")
            }
            setup.status shouldBe HttpStatusCode.NotFound

            // The builtin USER-CRUD surface stays ABSENT (404) in proxy mode (user CRUD is builtin-only, locked pt 3).
            val adminUsers = client.get("/api/v1/admin/users")
            adminUsers.status shouldBe HttpStatusCode.NotFound

            // A4b WIDEN: /session is PUBLIC in proxy mode — an anonymous proxy request gets 200 authenticated=false,
            // with authMode=proxy so the SPA hides builtin-only panels.
            val session = client.get("/api/v1/session")
            session.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(session.bodyAsText()).jsonObject
            body["authenticated"]!!.jsonPrimitive.content shouldBe "false"
            body["auth_mode"]!!.jsonPrimitive.content shouldBe "proxy"
        }
    }
})
