package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.SessionRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * SqlDelightSessionRepository over an in-memory SQLite db (A4a WI-2): insert/find round-trip, the §2 atomic
 * touchIfActive (true for an active row + slides idle, false for revoked / idle-expired / absolute-expired), and
 * revokeAllForUser revoking every active row for a user and none for others.
 */
class SessionRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightSessionRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightSessionRepository(DatabaseFactory.createDatabase(driver)))
        }

    val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    val idle = 7.days
    val absolute = 30.days

    fun row(hash: Byte, userId: String, created: Instant = t0) = SessionRow(
        tokenHash = ByteArray(32) { hash },
        userId = userId,
        csrfToken = ByteArray(32) { (hash + 1).toByte() },
        createdAt = created,
        idleExpiresAt = created + idle,
        absoluteExpiresAt = created + absolute,
        revokedAt = null,
    )

    test("insert then findByTokenHash returns the row; an unknown hash is null") {
        withRepo { repo ->
            repo.insert(row(1, "u1"))
            val found = repo.findByTokenHash(ByteArray(32) { 1 }).shouldNotBeNull()
            found.userId shouldBe "u1"
            found.csrfToken.contentEquals(ByteArray(32) { 2 }) shouldBe true
            repo.findByTokenHash(ByteArray(32) { 9 }).shouldBeNull()
        }
    }

    test("touchIfActive grants an active session and slides idle_expires_at") {
        withRepo { repo ->
            repo.insert(row(1, "u1"))
            val at = t0 + 1.hours
            repo.touchIfActive(ByteArray(32) { 1 }, at, idle).shouldBeTrue()
            repo.findByTokenHash(ByteArray(32) { 1 })!!.idleExpiresAt shouldBe at + idle
        }
    }

    test("touchIfActive is a no-op (false) once the session is revoked") {
        withRepo { repo ->
            repo.insert(row(1, "u1"))
            repo.revokeByTokenHash(ByteArray(32) { 1 }, t0 + 1.hours)
            repo.touchIfActive(ByteArray(32) { 1 }, t0 + 2.hours, idle).shouldBeFalse()
        }
    }

    test("touchIfActive is a no-op (false) once idle-expired") {
        withRepo { repo ->
            repo.insert(row(1, "u1"))
            // 8 days later: past the 7-day idle window with no intervening touch.
            repo.touchIfActive(ByteArray(32) { 1 }, t0 + 8.days, idle).shouldBeFalse()
        }
    }

    test("touchIfActive is a no-op (false) once absolute-expired, even within the idle window") {
        withRepo { repo ->
            // A long-lived session kept active by touches still dies at the absolute cap.
            repo.insert(row(1, "u1"))
            var now = t0
            repeat(40) {
                now += 1.days
                repo.touchIfActive(ByteArray(32) { 1 }, now, idle) // slides idle but never extends absolute
            }
            // By now > t0 + 30d the absolute cap has passed: the last touch failed.
            repo.touchIfActive(ByteArray(32) { 1 }, t0 + 31.days, idle).shouldBeFalse()
        }
    }

    test("revokeAllForUser revokes every active session for the user and none for others") {
        withRepo { repo ->
            repo.insert(row(1, "u1"))
            repo.insert(row(2, "u1"))
            repo.insert(row(3, "u2"))
            repo.revokeAllForUser("u1", t0 + 1.hours)
            repo.touchIfActive(ByteArray(32) { 1 }, t0 + 2.hours, idle).shouldBeFalse()
            repo.touchIfActive(ByteArray(32) { 2 }, t0 + 2.hours, idle).shouldBeFalse()
            repo.touchIfActive(ByteArray(32) { 3 }, t0 + 2.hours, idle).shouldBeTrue() // u2 untouched
        }
    }

    test("prune deletes DEAD rows (revoked + absolute-expired + idle-expired) and keeps a live one") {
        withRepo { repo ->
            repo.insert(row(1, "u1")) // live: active, within both windows
            repo.insert(row(2, "u1")) // will be revoked → dead
            repo.revokeByTokenHash(ByteArray(32) { 2 }, t0 + 1.hours)
            // An absolutely-expired row (created 40d ago): past the 30d absolute cap at the prune instant → dead.
            repo.insert(row(3, "u2", created = t0 - 40.days))

            repo.prune(t0 + 1.hours)

            // The live row survives; the revoked + absolute-expired rows are gone (and a gone row can't be touched).
            repo.findByTokenHash(ByteArray(32) { 1 }).shouldNotBeNull()
            repo.findByTokenHash(ByteArray(32) { 2 }).shouldBeNull()
            repo.findByTokenHash(ByteArray(32) { 3 }).shouldBeNull()
            repo.touchIfActive(ByteArray(32) { 1 }, t0 + 2.hours, idle).shouldBeTrue()
        }
    }

    test("prune deletes an idle-expired-but-not-absolute row (provably dead) and keeps a still-idle-valid one") {
        withRepo { repo ->
            // Both rows are within the 30d absolute cap, so the OLD prune (absolute-only) would have spared BOTH —
            // letting the idle-dead row linger up to 30d (review P). row 4 idled out (created 8d ago, 7d idle window),
            // row 5 is still inside its idle window.
            repo.insert(row(4, "u1", created = t0 - 8.days)) // idle_expires_at = t0 - 1d → idle-expired at t0
            repo.insert(row(5, "u2", created = t0 - 1.days)) // idle_expires_at = t0 + 6d → still idle-valid

            repo.prune(t0)

            // The idle-expired row — which touchIfActive already refuses — is pruned; the still-idle-valid one survives.
            repo.findByTokenHash(ByteArray(32) { 4 }).shouldBeNull()
            repo.findByTokenHash(ByteArray(32) { 5 }).shouldNotBeNull()
            repo.touchIfActive(ByteArray(32) { 5 }, t0, idle).shouldBeTrue()
        }
    }
})
