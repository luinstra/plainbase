package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A4b WI-7: the direct-client-header-spoof guarantee — presenting ONLY the identity header (with a guessed/absent
 * secret) from an untrusted transport NEVER yields a `Principal.Human`. `testApplication` cannot spoof a routable
 * socket peer (the documented A4a limit), so the spoof is exercised at the pure-decision layer where the socket peer
 * IS controllable. The key property: no trusted transport AND/OR no valid secret ⇒ never authenticated as a human.
 */
class DirectClientHeaderSpoofTest : FunSpec({

    val secret = "real-secret"

    fun decide(remoteHost: String, forwardedProto: List<String>, presentedSecrets: List<String>) =
        decidePrincipalExtraction(
            bearer = null,
            cookie = null,
            remoteHost = remoteHost,
            forwardedProtoValues = forwardedProto,
            trustedProxyCidrs = listOf("10.0.0.0/8"),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = { null },
            builtinAuthEnabled = false,
            proxyIdentityValues = listOf("admin"), // the attacker claims to be admin
            presentedProxySecrets = presentedSecrets,
            configuredProxySecret = secret,
            proxyAuthEnabled = true,
        )

    test("a forged X-Forwarded-User from a routable peer with NO secret → InsecureTransportRefused (not a Human)") {
        decide("203.0.113.9", emptyList(), emptyList())
            .shouldBeInstanceOf<PrincipalExtraction.InsecureTransportRefused>()
    }

    test("a forged header from a routable peer claiming https but outside the CIDR → InsecureTransportRefused") {
        // The peer is not in trustedProxyCidrs, so its self-asserted X-Forwarded-Proto: https is ignored (fail-closed).
        decide("203.0.113.9", listOf("https"), listOf("guess"))
            .shouldBeInstanceOf<PrincipalExtraction.InsecureTransportRefused>()
    }

    test("a peer INSIDE the CIDR + https but a WRONG secret → Resolved(Anonymous), never a Human") {
        decide("10.1.2.3", listOf("https"), listOf("guess"))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
    }

    test("the ONLY path to a proxy Human is in-CIDR + https + the correct secret") {
        decide("10.1.2.3", listOf("https"), listOf(secret))
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Human("proxy", "admin")
    }
})
