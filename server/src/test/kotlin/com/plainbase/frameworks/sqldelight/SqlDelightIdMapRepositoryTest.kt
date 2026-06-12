package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdBinding
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * SqlDelightIdMapRepository over an in-memory SQLite db: binding round-trips, the move-supersede
 * rule behind UNIQUE(id), idempotent issue recording for every [IdentityIssue] variant, and the
 * direct-SQL binary-at-rest assertion (`length(id) = 16` over the seeded table).
 */
class SqlDelightIdMapRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightIdMapRepository, SqlDriver) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)), driver)
        }

    val pathA = TreePath.require("guides/a.md")
    val pathB = TreePath.require("notes/réunion.md")
    val idX = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val idY = PageId.require("f47ac10b-58cc-4372-a567-0e02b2c3d479")

    test("bind/find round-trip, including the materialized flag and pathOf") {
        withRepo { repo, _ ->
            repo.find(pathA).shouldBeNull()
            repo.bind(pathA, idX, materialized = false)
            repo.bind(pathB, idY, materialized = true)

            repo.find(pathA) shouldBe IdBinding(pathA, idX, materialized = false)
            repo.find(pathB) shouldBe IdBinding(pathB, idY, materialized = true)
            repo.pathOf(idX) shouldBe pathA
            repo.pathOf(idY) shouldBe pathB
            repo.bindings() shouldContainExactlyInAnyOrder listOf(
                IdBinding(pathA, idX, false),
                IdBinding(pathB, idY, true),
            )
        }
    }

    test("markMaterialized flips only the flag") {
        withRepo { repo, _ ->
            repo.bind(pathA, idX, materialized = false)
            repo.markMaterialized(pathA)
            repo.find(pathA) shouldBe IdBinding(pathA, idX, materialized = true)
        }
    }

    test("rebinding a path to a new id replaces the binding (duplicate reassignment)") {
        withRepo { repo, _ ->
            repo.bind(pathA, idX, materialized = false)
            repo.bind(pathA, idY, materialized = false)
            repo.find(pathA) shouldBe IdBinding(pathA, idY, false)
            repo.pathOf(idX).shouldBeNull()
        }
    }

    test("an id moving to a new path supersedes its stale row (a moved file keeps its id)") {
        withRepo { repo, _ ->
            repo.bind(pathA, idX, materialized = true)
            repo.bind(pathB, idX, materialized = true)
            repo.pathOf(idX) shouldBe pathB
            repo.find(pathA).shouldBeNull()
            repo.bindings() shouldHaveSize 1
        }
    }

    test("every IdentityIssue variant survives the record/issues round-trip") {
        withRepo { repo, _ ->
            val all = listOf(
                IdentityIssue.DuplicateId(idX, keptPath = pathA, reassignedPath = pathB),
                IdentityIssue.PatchRefused(pathA, "frontmatter keys must be plain unquoted scalars"),
                IdentityIssue.RedirectConflict(pathB, "alias shadowed by live canonical path"),
                IdentityIssue.PathCollision(keptPath = pathA, collidingPath = pathB),
                IdentityIssue.PathSlugCollision(keptPath = pathA, loserPath = pathB),
            )
            all.forEach(repo::record)
            repo.issues() shouldContainExactly all
        }
    }

    test("recording the same issue twice keeps one row (re-running adopt never grows the list)") {
        withRepo { repo, _ ->
            val issue = IdentityIssue.DuplicateId(idX, keptPath = pathA, reassignedPath = pathB)
            repo.record(issue)
            repo.record(issue)
            repo.issues() shouldContainExactly listOf(issue)
        }
    }

    test("re-recording an issue whose message changed refreshes it: one row, current guidance") {
        withRepo { repo, driver ->
            repo.record(IdentityIssue.PatchRefused(pathA, "frontmatter keys must be plain unquoted scalars"))
            repo.record(IdentityIssue.PatchRefused(pathA, "frontmatter block has no terminating delimiter"))
            // Same natural key, so no second row — but issues() must surface the CURRENT reason,
            // not the one a stale OR IGNORE would have pinned forever.
            repo.issues() shouldContainExactly
                listOf(IdentityIssue.PatchRefused(pathA, "frontmatter block has no terminating delimiter"))
            driver.queryLong("SELECT count(*) FROM identity_issue") shouldBe 1L
        }
    }

    test("dedup holds for variants with absent key columns (the SQLite NULL-distinct-in-UNIQUE trap)") {
        withRepo { repo, driver ->
            // PathCollision has no page_id and PatchRefused additionally has no other_path: if those
            // persisted as NULL, SQLite's UNIQUE index would treat every row as distinct and the
            // schema-enforced dedup would silently pass duplicates through.
            val collision = IdentityIssue.PathCollision(keptPath = pathA, collidingPath = pathB)
            val refusal = IdentityIssue.PatchRefused(pathB, "frontmatter keys must be plain unquoted scalars")
            repeat(2) {
                repo.record(collision)
                repo.record(refusal)
            }
            repo.issues() shouldContainExactlyInAnyOrder listOf(collision, refusal)
            // Direct SQL: absent key fields are the sentinels ('' / zero-length BLOB), never NULL.
            driver.queryLong("SELECT count(*) FROM identity_issue WHERE other_path IS NULL OR page_id IS NULL") shouldBe 0L
            driver.queryLong("SELECT count(*) FROM identity_issue WHERE length(page_id) NOT IN (0, 16)") shouldBe 0L
        }
    }

    test("binary at rest: every stored id_map.id is exactly 16 bytes (direct SQL, below the adapter)") {
        withRepo { repo, driver ->
            repo.bind(pathA, idX, materialized = false)
            repo.bind(pathB, idY, materialized = true)
            driver.queryLong("SELECT count(*) FROM id_map") shouldBe 2L
            driver.queryLong("SELECT count(*) FROM id_map WHERE length(id) != 16") shouldBe 0L
        }
    }
})
