package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * SqlDelightApiTokenRepository over an in-memory SQLite db (A2 WI 3): insert/find round-trip, an unknown id is
 * null, touch/revoke set the fields against a deterministic clock instant, `all()` returns metadata, and the
 * lookup is by the indexed prefix. The `4.sqm`/`5.db` migration-verify is asserted by `./gradlew build`
 * (`verifyMigrations`), not here.
 */
class SqlDelightApiTokenRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightApiTokenRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver)))
        }

    val created = Instant.fromEpochMilliseconds(1_700_000_000_000)
    val later = Instant.fromEpochMilliseconds(1_700_000_500_000)

    fun row(id: String) = ApiTokenRow(
        id = id,
        secretHash = ByteArray(32) { it.toByte() },
        agentLabel = "ci-bot",
        issuer = "agent",
        externalId = id,
        mode = AgentMode.READ_ONLY,
        createdAt = created,
        lastUsedAt = null,
        expiresAt = null,
        revokedAt = null,
    )

    test("insert then findById returns the row; an unknown id is null") {
        withRepo { repo ->
            repo.insert(row("abc"))
            val found = repo.findById("abc").shouldNotBeNull()
            found.agentLabel shouldBe "ci-bot"
            found.mode shouldBe AgentMode.READ_ONLY
            found.createdAt shouldBe created
            found.secretHash.contentEquals(ByteArray(32) { it.toByte() }) shouldBe true
            repo.findById("nope").shouldBeNull()
        }
    }

    test("touchIfActive stamps last-used and reports a row was updated; revoke sets revoked_at") {
        withRepo { repo ->
            repo.insert(row("abc"))
            repo.touchIfActive("abc", later).shouldBeTrue()
            repo.findById("abc")!!.lastUsedAt shouldBe later
            repo.findById("abc")!!.revokedAt.shouldBeNull()
            repo.revoke("abc", later)
            repo.findById("abc")!!.revokedAt shouldBe later
        }
    }

    test("touchIfActive is a no-op (false, no stamp) once the row is revoked — the TOCTOU-safe conditional") {
        withRepo { repo ->
            repo.insert(row("abc"))
            repo.revoke("abc", later)
            repo.touchIfActive("abc", later).shouldBeFalse()
            repo.findById("abc")!!.lastUsedAt.shouldBeNull()
        }
    }

    test("touchIfActive is a no-op (false) once the row is expired at the stamp instant") {
        withRepo { repo ->
            repo.insert(
                row("abc").let {
                    ApiTokenRow(
                        it.id, it.secretHash, it.agentLabel, it.issuer, it.externalId, it.mode,
                        createdAt = created, lastUsedAt = null, expiresAt = created, revokedAt = null,
                    )
                },
            )
            // expires_at == created, stamp at `later` (> created): the exclusive `expires_at > :now` guard fails.
            repo.touchIfActive("abc", later).shouldBeFalse()
            repo.findById("abc")!!.lastUsedAt.shouldBeNull()
        }
    }

    test("touchIfActive on an unknown id reports false") {
        withRepo { repo ->
            repo.touchIfActive("nope", later).shouldBeFalse()
        }
    }

    test("all() returns the inserted rows' metadata, ordered by created_at") {
        withRepo { repo ->
            repo.insert(row("a"))
            repo.insert(
                row("b").let {
                    ApiTokenRow(
                        it.id, it.secretHash, it.agentLabel, it.issuer, it.externalId, it.mode,
                        createdAt = later, lastUsedAt = null, expiresAt = null, revokedAt = null,
                    )
                },
            )
            repo.all().map { it.id } shouldBe listOf("a", "b")
        }
    }

    test("the list metadata type exposes no secret_hash property (a future leak the return type forbids)") {
        // ApiTokenMeta carries no secret-bearing property — the list path's return type can't leak the hash.
        val props = com.plainbase.domain.repository.ApiTokenMeta::class.members
            .filterIsInstance<kotlin.reflect.KProperty<*>>()
            .map { it.name }
        props.none { it.contains("secret", ignoreCase = true) || it.contains("hash", ignoreCase = true) } shouldBe true
    }
})
