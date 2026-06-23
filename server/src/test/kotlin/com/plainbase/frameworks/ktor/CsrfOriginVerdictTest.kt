package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * B1 (cross-model review): the Origin secondary check must NOT hard-fail a legitimate proxy-fronted mutation. Behind
 * a TLS terminator the browser's `Origin` is `https://docs.example.com:443` while Ktor sees the internal `:8080` hop,
 * so the naive host:port compare false-rejected valid same-origin mutations (403 `cross_origin`). Driven through the
 * pure [CsrfGuard.originVerdict] because `testApplication` cannot present a non-loopback socket peer (the documented
 * A4a limit) — so the proxy branch is only reachable at the pure-decision layer. Origin is the SECONDARY defense; the
 * synchronizer/double-submit token is load-bearing.
 */
class CsrfOriginVerdictTest : FunSpec({

    val proxyCidrs = listOf("172.31.7.0/24")
    val proxyPeer = "172.31.7.5" // an allowlisted proxy socket peer
    val directPeer = "203.0.113.9" // a routable, non-allowlisted peer

    fun verdict(
        origin: String?,
        forwardedHost: String? = null,
        forwardedProto: List<String> = emptyList(),
        peer: String = proxyPeer,
        requestHost: String = "plainbase",
        requestPort: Int = 8080,
        cidrs: List<String> = proxyCidrs,
    ) = CsrfGuard.originVerdict(origin, forwardedHost, forwardedProto, peer, requestHost, requestPort, cidrs)

    test("an ABSENT Origin/Referer is allowed (fail-closed-WHEN-PRESENT)") {
        verdict(origin = null) shouldBe true
    }

    test("a malformed present Origin is rejected") {
        verdict(origin = "::::not a url") shouldBe false
    }

    test("behind a trusted proxy, Origin matching X-Forwarded-Host (https) is accepted port-agnostically") {
        // Browser Origin is the external :443; the internal hop is :8080 — the port difference must NOT reject it.
        verdict(origin = "https://docs.example.com", forwardedHost = "docs.example.com", forwardedProto = listOf("https")) shouldBe true
    }

    test("behind a trusted proxy, an X-Forwarded-Host carrying an explicit :443 still matches port-agnostically") {
        verdict(origin = "https://docs.example.com", forwardedHost = "docs.example.com:443", forwardedProto = listOf("https")) shouldBe true
    }

    test("behind a trusted proxy, a CROSS-origin Origin (not the X-Forwarded-Host) is rejected") {
        verdict(origin = "https://evil.example.com", forwardedHost = "docs.example.com", forwardedProto = listOf("https")) shouldBe false
    }

    test("behind a trusted proxy, an http Origin with X-Forwarded-Proto: https is a scheme mismatch → rejected") {
        verdict(origin = "http://docs.example.com", forwardedHost = "docs.example.com", forwardedProto = listOf("https")) shouldBe false
    }

    test("X-Forwarded-Host from a NON-allowlisted peer is ignored — the strict host:port compare applies") {
        // A direct client can't smuggle X-Forwarded-Host past the check: peer ∉ cidrs ⇒ strict compare, which rejects
        // the external host against the internal request host.
        verdict(
            origin = "https://docs.example.com",
            forwardedHost = "docs.example.com",
            forwardedProto = listOf("https"),
            peer = directPeer,
        ) shouldBe false
    }

    test("a direct request with a matching host:port is accepted (the original strict path)") {
        verdict(origin = "http://plainbase:8080", peer = directPeer, requestHost = "plainbase", requestPort = 8080) shouldBe true
    }

    test("a direct request with a mismatched host is rejected (cross-origin)") {
        verdict(origin = "http://evil:8080", peer = directPeer, requestHost = "plainbase", requestPort = 8080) shouldBe false
    }
})
