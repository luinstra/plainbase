package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
 * A4b WI-5: proxy-mode CSRF. The proxy `GET /session` issues a `pb_proxy_csrf` cookie + the matching `csrf_token`;
 * a proxy-Human mutation with the matching double-submit passes; missing/wrong `X-CSRF-Token` → 403 `csrf_failed`; a
 * cross-site forged mutation (attacker can't read the cookie, so can't echo it) → 403; a `pb_` bearer mutation is
 * STILL CSRF-exempt in proxy mode; and the widened `/session`-in-proxy 200 + `auth_mode` assertion. The loopback
 * test-client peer (127.0.0.1) counts as loopback-secure, so the harness presents the identity + secret headers.
 */
class ProxyCsrfRouteTest : FunSpec({

    val secret = "harness-proxy-secret"

    /** A proxy harness with `bob` granted ADMIN as a proxy identity (so token mint — a manage mutation — is allowed). */
    fun proxyTest(block: suspend ApplicationTestBuilder.(AuthRouteHarness) -> Unit) =
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = secret) { harness ->
            harness.seedProxyRole("bob", Role.ADMIN)
            block(harness)
        }

    /** A cookie client (holds pb_proxy_csrf across requests); the proxy identity + secret headers are set per call. */
    fun ApplicationTestBuilder.proxyClient(): HttpClient = createClient { install(HttpCookies) }

    fun proxyHeaders() = arrayOf("X-Forwarded-User" to "bob", PROXY_SECRET_HEADER to secret)

    test("a proxy-Human GET /session issues a token + a pb_proxy_csrf cookie; auth_mode=proxy, authenticated=true") {
        proxyTest {
            val client = proxyClient()
            val response = client.get("/api/v1/session") { proxyHeaders().forEach { header(it.first, it.second) } }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["authenticated"]!!.jsonPrimitive.content shouldBe "true"
            body["auth_mode"]!!.jsonPrimitive.content shouldBe "proxy"
            body["username"]!!.jsonPrimitive.content shouldBe "bob"
            (body["csrf_token"]!!.jsonPrimitive.content.isNotBlank()) shouldBe true
        }
    }

    test("a proxy-Human mutation with the matching double-submit passes CSRF") {
        proxyTest {
            val client = proxyClient()
            val session = client.get("/api/v1/session") { proxyHeaders().forEach { header(it.first, it.second) } }
            val token = Json.parseToJsonElement(session.bodyAsText()).jsonObject["csrf_token"]!!.jsonPrimitive.content
            // The HttpCookies plugin already holds pb_proxy_csrf; echo the token as the X-CSRF-Token header.
            val mint = client.post("/api/v1/admin/tokens") {
                proxyHeaders().forEach { header(it.first, it.second) }
                header("X-CSRF-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"label":"ci","mode":"read-only"}""")
            }
            mint.status shouldBe HttpStatusCode.Created
        }
    }

    test("a proxy-Human mutation WITHOUT an X-CSRF-Token → 403 csrf_failed") {
        proxyTest {
            val client = proxyClient()
            client.get("/api/v1/session") { proxyHeaders().forEach { header(it.first, it.second) } } // sets the cookie
            val mint = client.post("/api/v1/admin/tokens") {
                proxyHeaders().forEach { header(it.first, it.second) }
                contentType(ContentType.Application.Json)
                setBody("""{"label":"ci","mode":"read-only"}""")
            }
            mint.status shouldBe HttpStatusCode.Forbidden
            Json.parseToJsonElement(mint.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "csrf_failed"
        }
    }

    test("a proxy-Human mutation with a WRONG X-CSRF-Token → 403") {
        proxyTest {
            val client = proxyClient()
            client.get("/api/v1/session") { proxyHeaders().forEach { header(it.first, it.second) } }
            val mint = client.post("/api/v1/admin/tokens") {
                proxyHeaders().forEach { header(it.first, it.second) }
                header("X-CSRF-Token", "bm90LXRoZS1yaWdodC10b2tlbg")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"ci","mode":"read-only"}""")
            }
            mint.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("a cross-site forged mutation (no pb_proxy_csrf cookie to echo) → 403") {
        proxyTest {
            // A fresh client with NO cookie jar: an attacker can't read the victim's pb_proxy_csrf, so the double-submit
            // EQUALITY fails — the header has no matching cookie, regardless of token validity. (This proves cookie
            // ABSENCE blocks the forge; HMAC key-unforgeability of a self-minted token is proven unit-side in ProxyCsrfTest.)
            val attacker = createClient { }
            val forgedToken = AuthRouteHarness().use { it.issueProxyCsrf() } // a token NOT set as this client's cookie
            val mint = attacker.post("/api/v1/admin/tokens") {
                proxyHeaders().forEach { header(it.first, it.second) }
                header("X-CSRF-Token", forgedToken)
                contentType(ContentType.Application.Json)
                setBody("""{"label":"ci","mode":"read-only"}""")
            }
            mint.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("a pb_ bearer mutation in proxy mode is STILL CSRF-exempt (regression)") {
        proxyTest { harness ->
            val token = harness.mintAgentToken(AgentMode.COMMIT)
            // A bearer carries no ambient cookie — it cannot be CSRF'd, so NO X-CSRF-Token is needed. The token list
            // read is a GET, so use a page PUT to prove a MUTATION is exempt; seed a page + grant the agent EDITOR.
            val (id, hash) = harness.seedPage("doc.md", "---\ntitle: Doc\n---\n\nbody.\n")
            val response = client.put("/api/v1/pages/$id") {
                header("Authorization", "Bearer $token")
                header("If-Match", "\"$hash\"")
                contentType(ContentType.parse("text/markdown"))
                setBody("---\nid: $id\ntitle: Doc\n---\n\nx.\n")
            }
            // COMMIT maps to EDITOR → the PUT is authorized AND CSRF-exempt (no 403 csrf_failed).
            (response.status != HttpStatusCode.Forbidden) shouldBe true
        }
    }
})
