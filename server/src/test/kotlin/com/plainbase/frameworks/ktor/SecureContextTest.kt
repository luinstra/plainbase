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
})
