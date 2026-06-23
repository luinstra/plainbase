package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.DuplicateUsernameException
import com.plainbase.domain.repository.UserRow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * SqlDelightUserRepository over an in-memory SQLite db (A4a WI-2). The review-U guard: the adapter translates the
 * `users.username` UNIQUE-constraint violation from the driver into the framework-free [DuplicateUsernameException]
 * (so the admin facade maps a racing duplicate to a 409, never a raw 500) — and a distinct username inserts fine.
 */
class UserRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightUserRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightUserRepository(DatabaseFactory.createDatabase(driver)))
        }

    val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    fun row(id: String, username: String) = UserRow(
        id = id,
        username = username,
        passwordHash = "\$argon2id\$x",
        displayName = null,
        disabled = false,
        createdAt = t0,
        updatedAt = t0,
    )

    test("inserting a duplicate username throws DuplicateUsernameException (the driver constraint is translated)") {
        withRepo { repo ->
            repo.insert(row("u1", "alice"))
            shouldThrow<DuplicateUsernameException> { repo.insert(row("u2", "alice")) }
            // The first row is intact and the second never landed.
            repo.findByUsername("alice")!!.id shouldBe "u1"
        }
    }

    test("inserting a distinct username succeeds") {
        withRepo { repo ->
            repo.insert(row("u1", "alice"))
            repo.insert(row("u2", "bob"))
            repo.findByUsername("bob")!!.id shouldBe "u2"
        }
    }
})
