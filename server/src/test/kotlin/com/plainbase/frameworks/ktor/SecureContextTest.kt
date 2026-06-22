package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The ADR-0008 per-request secure-context predicate. Built on [RemoteAddress] (shares the AddressParsingTest
 * edge cases). Fail-closed everywhere: only loopback, or `https` from an allowlisted proxy source, is secure.
 *
 * The credential-CONDITIONAL wiring (the gate fires when a cookie/bearer is present on a route) is A4a's
 * SecureContextWiringTest — A1 tests the predicate itself.
 */
class SecureContextTest : FunSpec({

    val cidrs = listOf("10.0.0.0/8")

    test("loopback is secure regardless of proto headers") {
        isSecureContext("127.0.0.1", emptyList(), cidrs) shouldBe true
        isSecureContext("::1", listOf("http"), cidrs) shouldBe true
    }

    test("non-loopback https from an allowlisted source is secure") {
        isSecureContext("10.1.2.3", listOf("https"), cidrs) shouldBe true
    }

    test("non-loopback https from a NON-allowlisted source is not secure (the spoof case)") {
        isSecureContext("203.0.113.7", listOf("https"), cidrs) shouldBe false
    }

    test("allowlisted source presenting http is not secure") {
        isSecureContext("10.1.2.3", listOf("http"), cidrs) shouldBe false
    }

    test("allowlisted source with a duplicate proto containing http is not secure") {
        isSecureContext("10.1.2.3", listOf("https", "http"), cidrs) shouldBe false
    }

    test("allowlisted source with a trailing/blank empty proto token is not secure (spoofer-appended blank)") {
        isSecureContext("10.1.2.3", listOf("https,"), cidrs) shouldBe false
        isSecureContext("10.1.2.3", listOf("https", ""), cidrs) shouldBe false
    }

    test("plain HTTP with no proxy config is not secure") {
        isSecureContext("203.0.113.7", emptyList(), emptyList()) shouldBe false
    }

    test("WI-9 body-credential gate verdicts: a routable plaintext peer refuses; loopback + allowlisted-https admit") {
        // The exact predicate the PUBLIC login/setup/reset body-credential routes evaluate before reading the body
        // (frameworks/ktor/routes/RouteSupport.refuseIfInsecureContext): a non-loopback plaintext request (no
        // X-Forwarded-Proto: https, no trusted proxy) is refused → 421; loopback dev and a properly-fronted proxy pass.
        isSecureContext("203.0.113.7", emptyList(), emptyList()) shouldBe false // → 421 transport_insecure, body never read
        isSecureContext("127.0.0.1", emptyList(), emptyList()) shouldBe true // loopback dev reaches the handler
        isSecureContext("10.1.2.3", listOf("https"), listOf("10.0.0.0/8")) shouldBe true // allowlisted proxy https
    }
})
