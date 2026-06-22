package com.plainbase.domain.service

import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.repository.Role
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
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock

/** Builds a real SetupService + its SessionService over a shared in-memory DB (the production wiring, minus HTTP). */
internal fun setupServiceFixture(db: PlainbaseDb): Pair<SetupService, SessionService> {
    val hasher = TokenHasher()
    val sessions = SessionService(
        minter = SessionTokenMinter(hasher),
        hasher = hasher,
        sessions = SqlDelightSessionRepository(db),
        clock = Clock.System,
    )
    val setup = SetupService(
        minter = SetupTokenMinter(hasher),
        hasher = hasher,
        setupTokens = SqlDelightSetupTokenRepository(db),
        users = SqlDelightUserRepository(db),
        roles = SqlDelightRoleRepository(db),
        sessions = sessions,
        passwordHasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1), // fast params for tests
        idProvider = UuidV7IdProvider(),
        transactions = SqlDelightTransactionRunner(db),
        clock = Clock.System,
    )
    return setup to sessions
}

/**
 * SetupService bootstrap (A4a WI-5, §5): a single-use token creates exactly one admin (user + builtin/<id>=ADMIN
 * grant + token used); a second consume with the same token fails; a concurrent double-consume admits exactly one
 * (the atomic markUsed + the single transaction — the count-then-insert TOCTOU guard).
 */
