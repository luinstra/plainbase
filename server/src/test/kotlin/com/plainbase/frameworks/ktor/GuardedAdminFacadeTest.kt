package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.MintedSetupToken
import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.manageGrantForTests
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.DuplicateUsernameException
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.TransactionRunner
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.CreateUserOutcome
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.SetupService
import com.plainbase.domain.service.UuidV7IdProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/**
 * GuardedAdminFacade.createUser (review U): the `users.username UNIQUE` constraint — not the preflight — is the 409
 * authority. When two concurrent creates both pass the preflight, the loser's insert throws
 * [DuplicateUsernameException]; the facade must map it to the SAME [CreateUserOutcome.UsernameExists] (→ 409), never
 * let it surface as a 500. Exercised directly by stubbing the preflight to MISS while the insert throws (the race the
 * preflight can't catch).
 */
class GuardedAdminFacadeTest : FunSpec({

    test("a username UNIQUE-constraint violation on insert maps to UsernameExists (not a 500), even past the preflight") {
        val users = mockk<UserRepository> {
            every { findByUsername("taken") } returns null // preflight MISSES — simulates the concurrent-create race
            every { insert(any()) } throws DuplicateUsernameException("taken")
        }
        val roles = mockk<RoleRepository>(relaxed = true)
        val policy = mockk<PolicyService> { every { checkManage(any()) } returns manageGrantForTests() }
        val hasher = mockk<PasswordHasher> { every { hash(any()) } returns "\$argon2id\$x" }
        val sessions = mockk<SessionService>(relaxed = true)
        val setup = mockk<SetupService>(relaxed = true)
        val idProvider: IdProvider = UuidV7IdProvider()
        // A pass-through runner so the insert's throw propagates exactly as it would inside a real rolled-back txn.
        val transactions = object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

        val facade = GuardedAdminFacade(
            policy = policy,
            users = users,
            roles = roles,
            setup = setup,
            sessions = sessions,
            passwordHasher = hasher,
            idProvider = idProvider,
            transactions = transactions,
            clock = Clock.System,
            tokens = mockk<ApiTokenService>(relaxed = true),
            audit = mockk<AuditRepository>(relaxed = true),
        )

        facade.createUser(Principal.Human("builtin", "admin"), "taken", displayName = null, role = Role.EDITOR)
            .shouldBeInstanceOf<CreateUserOutcome.UsernameExists>()
    }

    // Pins the hoist, not just the current line order. Under BeginImmediateSqliteDriver the write lock is taken at
    // BEGIN, so an Argon2id hash (m=64 MiB, t=3) inside the transaction holds it for hundreds of milliseconds and eats
    // the busy budget every other app-DB transaction waits on. Moving the hash back inside reds this row.
    test("hashes the placeholder password OUTSIDE the create transaction, so the write lock is not held across argon2") {
        val insideTransaction = AtomicBoolean(false)
        val hashCalls = AtomicInteger(0)
        val hashedInsideTransaction = AtomicBoolean(false)

        val users = mockk<UserRepository>(relaxed = true) { every { findByUsername(any()) } returns null }
        val roles = mockk<RoleRepository>(relaxed = true)
        val policy = mockk<PolicyService> { every { checkManage(any()) } returns manageGrantForTests() }
        val hasher = mockk<PasswordHasher> {
            every { hash(any()) } answers {
                hashCalls.incrementAndGet()
                if (insideTransaction.get()) hashedInsideTransaction.set(true)
                "\$argon2id\$x"
            }
        }
        val setup = mockk<SetupService> {
            every { mintResetToken(any()) } returns MintedSetupToken("reset-plaintext", ByteArray(0))
        }
        // Records whether control is inside the transaction rather than assuming the call order from the source.
        val transactions = object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                insideTransaction.set(true)
                return try {
                    block()
                } finally {
                    insideTransaction.set(false)
                }
            }
        }

        val facade = GuardedAdminFacade(
            policy = policy,
            users = users,
            roles = roles,
            setup = setup,
            sessions = mockk<SessionService>(relaxed = true),
            passwordHasher = hasher,
            idProvider = UuidV7IdProvider(),
            transactions = transactions,
            clock = Clock.System,
            tokens = mockk<ApiTokenService>(relaxed = true),
            audit = mockk<AuditRepository>(relaxed = true),
        )

        facade.createUser(Principal.Human("builtin", "admin"), "fresh", displayName = null, role = Role.EDITOR)
            .shouldBeInstanceOf<CreateUserOutcome.Created>()

        // Anti-vacuity: a hash that never ran would leave hashedInsideTransaction false and pass for the wrong reason.
        hashCalls.get() shouldBe 1
        hashedInsideTransaction.get() shouldBe false
    }
})
