package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A4b WI-6/WI-7 route-level: a malformed proxy identity header (with a valid secret + transport) → 400
 * `invalid_proxy_identity` (the misconfig signal, not 401); and the spoof companion — an anonymous proxy request on a
 * gated route resolves to 401 (no Human is conjured). The loopback test-client peer counts as loopback-secure.
 */
class ProxyAuthRouteTest : FunSpec({

    val secret = "route-proxy-secret"

    fun ApplicationTestBuilder.secretHeader() = PROXY_SECRET_HEADER to secret

    // A malformed identity from a trusted proxy (valid secret + transport) is the misconfig 400. The control-char
    // case is the route-reliable one (the testApplication client coalesces duplicate request-header LINES into one
    // value, so the MULTI_VALUE verdict is proven at the pure-decision layer in ProxyIdentityExtractionTest instead).
    test("a control-char identity → 400 invalid_proxy_identity") {
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = secret) {
            val response = client.get("/api/v1/admin/tokens") {
                header("X-Forwarded-User", "a\tb")
                header(secretHeader().first, secretHeader().second)
            }
            response.status shouldBe HttpStatusCode.BadRequest
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "invalid_proxy_identity"
        }
    }

    test("an oversized identity → 400 invalid_proxy_identity") {
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = secret) {
            val response = client.get("/api/v1/admin/tokens") {
                header("X-Forwarded-User", "x".repeat(300))
                header(secretHeader().first, secretHeader().second)
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("an identity header WITHOUT the secret resolves to anonymous → 401 on a gated route (no Human conjured)") {
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = secret) {
            client.get("/api/v1/admin/tokens") { header("X-Forwarded-User", "alice") }
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