class SetupServiceTest : FunSpec({

    fun <T> withDb(block: (PlainbaseDb) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver -> block(DatabaseFactory.createDatabase(driver)) }

    test("bootstrap creates a user + a builtin ADMIN grant and marks the token used") {
        withDb { db ->
            val (setup, _) = setupServiceFixture(db)
            val roles = SqlDelightRoleRepository(db)
            val users = SqlDelightUserRepository(db)

            val token = setup.mintBootstrapToken()
            val outcome = setup.consumeBootstrap(token.plaintext, "alice", "secret-pw".toCharArray())
            val created = outcome.shouldBeInstanceOf<BootstrapOutcome.Created>()

            users.findByUsername("alice")!!.id shouldBe created.userId
            roles.roleOf("builtin", created.userId) shouldBe Role.ADMIN
            users.countEnabledAdmins() shouldBe 1L
        }
    }

    test("a second consume of the same token fails — single-use") {
        withDb { db ->
            val (setup, _) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            setup.consumeBootstrap(token.plaintext, "alice", "pw".toCharArray()).shouldBeInstanceOf<BootstrapOutcome.Created>()
            setup.consumeBootstrap(token.plaintext, "bob", "pw".toCharArray()) shouldBe BootstrapOutcome.TokenInvalid
        }
    }

    test("a bootstrap consume with an already-taken username → UsernameExists (409), NOT a 500, and the token is not burned") {
        withDb { db ->
            val (setup, _) = setupServiceFixture(db)
            val users = SqlDelightUserRepository(db)

            // First admin claims "alice".
            val first = setup.mintBootstrapToken()
            setup.consumeBootstrap(first.plaintext, "alice", "pw".toCharArray()).shouldBeInstanceOf<BootstrapOutcome.Created>()

            // A second (valid) bootstrap token consumed with the SAME username surfaces the unique violation as 409,
            // never a raw 500. The username is unchanged and only one admin exists.
            val second = setup.mintBootstrapToken()
            setup.consumeBootstrap(second.plaintext, "alice", "pw".toCharArray()) shouldBe BootstrapOutcome.UsernameExists
            users.countEnabledAdmins() shouldBe 1L

            // The clash rolled back markUsed, so the second token is NOT burned — retrying it with a free name succeeds.
            setup.consumeBootstrap(second.plaintext, "bob", "pw".toCharArray()).shouldBeInstanceOf<BootstrapOutcome.Created>()
            users.countEnabledAdmins() shouldBe 2L
        }
    }

    test("consumeBootstrap REJECTS a RESET token — no cross-purpose privilege escalation to a new ADMIN") {
        withDb { db ->
            val (setup, _) = setupServiceFixture(db)
            val users = SqlDelightUserRepository(db)

            // Provision the system: one real admin, then an admin-issued RESET token for that user.
            val bootstrap = setup.mintBootstrapToken()
            val admin = setup.consumeBootstrap(bootstrap.plaintext, "alice", "pw".toCharArray())
                .shouldBeInstanceOf<BootstrapOutcome.Created>()
            val resetToken = setup.mintResetToken(admin.userId)!!

            // The RESET token must NOT be consumable as a bootstrap — else it would mint a brand-new ADMIN.
            setup.consumeBootstrap(resetToken.plaintext, "attacker", "pw".toCharArray()) shouldBe BootstrapOutcome.TokenInvalid
            users.findByUsername("attacker") shouldBe null
            users.countEnabledAdmins() shouldBe 1L
        }
    }

    test("consumeReset REJECTS a BOOTSTRAP token — purpose-bound symmetry can't regress") {
        withDb { db ->
            val (setup, _) = setupServiceFixture(db)
            val bootstrap = setup.mintBootstrapToken()
            setup.consumeReset(bootstrap.plaintext, "pw".toCharArray()) shouldBe ResetOutcome.TokenInvalid
        }
    }

    test("the argon2 hash is NOT run for a bogus/cross-purpose token, but IS for a valid one (fix E)") {
        withDb { db ->
            val counter = CountingPasswordHasher(Argon2PasswordHasher(memoryKb = 256, iterations = 1))
            val setup = SetupService(
                minter = SetupTokenMinter(TokenHasher()),
                hasher = TokenHasher(),
                setupTokens = SqlDelightSetupTokenRepository(db),
                users = SqlDelightUserRepository(db),
                roles = SqlDelightRoleRepository(db),
                sessions = SessionService(SessionTokenMinter(TokenHasher()), TokenHasher(), SqlDelightSessionRepository(db), Clock.System),
                passwordHasher = counter,
                idProvider = UuidV7IdProvider(),
                transactions = SqlDelightTransactionRunner(db),
                clock = Clock.System,
            )

            // A bogus token on the PUBLIC consume path must not force an argon2 hash (the cheap pre-check rejects it).
            setup.consumeBootstrap("pb_bogus_token", "alice", "pw".toCharArray()) shouldBe BootstrapOutcome.TokenInvalid
            (counter.hashes == 0).shouldBeTrue()

            // A cross-purpose RESET token (admin-issued) also rejects BEFORE hashing — no argon2 amplification.
            val bootstrap = setup.mintBootstrapToken()
            val admin = setup.consumeBootstrap(bootstrap.plaintext, "alice", "pw".toCharArray())
                .shouldBeInstanceOf<BootstrapOutcome.Created>()
            val hashesAfterRealBootstrap = counter.hashes
            (hashesAfterRealBootstrap == 1).shouldBeTrue() // a VALID token DID hash exactly once
            val reset = setup.mintResetToken(admin.userId)!!
            setup.consumeBootstrap(reset.plaintext, "attacker", "pw".toCharArray()) shouldBe BootstrapOutcome.TokenInvalid
            (counter.hashes == hashesAfterRealBootstrap).shouldBeTrue() // the cross-purpose token did NOT hash
        }
    }

    test("a concurrent double-consume of one token admits exactly one (atomic markUsed + txn)") {
        withDb { db ->
            val (setup, _) = setupServiceFixture(db)
            val users = SqlDelightUserRepository(db)
            val token = setup.mintBootstrapToken()

            val created = java.util.concurrent.atomic.AtomicInteger(0)
            val t1 = kotlin.concurrent.thread {
                if (setup.consumeBootstrap(
                        token.plaintext,
                        "alice",
                        "pw".toCharArray(),
                    ) is BootstrapOutcome.Created
                ) {
                    created.incrementAndGet()
                }
            }
            val t2 = kotlin.concurrent.thread {
                if (setup.consumeBootstrap(
                        token.plaintext,
                        "bob",
                        "pw".toCharArray(),
                    ) is BootstrapOutcome.Created
                ) {
                    created.incrementAndGet()
                }
            }
            t1.join()
            t2.join()
            (created.get() == 1).shouldBeTrue()
            (users.countEnabledAdmins() == 1L).shouldBeTrue()
        }
    }
})

/** Counts argon2 hash calls so the fix-E ordering (cheap token check BEFORE the expensive hash) is observable. */
private class CountingPasswordHasher(private val delegate: PasswordHasher) : PasswordHasher {
    var hashes = 0
        private set

    override fun hash(plain: CharArray): String {
        hashes++
        return delegate.hash(plain)
    }

    override fun verify(plain: CharArray, encoded: String): Boolean = delegate.verify(plain, encoded)
}
