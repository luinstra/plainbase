package com.plainbase.frameworks.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A4b WI-4: the stateless proxy-CSRF double-submit token. `issue()`'d tokens validate against themselves (cookie ==
 * header) AND re-derive their HMAC tail; a tampered token, a cookie≠header mismatch, a missing/blank either side, and
 * a malformed-base64 value all fail (the last a 403, never an unhandled 500); a token minted under key A fails under
 * key B (unforgeability without server-side storage).
 */
class ProxyCsrfTest : FunSpec({

    val keyA = ByteArray(32) { it.toByte() }
    val keyB = ByteArray(32) { (it + 1).toByte() }
    val csrf = ProxyCsrf(keyA)

    test("an issued token validates against itself (cookie == header, HMAC re-derives)") {
        val token = csrf.issue()
        csrf.validate(token, token) shouldBe true
    }

    test("cookie ≠ header → false (double-submit equality)") {
        csrf.validate(csrf.issue(), csrf.issue()) shouldBe false
    }

    test("a missing cookie OR a missing header → false") {
        val token = csrf.issue()
        csrf.validate(null, token) shouldBe false
        csrf.validate(token, null) shouldBe false
        csrf.validate(null, null) shouldBe false
    }

    test("a blank cookie/header → false") {
        val token = csrf.issue()
        csrf.validate("", token) shouldBe false
        csrf.validate(token, "   ") shouldBe false
    }

    test("a malformed-base64 value → false, never an unhandled exception (MINOR fold-in)") {
        csrf.validate("not valid base64 !!!", "not valid base64 !!!") shouldBe false
        // A well-formed base64 of the WRONG length (decodes, but not nonce+mac) also fails cleanly.
        val shortButValid = ProxyCsrf(keyA).let { "AAAA" }
        csrf.validate(shortButValid, shortButValid) shouldBe false
    }

    test("a tampered token (flipped nonce char) → false (HMAC tail no longer re-derives)") {
        val token = csrf.issue()
        // Flip a char near the START (the nonce region) so a byte definitely changes; equality still holds on both
        // sides (cookie == header), so ONLY the HMAC re-derivation can reject it.
        val flipped = if (token[0] == 'A') 'B' else 'A'
        val tampered = flipped + token.substring(1)
        csrf.validate(tampered, tampered) shouldBe false
    }

    test("a token issued under key A fails under key B (unforgeable without the server key)") {
        val token = ProxyCsrf(keyA).issue()
        ProxyCsrf(keyB).validate(token, token) shouldBe false
    }
})
