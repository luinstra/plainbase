package com.plainbase.frameworks.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * The at-rest scheme (A2 WI 2): raw SHA-256 (frozen golden over a known secret — deterministic, safe) + a
 * constant-time verify that ALSO runs on the unknown-id path (a null stored hash → the dummy-compare, returns
 * false without throwing). The structural "isEqual called once on both paths" proof rides
 * `TokenAntiEnumerationTest` (the HTTP boundary), not a wall-clock measurement.
 */
class TokenHasherTest : FunSpec({

    val hasher = TokenHasher()

    test("SHA-256 of a known secret matches the frozen golden digest") {
        // Frozen: the raw 32-byte SHA-256 of these exact bytes (deterministic, safe to freeze exactly).
        val secret = "plainbase-token-secret".toByteArray(Charsets.UTF_8)
        val digest = hasher.hash(secret)
        digest.size shouldBe 32
        digest.toHexString() shouldBe "1ee8191c8a9ff6132401074a4dca761781665beebdae057548c4b0be4d50898a"
    }

    test("verify accepts the correct secret and rejects a wrong one") {
        val secret = "the-secret".toByteArray()
        val stored = hasher.hash(secret)
        hasher.verify(secret, stored).shouldBeTrue()
        hasher.verify("wrong".toByteArray(), stored).shouldBeFalse()
    }

    test("verify against a null stored hash (unknown id) returns false and still executes the dummy compare") {
        hasher.verify("anything".toByteArray(), null).shouldBeFalse()
    }

    test("a stored hash of the wrong length verifies false via the dummy compare (no length oracle)") {
        // schema is BLOB NOT NULL with no length guard; a malformed/imported row must not reintroduce a
        // length-sensitive compare. Both a too-short and a too-long stored hash normalize to the 32-byte dummy.
        hasher.verify("anything".toByteArray(), ByteArray(16)).shouldBeFalse()
        hasher.verify("anything".toByteArray(), ByteArray(64)).shouldBeFalse()
        // Even when the wrong-length stored bytes are a PREFIX of the real digest, the compare stays false.
        val real = hasher.hash("the-secret".toByteArray())
        hasher.verify("the-secret".toByteArray(), real.copyOf(16)).shouldBeFalse()
    }
})
