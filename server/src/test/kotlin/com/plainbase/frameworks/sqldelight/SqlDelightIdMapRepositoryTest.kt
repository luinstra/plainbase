package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * SqlDelightIdMapRepository over an in-memory SQLite db: composite (root, path) binding round-trips,
 * the key-complete move/cross-root supersede behind UNIQUE(id), idempotent issue recording for every
 * [IdentityIssue] variant (natural key with the C2 root dimensions), and the direct-SQL
 * binary-at-rest assertion (`length(id) = 16` over the seeded table).
 */
class SqlDelightIdMapRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightIdMapRepository, SqlDriver) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)), driver)
        }

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val pathA = RootedPath(main, TreePath.require("guides/a.md"))
    val pathB = RootedPath(main, TreePath.require("notes/réunion.md"))
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

    test("the composite key is per root: the same relative path binds independently under two roots") {
        withRepo { repo, _ ->
            val mirrored = RootedPath(extra, pathA.path)
            repo.bind(pathA, idX, materialized = false)
            repo.bind(mirrored, idY, materialized = true)
            repo.find(pathA) shouldBe IdBinding(pathA, idX, false)
            repo.find(mirrored) shouldBe IdBinding(mirrored, idY, true)
            repo.roots() shouldBe setOf(main, extra)
        }
    }

    test("roots() is empty on a fresh db and distinct over bindings") {
        withRepo { repo, _ ->
            repo.roots() shouldBe emptySet()
            repo.bind(pathA, idX, materialized = false)
            repo.bind(pathB, idY, materialized = false)
            repo.roots() shouldBe setOf(main)
        }
    }

    test("markMaterialized flips only the flag") {
        withRepo { repo, _ ->
            repo.bind(pathA, idX, materialized = false)
            repo.markMaterialized(pathA)
            repo.find(pathA) shouldBe IdBinding(pathA, idX, materialized = true)
        }
    }

    test("rebinding a key to a new id replaces the binding (duplicate reassignment)") {
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

    test("unbindStale is key-complete: binding the same relative path under another root supersedes, never a UNIQUE(id) crash") {
        // The SQL-level pin of the round-1 panel crash case (the policy-level pin lives in
        // IndexBuilderMultiRootTest): (main, p) holds X, then (extra, p) binds X.
        withRepo { repo, _ ->
            val mirrored = RootedPath(extra, pathA.path)
            repo.bind(pathA, idX, materialized = true)
            repo.bind(mirrored, idX, materialized = true)
            repo.pathOf(idX) shouldBe mirrored
            repo.find(pathA).shouldBeNull()
            repo.bindings() shouldHaveSize 1
        }
    }

    test("the key-complete supersede is atomic: a failed upsert rolls the stale-unbind back, never orphaning the id") {
        // Crash-shaped fault injection below the repository: the upsert throws inside bind's transaction,
        // so the key-complete DELETE it rides with must roll back too. A committed delete with no
        // replacement row would leave the id bound to NOBODY - the page silently loses its permalink, and
        // the caller's duplicate policy (which reads pathOf) can no longer see that anyone ever owned it.
        DatabaseFactory.createInMemoryDriver().use { real ->
            var failUpsert = false
            val driver = object : SqlDriver by real {
                override fun execute(
                    identifier: Int?,
                    sql: String,
                    parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<Long> {
                    if (failUpsert && "INTO id_map" in sql) error("injected crash before the replacement binding lands")
                    return real.execute(identifier, sql, parameters, binders)
                }
            }
            val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
            val moved = RootedPath(extra, TreePath.require("mirror/page.md"))
            repo.bind(moved, idX, materialized = true)

            failUpsert = true
            shouldThrowAny { repo.bind(pathA, idX, materialized = true) }

            // All-or-nothing: the prior owner's row survived, and nothing half-landed.
            repo.pathOf(idX) shouldBe moved
            repo.find(pathA).shouldBeNull()

            failUpsert = false
            repo.bind(pathA, idX, materialized = true)
            repo.pathOf(idX) shouldBe pathA
            repo.bindings() shouldHaveSize 1
        }
    }

    test("every IdentityIssue variant survives the record/issues round-trip") {
        withRepo { repo, _ ->
            val all = listOf(
                IdentityIssue.DuplicateId(idX, root = main, keptPath = pathA.path, reassignedPath = pathB.path),
                IdentityIssue.PatchRefused(main, pathA.path, "frontmatter keys must be plain unquoted scalars"),
                IdentityIssue.RedirectConflict(main, pathB.path, "alias shadowed by live canonical path"),
                IdentityIssue.PathCollision(root = main, keptPath = pathB.path, loserRawName = "re\u0301union.md"),
                IdentityIssue.PathSlugCollision(root = main, keptPath = pathA.path, loserPath = pathB.path),
                IdentityIssue.CrossRootDuplicateId(idY, kept = RootedPath(extra, pathA.path), reassigned = pathA),
            )
            all.forEach(repo::record)
            repo.issues() shouldContainExactly all
        }
    }

    test("a path_collision keeps the NFD loser's raw name verbatim — never normalized back into keptPath") {
        withRepo { repo, _ ->
            // The exact case the issue exists for: NFC/NFD siblings. The kept TreePath is NFC by
            // construction; the loser's raw NFD name must survive persistence un-normalized,
            // otherwise the issue degenerates to keptPath == loser and stops being actionable.
            val loserRawName = "re\u0301union.md" // e + combining acute — raw on-disk NFD bytes
            repo.record(IdentityIssue.PathCollision(root = main, keptPath = pathB.path, loserRawName = loserRawName))

            val issue = repo.issues().filterIsInstance<IdentityIssue.PathCollision>().single()
            issue.keptPath shouldBe pathB.path
            issue.loserRawName shouldBe loserRawName
            issue.loserRawName shouldNotBe issue.keptPath.name
        }
    }

    test("recording the same issue twice keeps one row (re-running adopt never grows the list)") {
        withRepo { repo, _ ->
            val issue = IdentityIssue.DuplicateId(idX, root = main, keptPath = pathA.path, reassignedPath = pathB.path)
            repo.record(issue)
            repo.record(issue)
            repo.issues() shouldContainExactly listOf(issue)
        }
    }

    test("the natural key distinguishes roots: the same paths under different roots are two issue rows") {
        withRepo { repo, _ ->
            val underMain = IdentityIssue.DuplicateId(idX, root = main, keptPath = pathA.path, reassignedPath = pathB.path)
            val underExtra = IdentityIssue.DuplicateId(idX, root = extra, keptPath = pathA.path, reassignedPath = pathB.path)
            repo.record(underMain)
            repo.record(underExtra)
            repo.issues() shouldContainExactlyInAnyOrder listOf(underMain, underExtra)
        }
    }

    test("a cross-root issue dedups on its full (kept, reassigned) rooted key") {
        withRepo { repo, driver ->
            val issue = IdentityIssue.CrossRootDuplicateId(idX, kept = RootedPath(extra, pathA.path), reassigned = pathA)
            repo.record(issue)
            repo.record(issue)
            repo.issues() shouldContainExactly listOf(issue)
            driver.queryLong("SELECT count(*) FROM identity_issue") shouldBe 1L
        }
    }

    test("re-recording an issue whose message changed refreshes it: one row, current guidance") {
        withRepo { repo, driver ->
            repo.record(IdentityIssue.PatchRefused(main, pathA.path, "frontmatter keys must be plain unquoted scalars"))
            repo.record(IdentityIssue.PatchRefused(main, pathA.path, "frontmatter block has no terminating delimiter"))
            // Same natural key, so no second row — but issues() must surface the CURRENT reason,
            // not the one a stale OR IGNORE would have pinned forever.
            repo.issues() shouldContainExactly
                listOf(IdentityIssue.PatchRefused(main, pathA.path, "frontmatter block has no terminating delimiter"))
            driver.queryLong("SELECT count(*) FROM identity_issue") shouldBe 1L
        }
    }

    test("dedup holds for variants with absent key columns (the SQLite NULL-distinct-in-UNIQUE trap)") {
        withRepo { repo, driver ->
            // PathCollision has no page_id and PatchRefused additionally has no other_path: if those
            // persisted as NULL, SQLite's UNIQUE index would treat every row as distinct and the
            // schema-enforced dedup would silently pass duplicates through. other_root joins the key
            // in C2 with the same sentinel rule.
            val collision = IdentityIssue.PathCollision(root = main, keptPath = pathB.path, loserRawName = "re\u0301union.md")
            val refusal = IdentityIssue.PatchRefused(main, pathB.path, "frontmatter keys must be plain unquoted scalars")
            repeat(2) {
                repo.record(collision)
                repo.record(refusal)
            }
            repo.issues() shouldContainExactlyInAnyOrder listOf(collision, refusal)
            // Direct SQL: absent key fields are the sentinels ('' / zero-length BLOB), never NULL.
            driver.queryLong(
                "SELECT count(*) FROM identity_issue WHERE other_root IS NULL OR other_path IS NULL OR page_id IS NULL",
            ) shouldBe
                0L
            driver.queryLong("SELECT count(*) FROM identity_issue WHERE length(page_id) NOT IN (0, 16)") shouldBe 0L
        }
    }

    test("other_root is the sentinel for every same-root kind and the real root for the cross-root kind (direct SQL)") {
        withRepo { repo, driver ->
            repo.record(IdentityIssue.DuplicateId(idX, root = main, keptPath = pathA.path, reassignedPath = pathB.path))
            repo.record(IdentityIssue.CrossRootDuplicateId(idY, kept = RootedPath(extra, pathA.path), reassigned = pathA))
            driver.queryLong("SELECT count(*) FROM identity_issue WHERE kind != 'CROSS_ROOT_DUPLICATE_ID' AND other_root != ''") shouldBe 0L
            driver.queryLong("SELECT count(*) FROM identity_issue WHERE kind = 'CROSS_ROOT_DUPLICATE_ID' AND other_root = 'main'") shouldBe
                1L
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
