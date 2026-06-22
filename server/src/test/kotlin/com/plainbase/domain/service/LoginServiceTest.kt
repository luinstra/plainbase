package com.plainbase.domain.service

import com.plainbase.domain.principal.MintedSession
import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.repository.UserRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.time.Instant

/**
 * LoginService (A4a WI-4): the four outcomes, and the anti-enumeration property at the SERVICE boundary — an
 * unknown OR disabled user still invokes PasswordHasher.verify against the dummy hash exactly once (timing
 * parity), with NO early-out. MockK proves the dummy verify is reached.
 */
class LoginServiceTest : FunSpec({

    val dummy = "\$argon2id\$v=19\$m=65536,t=3,p=1\$ZHVtbXk\$ZHVtbXloYXNo"

    fun userRow(disabled: Boolean) = UserRow(
        id = "u1",
        username = "alice",
        passwordHash = "\$argon2id\$real",
        displayName = null,
        disabled = disabled,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    test("correct credentials → Success with a minted session") {
        val users = mockk<UserRepository> { every { findByUsername("alice") } returns userRow(disabled = false) }
        val hasher = mockk<PasswordHasher> { every { verify(any(), "\$argon2id\$real") } returns true }
        val sessions = mockk<SessionService> { every { create("u1") } returns MintedSession("plain", ByteArray(32), ByteArray(32)) }
        val service = LoginService(users, hasher, sessions, dummy)

        service.login("alice", "pw".toCharArray()).shouldBeInstanceOf<LoginOutcome.Success>()
        verify(exactly = 1) { sessions.create("u1") }
    }

    test("wrong password → InvalidCredentials, no session") {
        val users = mockk<UserRepository> { every { findByUsername("alice") } returns userRow(disabled = false) }
        val hasher = mockk<PasswordHasher> { every { verify(any(), "\$argon2id\$real") } returns false }
        val sessions = mockk<SessionService>()
        val service = LoginService(users, hasher, sessions, dummy)

        service.login("alice", "bad".toCharArray()) shouldBe LoginOutcome.InvalidCredentials
        verify(exactly = 0) { sessions.create(any()) }
    }

    test("a disabled user → Disabled, and the dummy hash is still verified (timing parity)") {
        val users = mockk<UserRepository> { every { findByUsername("alice") } returns userRow(disabled = true) }
        val hasher = mockk<PasswordHasher> { every { verify(any(), dummy) } returns false }
        val sessions = mockk<SessionService>()
        val service = LoginService(users, hasher, sessions, dummy)

        service.login("alice", "pw".toCharArray()) shouldBe LoginOutcome.Disabled
        verify(exactly = 1) { hasher.verify(any(), dummy) } // ran the same argon2 work as the real path
        verify(exactly = 0) { sessions.create(any()) }
    }

    test("an unknown user → InvalidCredentials, and the dummy hash is verified exactly once (anti-enumeration)") {
        val users = mockk<UserRepository> { every { findByUsername("ghost") } returns null }
        val hasher = mockk<PasswordHasher> { every { verify(any(), dummy) } returns false }
        val sessions = mockk<SessionService>()
        val service = LoginService(users, hasher, sessions, dummy)

        service.login("ghost", "pw".toCharArray()) shouldBe LoginOutcome.InvalidCredentials
        verify(exactly = 1) { hasher.verify(any(), dummy) } // no early-out — the unknown path does the verify work
    }
})
