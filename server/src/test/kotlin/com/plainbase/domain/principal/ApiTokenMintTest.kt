package com.plainbase.domain.principal

import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

/**
 * The `pb_<id>_<secret>` format + its round-trip (A2 WI 1): mint emits a base64url token, [parseToken]
 * recovers the id + a secret whose SHA-256 equals the persisted hash, two mints differ (SecureRandom), and
 * structurally invalid bearers are rejected (null), not thrown — mirroring the Argon2 hasher's
 * "structurally invalid encodings are rejected" table.
 */
class ApiTokenMintTest : FunSpec({

    val hasher = TokenHasher()
    val minter = ApiTokenMinter(hasher)

    test("mint emits a pb_<16-hex-id>_<43-char-base64url-secret> token") {
        minter.mint().plaintext shouldMatch Regex("^pb_[0-9a-f]{16}_[A-Za-z0-9_-]{43}$")
    }

    test("parse round-trips the id and a secret whose SHA-256 is the stored hash") {
        val minted = minter.mint()
        val parsed = parseToken(minted.plaintext).shouldNotBeNull()
        parsed.id shouldBe minted.id
        hasher.verify(parsed.secret, minted.secretHash).shouldBeTrue()
    }

    test("two mints differ (SecureRandom)") {
        minter.mint().plaintext shouldNotBe minter.mint().plaintext
    }

    test("structurally invalid bearers parse to null, not thrown") {
        val validId = "00112233445566ff" // 16 lowercase-hex chars
        val validSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" // 43-char base64url of 32 bytes
        listOf(
            "",
            "pb_",
            "pb_onlyid", // no id↔secret boundary
            "notpb_x_y", // wrong prefix
            "pb_${validId}_!!notbase64!!", // undecodable secret (wrong length anyway)
            "pb__$validSecret", // empty id (boundary at index 0)
            "pb_${validId}_", // empty secret
        ).forEach { parseToken(it) shouldBe null }
    }

    test("the documented format is enforced: non-hex / overlong id, wrong secret length, oversized bearer reject") {
        val validId = "00112233445566ff" // 16 lowercase-hex
        val validSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" // 43-char base64url of 32 bytes
        listOf(
            "pb_00112233445566fg_$validSecret", // 'g' is not hex
            "pb_00112233445566FF_$validSecret", // uppercase hex (the minter emits lowercase)
            "pb_00112233445566_$validSecret", // 14-char id (too short)
            "pb_00112233445566ff00_$validSecret", // 20-char id (overlong)
            "pb_00112233445566fé_$validSecret", // Unicode in the id
            "pb_${validId}_${validSecret}AB", // encoded secret too long (45 chars)
            "pb_${validId}_${validSecret.dropLast(4)}", // encoded secret too short
            "pb_${validId}_$validSecret${"A".repeat(100_000)}", // oversized bearer — rejected before hash work
        ).forEach { parseToken(it) shouldBe null }
    }

    test("a legit minted token (incl. a secret containing `_`) still parses to a 32-byte secret") {
        // The secret alphabet includes `_`; the FIRST `_` after the prefix is the id boundary, so a 43-char
        // secret with `_` parses correctly — the regression the hex id fixes, now within the enforced bounds.
        val parsed = parseToken("pb_00112233445566ff_-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_s")
        parsed.shouldNotBeNull().id shouldBe "00112233445566ff"
        parsed.secret.size shouldBe 32
    }
})
