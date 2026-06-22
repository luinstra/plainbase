package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.AuditEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * SqlDelightAuditRepository over an in-memory SQLite db (A3 WI 1): record→recent round-trip with NO field loss
 * (incl. the nullable issuer/external_id for an anonymous principal), and `recent` returns newest-first capped.
 */
class SqlDelightAuditRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightAuditRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightAuditRepository(DatabaseFactory.createDatabase(driver)))
        }

    fun entry(id: String, ts: Long, kind: String, issuer: String?, externalId: String?, decision: String) =
        AuditEntry(
            id = id,
            ts = Instant.fromEpochMilliseconds(ts),
            principalKind = kind,
            issuer = issuer,
            externalId = externalId,
            action = "EDIT",
            resource = "page-1",
            decision = decision,
        )

    test("record then recent returns the entry with no field loss (human + anonymous)") {
        withRepo { repo ->
            repo.record(entry("1", 1_000, "human", "builtin", "alice", "allowed"))
            repo.record(entry("2", 2_000, "anonymous", null, null, "denied"))
            val rows = repo.recent(10)
            rows shouldHaveSize 2
            val human = rows.single { it.id == "1" }
            human.principalKind shouldBe "human"
            human.issuer shouldBe "builtin"
            human.externalId shouldBe "alice"
            human.action shouldBe "EDIT"
            human.resource shouldBe "page-1"
            human.decision shouldBe "allowed"
            human.ts shouldBe Instant.fromEpochMilliseconds(1_000)
            val anon = rows.single { it.id == "2" }
            anon.issuer.shouldBeNull()
            anon.externalId.shouldBeNull()
            anon.decision shouldBe "denied"
        }
    }

    test("recent returns newest-first, capped at the limit") {
        withRepo { repo ->
            repo.record(entry("old", 1_000, "human", "builtin", "a", "allowed"))
            repo.record(entry("mid", 2_000, "human", "builtin", "a", "allowed"))
            repo.record(entry("new", 3_000, "human", "builtin", "a", "allowed"))
            repo.recent(2).map { it.id } shouldBe listOf("new", "mid")
        }
    }
})
