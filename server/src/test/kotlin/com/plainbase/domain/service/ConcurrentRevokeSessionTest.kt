package com.plainbase.domain.service

import com.plainbase.domain.repository.SessionRepository
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The debate-REQUIRED concurrency test (synthesis §2 / §"WIDEN" 3): a revoke and an authenticate racing over the
 * SAME session must never let the post-revoke authenticate grant. The §2 atomic conditional UPDATE
 * ([SessionRepository.touchIfActive]) linearizes them — a revoke committing before the touch makes the touch
 * affect 0 rows. This is the test "the green suite tests single-request auth only" warned was missing.
 *
 * Two flavors, both over a shared in-memory SQLite session repo: (1) a decorator that simulates the lost race (the
 * conditional UPDATE reports the row was no longer active), proving authenticate trusts the UPDATE not a stale
 * read; (2) a real revoke/authenticate thread race asserting that once revoke has committed, NO subsequent
 * authenticate grants.
 */
@OptIn(ExperimentalAtomicApi::class)
class ConcurrentRevokeSessionTest : FunSpec({

    val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    /** A repo whose touchIfActive always reports "no longer active" — the lost-race linearization point. */
    class RaceLosingRepo(private val delegate: SessionRepository) : SessionRepository by delegate {
        override fun touchIfActive(tokenHash: ByteArray, now: Instant, idleTtl: Duration): Boolean = false
    }

    test("authenticate routes success through the conditional UPDATE: a lost race yields null") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val hasher = TokenHasher()
            val realRepo = SqlDelightSessionRepository(DatabaseFactory.createDatabase(driver))
            val racing = RaceLosingRepo(realRepo)
            val service = SessionService(SessionTokenMinter(hasher), hasher, racing, Clock.System, 7.days, 30.days)

            val minted = service.create("u1")
            // A stale read WOULD see the active row, but the conditional UPDATE reports it inactive → null.
            realRepo.findByTokenHash(minted.tokenHash)!!.revokedAt shouldBe null
            (service.authenticate(minted.plaintext) == null).shouldBeTrue()
        }
    }

    test("a real revoke/authenticate race never grants after the revoke commits") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val hasher = TokenHasher()
            val repo = SqlDelightSessionRepository(DatabaseFactory.createDatabase(driver))
            val service = SessionService(SessionTokenMinter(hasher), hasher, repo, Clock.System, 7.days, 30.days)
            val minted = service.create("u1")

            val revoked = AtomicBoolean(false)
            val grantedAfterRevoke = AtomicBoolean(false)

            val revoker = thread {
                service.revoke(minted.plaintext)
                revoked.store(true)
            }
            val auther = thread {
                repeat(200) {
                    val ok = service.authenticate(minted.plaintext) != null
                    // Once the revoke has COMMITTED, no authenticate may grant (the atomic UPDATE linearizes them).
                    if (revoked.load() && ok) grantedAfterRevoke.store(true)
                }
            }
            revoker.join()
            auther.join()
            // A final authenticate after the revoke definitely committed must be null.
            (service.authenticate(minted.plaintext) == null).shouldBeTrue()
            (!grantedAfterRevoke.load()).shouldBeTrue()
        }
    }
})
