package com.plainbase.domain.service

import com.plainbase.domain.repository.SessionRepository
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.SetupTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSetupTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The debate-REQUIRED test (synthesis §"WIDEN" 1): a password change AND a reset BOTH revoke ALL of the user's
 * sessions (the §7 force-re-login). A wrong current password leaves sessions intact + does not change the hash.
 * B2b extends it: the change is ATOMIC (a failure between the hash-update and the revoke rolls BOTH back), and a
 * user concurrently deleted between the verify and the txn returns UserNotFound (not a lying Changed).
 */
class RevokeAllOnPasswordChangeTest : FunSpec({

    fun <T> withDb(block: (PlainbaseDb) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver -> block(DatabaseFactory.createDatabase(driver)) }

    test("changePassword revokes ALL of the user's sessions; a wrong current password leaves them intact") {
        withDb { db ->
            val (setup, sessions) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (setup.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId

            val s1 = sessions.create(userId)
            val s2 = sessions.create(userId)
            sessions.authenticate(s1.plaintext).shouldNotBeNull()
            sessions.authenticate(s2.plaintext).shouldNotBeNull()

            // A wrong current password must NOT touch the hash or the sessions.
            setup.changePassword(userId, "wrong".toCharArray(), "new-pw".toCharArray()) shouldBe ChangeOutcome.WrongCurrentPassword
            sessions.authenticate(s1.plaintext).shouldNotBeNull()

            setup.changePassword(userId, "old-pw".toCharArray(), "new-pw".toCharArray()) shouldBe ChangeOutcome.Changed
            sessions.authenticate(s1.plaintext).shouldBeNull()
            sessions.authenticate(s2.plaintext).shouldBeNull()
        }
    }

    test("consumeReset revokes ALL of the user's sessions") {
        withDb { db ->
            val (setup, sessions) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (setup.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId

            // Admin issues a reset (which already revokes-on-issue), then a NEW session is created and consume revokes it too.
            val resetToken = setup.mintResetToken(userId).shouldNotBeNull()
            val live = sessions.create(userId)
            sessions.authenticate(live.plaintext).shouldNotBeNull()

            setup.consumeReset(resetToken.plaintext, "reset-pw".toCharArray()).shouldBeInstanceOf<ResetOutcome.Reset>()
            sessions.authenticate(live.plaintext).shouldBeNull()
        }
    }

    // B2b — a SetupService over real SQLite whose SessionService can be swapped for a throwing-revoke one, so the
    // rollback exercises the genuine `SqlDelightTransactionRunner`, not a mock.
    fun setupOver(db: PlainbaseDb, sessions: SessionService): SetupService {
        val tokenHasher = TokenHasher()
        return SetupService(
            minter = SetupTokenMinter(tokenHasher),
            hasher = tokenHasher,
            setupTokens = SqlDelightSetupTokenRepository(db),
            users = SqlDelightUserRepository(db),
            roles = SqlDelightRoleRepository(db),
            sessions = sessions,
            passwordHasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1),
            idProvider = UuidV7IdProvider(),
            transactions = SqlDelightTransactionRunner(db),
            clock = Clock.System,
        )
    }

    test("changePassword rolls BOTH writes back when revoke fails: the old hash + the old sessions survive (B2b)") {
        withDb { db ->
            val tokenHasher = TokenHasher()
            val realSessions = SessionService(SessionTokenMinter(tokenHasher), tokenHasher, SqlDelightSessionRepository(db), Clock.System)
            // Seed an enabled user with a known password + two live sessions via the real path.
            val bootstrap = setupOver(db, realSessions)
            val token = bootstrap.mintBootstrapToken()
            val userId = (bootstrap.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId
            val s1 = realSessions.create(userId)
            val s2 = realSessions.create(userId)

            // The change runs over a SetupService whose revokeAllForUser throws — the SECOND write inside the txn,
            // after setPasswordHash has run. The txn must roll the hash-update back too.
            val throwingRevoke = object : SessionRepository by SqlDelightSessionRepository(db) {
                override fun revokeAllForUser(userId: String, at: Instant): Unit = throw RuntimeException("injected revoke failure")
            }
            val throwingSessions = SessionService(SessionTokenMinter(tokenHasher), tokenHasher, throwingRevoke, Clock.System)
            io.kotest.assertions.throwables.shouldThrow<RuntimeException> {
                setupOver(db, throwingSessions).changePassword(userId, "old-pw".toCharArray(), "new-pw".toCharArray())
            }

            // Rolled back: the OLD password still verifies (hash unchanged) AND both sessions still authenticate.
            setupOver(db, realSessions).changePassword(userId, "new-pw".toCharArray(), "x".toCharArray()) shouldBe
                ChangeOutcome.WrongCurrentPassword
            realSessions.authenticate(s1.plaintext).shouldNotBeNull()
            realSessions.authenticate(s2.plaintext).shouldNotBeNull()
        }
    }

    test("a user deleted between the verify and the txn body → UserNotFound, no rows mutated (B2b)") {
        withDb { db ->
            val tokenHasher = TokenHasher()
            val realSessions = SessionService(SessionTokenMinter(tokenHasher), tokenHasher, SqlDelightSessionRepository(db), Clock.System)
            val bootstrap = setupOver(db, realSessions)
            val token = bootstrap.mintBootstrapToken()
            val userId = (bootstrap.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId
            val live = realSessions.create(userId)

            // A UserRepository whose first (pre-txn verify) findById sees the user, but the in-txn re-read returns null
            // (the concurrent delete). The change must report UserNotFound, not Changed, and revoke nothing.
            val realUsers = SqlDelightUserRepository(db)
            val vanishing = object : com.plainbase.domain.repository.UserRepository by realUsers {
                private var calls = 0
                override fun findById(id: String) = realUsers.findById(id).also { calls++ }.takeIf { calls == 1 }
            }
            val service = SetupService(
                minter = SetupTokenMinter(tokenHasher),
                hasher = tokenHasher,
                setupTokens = SqlDelightSetupTokenRepository(db),
                users = vanishing,
                roles = SqlDelightRoleRepository(db),
                sessions = realSessions,
                passwordHasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1),
                idProvider = UuidV7IdProvider(),
                transactions = SqlDelightTransactionRunner(db),
                clock = Clock.System,
            )

            service.changePassword(userId, "old-pw".toCharArray(), "new-pw".toCharArray()) shouldBe ChangeOutcome.UserNotFound
            // No rows mutated: the session is still live (revoke never ran) and the old password still verifies.
            realSessions.authenticate(live.plaintext).shouldNotBeNull()
            setupOver(db, realSessions).changePassword(userId, "new-pw".toCharArray(), "x".toCharArray()) shouldBe
                ChangeOutcome.WrongCurrentPassword
        }
    }

    test("a concurrent password change committed before the txn body → WrongCurrentPassword, the newer hash survives (B2b drift)") {
        withDb { db ->
            val tokenHasher = TokenHasher()
            val realSessions = SessionService(SessionTokenMinter(tokenHasher), tokenHasher, SqlDelightSessionRepository(db), Clock.System)
            val bootstrap = setupOver(db, realSessions)
            val token = bootstrap.mintBootstrapToken()
            val userId = (bootstrap.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId
            val live = realSessions.create(userId)

            // A UserRepository whose first (pre-txn verify) findById sees the ORIGINAL row (hash of "old-pw"), but
            // BEFORE the in-txn re-read a concurrent admin reset commits a NEW hash. The decorator commits that reset
            // between call 1 and call 2, so the in-txn re-read sees the drifted hash and the stale proof must lose.
            val realUsers = SqlDelightUserRepository(db)
            val racingUsers = object : com.plainbase.domain.repository.UserRepository by realUsers {
                private var calls = 0
                override fun findById(id: String) = realUsers.findById(id).also {
                    calls++
                    if (calls == 1) {
                        // The concurrent winner: a reset that lands during our argon2 verify window (new hash + revoke).
                        setupOver(db, realSessions).consumeReset(
                            bootstrap.mintResetToken(userId)!!.plaintext,
                            "winner-pw".toCharArray(),
                        )
                    }
                }
            }
            val service = SetupService(
                minter = SetupTokenMinter(tokenHasher),
                hasher = tokenHasher,
                setupTokens = SqlDelightSetupTokenRepository(db),
                users = racingUsers,
                roles = SqlDelightRoleRepository(db),
                sessions = realSessions,
                passwordHasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1),
                idProvider = UuidV7IdProvider(),
                transactions = SqlDelightTransactionRunner(db),
                clock = Clock.System,
            )

            // The stale "old-pw" proof was valid at the pre-txn read but the hash drifted under it → WrongCurrentPassword.
            service.changePassword(userId, "old-pw".toCharArray(), "loser-pw".toCharArray()) shouldBe ChangeOutcome.WrongCurrentPassword
            // The newer hash ("winner-pw") survives — the stale change did NOT clobber it.
            setupOver(db, realSessions).changePassword(userId, "winner-pw".toCharArray(), "x".toCharArray()) shouldBe ChangeOutcome.Changed
            // The stale "loser-pw" never took effect.
            setupOver(db, realSessions).changePassword(userId, "loser-pw".toCharArray(), "y".toCharArray()) shouldBe
                ChangeOutcome.WrongCurrentPassword
        }
    }
})
