package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * SqlDelightRoleRepository over an in-memory SQLite db (A3 WI 1): upsert→roleOf round-trip, an unknown identity
 * is null, upsert is insert-or-replace (a re-grant updates the role), and `all()` returns every row. The
 * `5.sqm`/`6.db` migration-verify is asserted by `./gradlew build` (`verifyMigrations`), not here.
 */
class SqlDelightRoleRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightRoleRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightRoleRepository(DatabaseFactory.createDatabase(driver)))
        }

    val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    test("upsert then roleOf returns the role; an unknown identity is null") {
        withRepo { repo ->
            repo.upsert("builtin", "alice", Role.EDITOR, at)
            repo.roleOf("builtin", "alice") shouldBe Role.EDITOR
            repo.roleOf("builtin", "unknown").shouldBeNull()
            repo.roleOf("proxy", "alice").shouldBeNull() // issuer is part of the key
        }
    }

    test("upsert is insert-or-replace: a re-grant updates the role for the same identity") {
        withRepo { repo ->
            repo.upsert("builtin", "bob", Role.VIEWER, at)
            repo.upsert("builtin", "bob", Role.ADMIN, at)
            repo.roleOf("builtin", "bob") shouldBe Role.ADMIN
            repo.all() shouldHaveSize 1
        }
    }

    test("all returns every subject role row with no loss") {
        withRepo { repo ->
            repo.upsert("builtin", "alice", Role.EDITOR, at)
            repo.upsert("agent", "pb_xyz", Role.VIEWER, at)
            val rows = repo.all().associateBy { it.issuer to it.externalId }
            rows.getValue("builtin" to "alice").role shouldBe Role.EDITOR
            rows.getValue("agent" to "pb_xyz").role shouldBe Role.VIEWER
            rows.getValue("builtin" to "alice").createdAt shouldBe at
        }
    }
})
