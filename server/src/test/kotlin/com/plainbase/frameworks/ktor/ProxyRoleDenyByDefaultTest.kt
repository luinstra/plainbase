package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * A4b WI-8: the proxy principal `Principal.Human("proxy", subject)` is deny-by-default. A proxy-Human with NO
 * `subject_role` row is denied a gated route (403); after `grant-role proxy <subject> admin` the same route passes.
 * The proxy header source produces the principal (WI-3); the role lookup + matrix are the already-built A3 path.
 */
class ProxyRoleDenyByDefaultTest : FunSpec({

    val secret = "deny-test-secret"

    fun ApplicationTestBuilder.proxyHeaders() = arrayOf("X-Forwarded-User" to "bob", PROXY_SECRET_HEADER to secret)

    test("a proxy-Human with NO role → 403 on a gated read; after grant admin → 200") {
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = secret) { harness ->
            // No role granted yet — the manage-gated token list denies bob (authenticated but unauthorized → 403).
            val denied = client.get("/api/v1/admin/tokens") { proxyHeaders().forEach { header(it.first, it.second) } }
            denied.status shouldBe HttpStatusCode.Forbidden

            harness.seedProxyRole("bob", Role.ADMIN)
            val allowed = client.get("/api/v1/admin/tokens") { proxyHeaders().forEach { header(it.first, it.second) } }
            allowed.status shouldBe HttpStatusCode.OK
        }
    }

    test("an anonymous proxy request (no identity header) on a gated route → 401") {
        authRouteTest(enforced = true, builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = secret) {
            client.get("/api/v1/admin/tokens").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
