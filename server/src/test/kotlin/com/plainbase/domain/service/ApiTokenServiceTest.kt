package com.plainbase.domain.service

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A clock whose [now] the test moves forward — deterministic expiry/last-used assertions (§0.9). */
private class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/**
 * A repo decorator simulating the TOCTOU race: [findById] still returns the active row a stale read would see,
 * but the atomic [touchIfActive] reports the token was no longer active when the conditional UPDATE committed
 * (a concurrent revoke/expiry winning the race). authenticate must trust the conditional write, not the read.
 */
private class RaceLosingRepo(private val delegate: ApiTokenRepository) : ApiTokenRepository by delegate {
    var touchCalls = 0
        private set

    override fun touchIfActive(id: String, at: Instant): Boolean {
        touchCalls++
        return false // the revoke/expiry committed first — no row was updated
    }
}

/**
 * The mint / authenticate / revoke decision-core (A2 WI 4) over an in-memory repo + a movable clock: a
 * round-trip resolves to [Principal.Agent], and a wrong-secret / unknown-prefix / revoked / expired token all
 * resolve to [Principal.Anonymous]; a successful authenticate stamps last-used to the clock. The HTTP-level
 * indistinguishability + both-paths-constant-time proof is `TokenAntiEnumerationTest`.
 */
class ApiTokenServiceTest : FunSpec({

    fun <T> withService(start: Instant, block: (ApiTokenService, MutableClock) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val clock = MutableClock(start)
            val hasher = TokenHasher()
            val service = ApiTokenService(
                minter = ApiTokenMinter(hasher),
                hasher = hasher,
                tokens = SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver)),
                clock = clock,
            )
            block(service, clock)
        }

    val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    test("mint then authenticate resolves to Agent and stamps last-used to the clock") {
        withService(t0) { service, clock ->
            val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
            clock.instant = t0 + 1.hours
            val principal = service.authenticate(minted.plaintext)
            principal.shouldBeInstanceOf<Principal.Agent>().tokenId shouldBe minted.id
            service.list().single().lastUsedAt shouldBe t0 + 1.hours
        }
    }

    test("a wrong secret resolves to Anonymous") {
        withService(t0) { service, _ ->
            val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
            val wrong = minted.plaintext.dropLast(2) + (if (minted.plaintext.last() == 'A') "BB" else "AA")
            service.authenticate(wrong) shouldBe Principal.Anonymous
        }
    }

    test("an unknown but well-formed prefix resolves to Anonymous") {
        withService(t0) { service, _ ->
            // A valid 16-hex id + 43-char base64url secret that was never minted — the anti-enumeration path.
            service.authenticate("pb_00112233445566ff_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8") shouldBe
                Principal.Anonymous
        }
    }

    test("a revoked token resolves to Anonymous") {
        withService(t0) { service, _ ->
            val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
            service.revoke(minted.id)
            service.authenticate(minted.plaintext) shouldBe Principal.Anonymous
        }
    }

    test("an expired token resolves to Anonymous once the clock passes expires_at") {
        withService(t0) { service, clock ->
            val minted = service.mint("ci-bot", AgentMode.READ_ONLY, ttl = 1.hours)
            service.authenticate(minted.plaintext).shouldNotBeNull().shouldBeInstanceOf<Principal.Agent>()
            clock.instant = t0 + 2.hours
            service.authenticate(minted.plaintext) shouldBe Principal.Anonymous
        }
    }

    test("authenticate routes success through the conditional update: a lost TOCTOU race yields Anonymous") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val clock = MutableClock(t0)
            val hasher = TokenHasher()
            val realRepo = SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver))
            val racingRepo = RaceLosingRepo(realRepo)
            val service = ApiTokenService(ApiTokenMinter(hasher), hasher, racingRepo, clock)

            // Mint a genuinely valid token: the secret verifies and a stale findById sees an active row...
            val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
            realRepo.findById(minted.id).shouldNotBeNull().revokedAt shouldBe null // the stale read WOULD pass

            // ...but touchIfActive (the linearization point) reports the row was no longer active.
            service.authenticate(minted.plaintext) shouldBe Principal.Anonymous
            (racingRepo.touchCalls == 1).shouldBeTrue() // success was decided by the ONE conditional write
        }
    }
})
