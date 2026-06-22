package com.plainbase.domain.service

import com.plainbase.domain.principal.Principal
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A clock whose [now] the test moves forward — deterministic expiry assertions (§0.9). */
private class SessionMutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/**
 * SessionService mint / authenticate / revoke (A4a WI-3, §1 + §2): a minted session authenticates; a revoked one
 * does not; idle-expiry and absolute-expiry (even kept active) both reject deterministically via the clock; the DB
 * stores only the HASH (the plaintext is never in a row); the atomic touch is the sole validity gate.
 */
class SessionServiceTest : FunSpec({

    fun <T> withService(start: Instant, block: (SessionService, SqlDelightSessionRepository, SessionMutableClock) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val clock = SessionMutableClock(start)
            val repo = SqlDelightSessionRepository(DatabaseFactory.createDatabase(driver))
            val hasher = TokenHasher()
            val service = SessionService(
                minter = SessionTokenMinter(hasher),
                hasher = hasher,
                sessions = repo,
                clock = clock,
                idleTtl = 7.days,
                absoluteTtl = 30.days,
            )
            block(service, repo, clock)
        }

    val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    test("mint then authenticate resolves to Human(builtin, userId)") {
        withService(t0) { service, _, clock ->
            val minted = service.create("u1")
            clock.instant = t0 + 1.hours
            val auth = service.authenticate(minted.plaintext).shouldNotBeNull()
            auth.principal shouldBe Principal.Human("builtin", "u1")
            auth.csrfToken.contentEquals(minted.csrfToken) shouldBe true
        }
    }

    test("a revoked session authenticates to null") {
        withService(t0) { service, _, _ ->
            val minted = service.create("u1")
            service.revoke(minted.plaintext)
            service.authenticate(minted.plaintext).shouldBeNull()
        }
    }

    test("an idle-expired session authenticates to null") {
        withService(t0) { service, _, clock ->
            val minted = service.create("u1")
            clock.instant = t0 + 8.days // past the 7-day idle window
            service.authenticate(minted.plaintext).shouldBeNull()
        }
    }

    test("an absolute-expired session authenticates to null even when kept active") {
        withService(t0) { service, _, clock ->
            val minted = service.create("u1")
            var now = t0
            repeat(40) {
                now += 1.days
                clock.instant = now
                service.authenticate(minted.plaintext) // slides idle but never extends absolute
            }
            clock.instant = t0 + 31.days
            service.authenticate(minted.plaintext).shouldBeNull()
        }
    }

    test("the DB stores the SHA-256 hash, never the plaintext cookie") {
        withService(t0) { service, repo, _ ->
            val minted = service.create("u1")
            // The row keyed by the SHA-256 hash exists; the plaintext bytes are NOT a key (a row under them is null).
            repo.findByTokenHash(minted.tokenHash).shouldNotBeNull()
            repo.findByTokenHash(minted.plaintext.encodeToByteArray()).shouldBeNull()
        }
    }
})
