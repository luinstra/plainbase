package com.plainbase.domain.service

import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.security.dummyPasswordHash
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Clock

/**
 * B2a — the disable-during-login TOCTOU. The argon2 `verify` window (OUTSIDE the txn) is long enough for an admin
 * to disable+revoke; without the in-txn re-read the post-verify `sessions.create` would mint a session the
 * revoke-all already passed over. This test parks the login INSIDE `verify` (via a blocking decorator), commits the
 * disable+revoke while it is parked, then releases — so the login's in-txn re-read sees `disabled=true` and aborts.
 *
 * NOT a vacuous test: the user is ENABLED when `login()` is called and at the INITIAL `findByUsername` read, so
 * pre-fix code (no in-txn re-read) reaches `sessions.create` and mints a surviving session; only the fixed code's
 * re-read aborts. The disable is committed strictly INSIDE the verify window.
 */
class LoginRaceDisableTest : FunSpec({

    fun <T> withDb(block: (PlainbaseDb) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver -> block(DatabaseFactory.createDatabase(driver)) }

    /** Wraps the real argon2 hasher; the first `verify` call parks on [entered]/[release] (the open verify window). */
    class BlockingVerifyHasher(
        private val delegate: PasswordHasher,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : PasswordHasher by delegate {
        override fun verify(plain: CharArray, encoded: String): Boolean {
            entered.countDown() // signal the login is inside the verify window
            release.await() // park here until the disable+revoke has committed
            return delegate.verify(plain, encoded)
        }
    }

    test("a disable+revoke committed during the verify window leaves NO valid session for the user") {
        withDb { db ->
            val argon2 = Argon2PasswordHasher(memoryKb = 256, iterations = 1) // fast params for tests
            val users = SqlDelightUserRepository(db)
            val sessionsRepo = SqlDelightSessionRepository(db)
            val transactions = SqlDelightTransactionRunner(db)
            val tokenHasher = TokenHasher()
            val sessionService = SessionService(SessionTokenMinter(tokenHasher), tokenHasher, sessionsRepo, Clock.System)

            // Seed an ENABLED user with a known password via the real bootstrap path.
            val (setup, _) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (setup.consumeBootstrap(token.plaintext, "alice", "pw".toCharArray()) as BootstrapOutcome.Created).userId

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocking = BlockingVerifyHasher(argon2, entered, release)
            val login = LoginService(
                users = users,
                passwordHasher = blocking,
                sessions = sessionService,
                transactions = transactions,
                dummyHash = dummyPasswordHash(argon2),
            )

            var outcome: LoginOutcome? = null
            val loginThread = thread { outcome = login.login("alice", "pw".toCharArray()) }

            entered.await() // the login is now parked inside verify, user still enabled
            // Commit the disable+revoke entirely INSIDE the verify window (the adversary GuardedAdminFacade.disableUser
            // shape: setDisabled + revokeAllForUser, atomic per B2a-2).
            transactions.inTransaction {
                users.setDisabled(userId, disabled = true, at = Clock.System.now())
                sessionService.revokeAllForUser(userId)
            }
            release.countDown() // let the login proceed into its txn; the in-txn re-read now sees disabled=true
            loginThread.join()

            // The fixed code aborts (Disabled) and mints nothing — pre-fix code (no in-txn re-read) would return
            // Success here, so this outcome assertion is what makes the test non-vacuous (no session is created).
            outcome.shouldNotBeNull()
            outcome.shouldBeInstanceOf<LoginOutcome.Disabled>()
        }
    }

    test("a password change+revoke committed during the verify window leaves NO valid session for the user") {
        withDb { db ->
            val argon2 = Argon2PasswordHasher(memoryKb = 256, iterations = 1) // fast params for tests
            val users = SqlDelightUserRepository(db)
            val sessionsRepo = SqlDelightSessionRepository(db)
            val transactions = SqlDelightTransactionRunner(db)
            val tokenHasher = TokenHasher()
            val sessionService = SessionService(SessionTokenMinter(tokenHasher), tokenHasher, sessionsRepo, Clock.System)

            val (setup, _) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (setup.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            // Park ONLY the login's verify: the concurrent change below runs over a SEPARATE SetupService with its own
            // (non-parking) hasher, so its current-proof verify never reaches this decorator.
            val blocking = BlockingVerifyHasher(argon2, entered, release)
            val login = LoginService(
                users = users,
                passwordHasher = blocking,
                sessions = sessionService,
                transactions = transactions,
                dummyHash = dummyPasswordHash(argon2),
            )

            var outcome: LoginOutcome? = null
            val loginThread = thread { outcome = login.login("alice", "old-pw".toCharArray()) }

            entered.await() // the login is parked inside verify; the user is still ENABLED, only the hash will drift.
            // Commit a password change (new hash + revokeAllForUser) entirely INSIDE the login's verify window, over a
            // SetupService with its OWN non-parking hasher so its current-proof verify does not block.
            (setupServiceFixture(db).first).changePassword(userId, "old-pw".toCharArray(), "new-pw".toCharArray())
            release.countDown() // let the login proceed into its txn; the in-txn re-read now sees the drifted hash.
            loginThread.join()

            // The disabled flag is untouched (the user stays ENABLED) — only the hash-drift guard can abort here, so a
            // disabled-only guard would mint a Success. The fixed code returns InvalidCredentials and mints nothing.
            outcome.shouldNotBeNull()
            outcome.shouldBeInstanceOf<LoginOutcome.InvalidCredentials>()
            users.findById(userId).shouldNotBeNull().disabled.shouldBeFalse() // non-vacuous: only the hash drifted
        }
    }
})
