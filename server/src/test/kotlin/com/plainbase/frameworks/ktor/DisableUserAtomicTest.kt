package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.manageGrantForTests
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.setupServiceFixture
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * B2a-2 — `disableUser` must set the disabled flag AND revoke all the user's sessions ATOMICALLY. `authenticate`
 * does NOT re-read `disabled` per request (its sole gate is the session-row touch), so revocation is the only thing
 * that kills a disabled user's live sessions — a crash between the two writes would leave the user
 * disabled-but-sessions-live. Driven over REAL in-memory SQLite (`SqlDelightTransactionRunner`) so the rollback is
 * genuine, not mocked.
 */
class DisableUserAtomicTest : FunSpec({

    fun <T> withDb(block: (PlainbaseDb) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver -> block(DatabaseFactory.createDatabase(driver)) }

    /** A session repo whose `revokeAllForUser` throws — the failure-after-`setDisabled` injection point. */
    class ThrowingRevokeRepo(private val delegate: SessionRepository) : SessionRepository by delegate {
        override fun revokeAllForUser(userId: String, at: Instant): Unit = throw RuntimeException("injected revoke failure")
    }

    fun facade(
        db: PlainbaseDb,
        sessionService: SessionService,
    ): GuardedAdminFacade {
        val policy = mockk<PolicyService> { every { checkManage(any()) } returns manageGrantForTests() }
        val (setup, _) = setupServiceFixture(db)
        return GuardedAdminFacade(
            policy = policy,
            users = SqlDelightUserRepository(db),
            roles = mockk(relaxed = true),
            setup = setup,
            sessions = sessionService,
            passwordHasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1),
            idProvider = UuidV7IdProvider(),
            transactions = SqlDelightTransactionRunner(db),
            clock = Clock.System,
            tokens = mockk<ApiTokenService>(relaxed = true),
            audit = mockk<AuditRepository>(relaxed = true),
        )
    }

    val admin = Principal.Human("builtin", "admin")

    test("success: disable sets the flag AND every pre-existing session fails authenticate (no valid session survives)") {
        withDb { db ->
            val users = SqlDelightUserRepository(db)
            val hasher = TokenHasher()
            val sessionService = SessionService(SessionTokenMinter(hasher), hasher, SqlDelightSessionRepository(db), Clock.System)

            val (setup, _) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (
                setup.consumeBootstrap(
                    token.plaintext,
                    "alice",
                    "pw".toCharArray(),
                ) as com.plainbase.domain.service.BootstrapOutcome.Created
                ).userId
            val s1 = sessionService.create(userId)
            val s2 = sessionService.create(userId)
            sessionService.authenticate(s1.plaintext).shouldNotBeNull()
            sessionService.authenticate(s2.plaintext).shouldNotBeNull()

            facade(db, sessionService).disableUser(admin, userId).shouldBeTrue()

            users.findById(userId).shouldNotBeNull().disabled.shouldBeTrue()
            sessionService.authenticate(s1.plaintext).shouldBeNull()
            sessionService.authenticate(s2.plaintext).shouldBeNull()
        }
    }

    test("rollback: a failure after setDisabled leaves the user STILL ENABLED and sessions STILL VALID (all-or-nothing)") {
        withDb { db ->
            val users = SqlDelightUserRepository(db)
            val hasher = TokenHasher()
            // The SessionService used to SEED + later VERIFY uses the real repo; the FACADE gets a throwing-revoke
            // SessionService so the second write inside the txn fails after setDisabled has run.
            val realSessionService = SessionService(SessionTokenMinter(hasher), hasher, SqlDelightSessionRepository(db), Clock.System)
            val throwingSessionService =
                SessionService(SessionTokenMinter(hasher), hasher, ThrowingRevokeRepo(SqlDelightSessionRepository(db)), Clock.System)

            val (setup, _) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (
                setup.consumeBootstrap(
                    token.plaintext,
                    "alice",
                    "pw".toCharArray(),
                ) as com.plainbase.domain.service.BootstrapOutcome.Created
                ).userId
            val s1 = realSessionService.create(userId)
            realSessionService.authenticate(s1.plaintext).shouldNotBeNull()

            shouldThrow<RuntimeException> { facade(db, throwingSessionService).disableUser(admin, userId) }

            // The txn rolled back: still enabled, and the pre-existing session still authenticates (intact).
            users.findById(userId).shouldNotBeNull().disabled.shouldBeFalse()
            realSessionService.authenticate(s1.plaintext).shouldNotBeNull()
        }
    }

    test("a non-existent userId returns false without opening a transaction") {
        withDb { db ->
            val hasher = TokenHasher()
            val sessionService = SessionService(SessionTokenMinter(hasher), hasher, SqlDelightSessionRepository(db), Clock.System)
            facade(db, sessionService).disableUser(admin, "no-such-user").shouldBeFalse()
        }
    }
})
