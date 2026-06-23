package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.SetupTokenPurpose
import com.plainbase.domain.repository.SetupTokenRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * SqlDelightSetupTokenRepository over an in-memory SQLite db (A4a WI-2): markUsed returns true exactly once
 * (single-use), false for an expired token, and the round-trip preserves purpose/user_id.
 */
class SetupTokenRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightSetupTokenRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightSetupTokenRepository(DatabaseFactory.createDatabase(driver)))
        }

    val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    fun row(hash: Byte, purpose: SetupTokenPurpose, userId: String?, expires: Instant) = SetupTokenRow(
        tokenHash = ByteArray(32) { hash },
        purpose = purpose,
        userId = userId,
        createdAt = t0,
        expiresAt = expires,
        usedAt = null,
    )

    test("markUsed returns true exactly once — single-use") {
        withRepo { repo ->
            repo.insert(row(1, SetupTokenPurpose.BOOTSTRAP, null, t0 + 24.hours))
            repo.markUsed(ByteArray(32) { 1 }, t0 + 1.hours).shouldBeTrue()
            repo.markUsed(ByteArray(32) { 1 }, t0 + 2.hours).shouldBeFalse() // already used
        }
    }

    test("markUsed returns false for an expired token") {
        withRepo { repo ->
            repo.insert(row(1, SetupTokenPurpose.RESET, "u1", t0 + 1.hours))
            repo.markUsed(ByteArray(32) { 1 }, t0 + 2.hours).shouldBeFalse() // past expiry
        }
    }

    test("findByTokenHash round-trips purpose + user_id") {
        withRepo { repo ->
            repo.insert(row(7, SetupTokenPurpose.RESET, "u9", t0 + 24.hours))
            val found = repo.findByTokenHash(ByteArray(32) { 7 })!!
            found.purpose shouldBe SetupTokenPurpose.RESET
            found.userId shouldBe "u9"
        }
    }

    test("prune deletes DEAD rows (used + expired) and keeps a live one") {
        withRepo { repo ->
            repo.insert(row(1, SetupTokenPurpose.BOOTSTRAP, null, t0 + 24.hours)) // live: unused, unexpired
            repo.insert(row(2, SetupTokenPurpose.RESET, "u1", t0 + 24.hours)) // will be consumed → dead
            repo.markUsed(ByteArray(32) { 2 }, t0 + 1.hours).shouldBeTrue()
            repo.insert(row(3, SetupTokenPurpose.RESET, "u2", t0 + 1.hours)) // expires at t0+1h → dead at prune

            repo.prune(t0 + 2.hours)

            repo.findByTokenHash(ByteArray(32) { 1 })!!.usedAt shouldBe null // the live token survives
            repo.findByTokenHash(ByteArray(32) { 2 }) shouldBe null // used → pruned
            repo.findByTokenHash(ByteArray(32) { 3 }) shouldBe null // expired → pruned
        }
    }
})
