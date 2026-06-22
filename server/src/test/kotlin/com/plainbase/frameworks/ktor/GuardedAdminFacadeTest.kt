package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.manageGrantForTests
import com.plainbase.domain.repository.DuplicateUsernameException
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.TransactionRunner
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.service.CreateUserOutcome
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.SetupService
import com.plainbase.domain.service.UuidV7IdProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
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
        )

        facade.createUser(Principal.Human("builtin", "admin"), "taken", displayName = null, role = Role.EDITOR)
            .shouldBeInstanceOf<CreateUserOutcome.UsernameExists>()
    }
})
