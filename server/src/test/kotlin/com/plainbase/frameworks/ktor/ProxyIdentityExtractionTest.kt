package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A4b WI-3: the proxy identity trust gate, driven directly through the pure `decidePrincipalExtraction`. The locked
 * verdicts:
 *  - secure context + right secret + one clean identity → Human("proxy", value), Source.PROXY;
 *  - a forged header from a non-allowlisted peer / no `X-Forwarded-Proto: https` → InsecureTransportRefused (the gate
 *    fails BEFORE the secret/header are read);
 *  - the right transport but a WRONG/MISSING/DUPLICATE secret → Resolved(Anonymous) (a bad secret is "no identity",
 *    never a 400) — constant-time, no early-out;
 *  - the right secret but a multi-value/blank/control/oversized identity → ProxyIdentityRejected (→ 400 misconfig);
 *  - a bearer in proxy mode still authenticates (bearer-wins).
 */
class ProxyIdentityExtractionTest : FunSpec({

    val secret = "the-shared-secret"

    fun decide(
        identityValues: List<String>,
        presentedSecrets: List<String>,
        remoteHost: String = "10.1.2.3",
        forwardedProto: List<String> = listOf("https"),
        cidrs: List<String> = listOf("10.0.0.0/8"),
        bearer: String? = null,
        authenticateBearer: (String) -> Principal = { Principal.Anonymous },
    ) = decidePrincipalExtraction(
        bearer = bearer,
        cookie = null,
        remoteHost = remoteHost,
        forwardedProtoValues = forwardedProto,
        trustedProxyCidrs = cidrs,
        authenticateBearer = authenticateBearer,
        authenticateCookie = { null },
        builtinAuthEnabled = false,
        proxyIdentityValues = identityValues,
        presentedProxySecrets = presentedSecrets,
        configuredProxySecret = secret,
        proxyAuthEnabled = true,
    )

    test("secure context + right secret + a clean identity → Human(proxy, value), Source.PROXY") {
        val resolved = decide(listOf("alice@idp"), listOf(secret)).shouldBeInstanceOf<PrincipalExtraction.Resolved>()
        resolved.principal shouldBe Principal.Human("proxy", "alice@idp")
        resolved.source shouldBe Source.PROXY
        (resolved.csrfToken == null) shouldBe true // the proxy double-submit token is route-issued, not seam-derived
    }

    test("the identity value is trimmed") {
        decide(listOf("  bob  "), listOf(secret))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Human("proxy", "bob")
    }

    test("a forged identity from a NON-allowlisted peer → InsecureTransportRefused (gate fails before secret/header)") {
        decide(listOf("attacker"), listOf(secret), remoteHost = "203.0.113.9", cidrs = listOf("10.0.0.0/8"))
            .shouldBeInstanceOf<PrincipalExtraction.InsecureTransportRefused>()
    }

    test("an in-CIDR peer without X-Forwarded-Proto: https → InsecureTransportRefused (gate fails before secret)") {
        decide(listOf("alice"), listOf(secret), forwardedProto = emptyList())
            .shouldBeInstanceOf<PrincipalExtraction.InsecureTransportRefused>()
    }

    test("a WRONG secret over a trusted transport → Resolved(Anonymous), never a Human, never a 400") {
        decide(listOf("alice"), listOf("wrong-secret"))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
    }

    test("a MISSING secret over a trusted transport → Resolved(Anonymous) (constant-time, no early-out)") {
        decide(listOf("alice"), emptyList())
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
    }

    test("an odd-length secret → Resolved(Anonymous) (fixed-length digest compare, no length leak)") {
        decide(listOf("alice"), listOf("x"))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
    }

    test("a DUPLICATE secret header → not authenticated, even when one copy is correct (BLOCKING-1)") {
        decide(listOf("alice"), listOf(secret, secret))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
        decide(listOf("alice"), listOf(secret, "garbage"))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
    }

    test("a multi-value/duplicate identity header (with the right secret) → ProxyIdentityRejected(MULTI_VALUE)") {
        decide(listOf("alice", "mallory"), listOf(secret))
            .shouldBeInstanceOf<PrincipalExtraction.ProxyIdentityRejected>().reason shouldBe ProxyRejectReason.MULTI_VALUE
    }

    test("a blank identity header (right secret) → ProxyIdentityRejected(BLANK)") {
        decide(listOf("   "), listOf(secret))
            .shouldBeInstanceOf<PrincipalExtraction.ProxyIdentityRejected>().reason shouldBe ProxyRejectReason.BLANK
    }

    test("a control-char identity (right secret) → ProxyIdentityRejected(CONTROL_CHARS)") {
        // Build the NUL control char with `Char(0)` rather than a literal NUL byte in the source (which makes git
        // treat the file as binary — `git diff` shows `-  -`, breaking review/blame). Same runtime control char.
        decide(listOf("a" + Char(0) + "b"), listOf(secret))
            .shouldBeInstanceOf<PrincipalExtraction.ProxyIdentityRejected>().reason shouldBe ProxyRejectReason.CONTROL_CHARS
    }

    test("an oversized identity (right secret) → ProxyIdentityRejected(OVERSIZED)") {
        decide(listOf("x".repeat(257)), listOf(secret))
            .shouldBeInstanceOf<PrincipalExtraction.ProxyIdentityRejected>().reason shouldBe ProxyRejectReason.OVERSIZED
    }

    test("a 256-char identity is the boundary — accepted, not OVERSIZED") {
        decide(listOf("x".repeat(256)), listOf(secret))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Human("proxy", "x".repeat(256))
    }

    test("a bearer in proxy mode still authenticates (bearer-wins, unchanged)") {
        decide(
            identityValues = listOf("alice"),
            presentedSecrets = listOf(secret),
            bearer = "pb_abc",
            authenticateBearer = { Principal.Agent("token-id") },
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Agent("token-id")
    }

    test("no credentials at all in proxy mode → Resolved(Anonymous) (the gate does not fire)") {
        decide(identityValues = emptyList(), presentedSecrets = emptyList(), remoteHost = "203.0.113.9")
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
    }
})
