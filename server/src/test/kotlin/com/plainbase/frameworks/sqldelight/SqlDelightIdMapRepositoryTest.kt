package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.ktor.livePathOf
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
 * the WITHIN-root move supersede under `UNIQUE(id, root)` (per-root identity, C5 - the same id may live in
 * two roots, so a cross-root duplicate no longer supersedes), idempotent issue recording for every
 * [IdentityIssue] variant (natural key with the C2 root dimensions), and the direct-SQL
 * binary-at-rest assertion (`length(id) = 16` over the seeded table).
 *
 * **The supersede is GATED since C0.** It still happens - the rows below prove it - but only under a stated
 * [Supersession], because taking an id away from a binding is a NEGATIVE CLAIM about that page and a negative
 * claim needs authority. A bind that states none takes nothing (the [Supersession.NONE] row), and that is the
 * whole difference between a moved file keeping its id and a pasted one stealing it.
 */
class SqlDelightIdMapRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightIdMapRepository, SqlDriver) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)), driver)
        }

    val main = RootName.PRIMARY
    val extra = RootName.require("extra")
    val pathA = RootedPath(main, TreePath.require("guides/a.md"))
    val pathB = RootedPath(main, TreePath.require("notes/réunion.md"))
    val idX = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    // "Every page in play was READ this pass" - the ordinary full-visibility authority a rebuild resolves under,
    // and the one under which the key-complete supersede is legitimate.
    val witnessedAll = Supersession(
        witnessed = setOf(pathA, pathB, RootedPath(extra, pathA.path), RootedPath(extra, TreePath.require("mirror/page.md"))),
        scannedRoots = setOf(main, extra),
        registeredRoots = setOf(main, extra),
    )
    val idY = PageId.require("f47ac10b-58cc-4372-a567-0e02b2c3d479")

    test("bind/find round-trip, including the materialized flag and the live rooted path") {
        withRepo { repo, _ ->
            repo.find(pathA).shouldBeNull()
            repo.bind(pathA, idX, materialized = false)
            repo.bind(pathB, idY, materialized = true)

            repo.find(pathA) shouldBe IdBinding(pathA, idX, materialized = false)
            repo.find(pathB) shouldBe IdBinding(pathB, idY, materialized = true)
            repo.livePathOf(idX) shouldBe pathA
            repo.livePathOf(idY) shouldBe pathB
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
            repo.livePathOf(idX).shouldBeNull()
        }
    }

    test("an id moving to a new path supersedes its stale row (a moved file keeps its id)") {
        withRepo { repo, _ ->
            repo.bind(pathA, idX, materialized = true)
            repo.bind(pathB, idX, materialized = true, supersession = witnessedAll)
            repo.livePathOf(idX) shouldBe pathB
            repo.find(pathA).shouldBeNull()
            repo.bindings() shouldHaveSize 1
        }
    }

    // THE C0 GATE, at the SQL boundary. Identical to the row above but for the authority - and the row that used
    // to be a moved file keeping its id is, without one, a pasted file stealing it. bind() writes NOTHING.
    test("...but a bind that states NO authority takes the id from NOBODY: it is refused, and nothing is written") {
        withRepo { repo, _ ->
            repo.bind(pathA, idX, materialized = true)

            repo.bind(pathB, idX, materialized = true) shouldBe BindOutcome.Refused(idX, heldBy = pathA, retired = false)

            repo.livePathOf(idX) shouldBe pathA
            repo.find(pathB).shouldBeNull()
            repo.bindings() shouldHaveSize 1
        }
    }

    test("per-root identity (C5): the same relative path under ANOTHER root leaves BOTH bindings live - no cross-root supersede") {
        // The flip DELETES the key-complete CROSS-root supersede: (main, p) holds X and (extra, p) binds X too, and
        // BOTH survive - the same id lives in two roots (the policy-level pin lives in IndexBuilderMultiRootTest).
        // The second bind uses the DEFAULT Supersession.NONE, so it succeeds because the roots differ, NOT via any
        // displacement authority. bindings() has 2 rows and bindingInRoot returns each root's own path.
        withRepo { repo, _ ->
            val mirrored = RootedPath(extra, pathA.path)
            repo.bind(pathA, idX, materialized = true)
            repo.bind(mirrored, idX, materialized = true) shouldBe BindOutcome.Bound
            repo.bindingInRoot(main, idX)?.path shouldBe pathA
            repo.bindingInRoot(extra, idX)?.path shouldBe mirrored
            repo.find(pathA) shouldBe IdBinding(pathA, idX, materialized = true)
            repo.bindings() shouldHaveSize 2
        }
    }

    test("the WITHIN-root move supersede is atomic: a failed upsert rolls the stale-unbind back, never orphaning the id") {
        // Crash-shaped fault injection below the repository: the upsert throws inside bind's transaction, so the
        // root-scoped stale-unbind (unbindStaleInRoot) it rides with must roll back too. A committed delete with no
        // replacement row would leave the id bound to NOBODY - the page silently loses its permalink, and the
        // caller's duplicate policy (which reads the binding) can no longer see that anyone ever owned it. The move
        // is WITHIN one root (post-flip a cross-root duplicate is legal, so only a within-root move supersedes).
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
            val moved = RootedPath(main, TreePath.require("mirror/page.md")) // within main: the flip only supersedes within a root
            repo.bind(moved, idX, materialized = true)

            failUpsert = true
            shouldThrowAny { repo.bind(pathA, idX, materialized = true, supersession = witnessedAll) }

            // All-or-nothing: the prior owner's row survived, and nothing half-landed.
            repo.livePathOf(idX) shouldBe moved
            repo.find(pathA).shouldBeNull()

            failUpsert = false
            repo.bind(pathA, idX, materialized = true, supersession = witnessedAll)
            repo.livePathOf(idX) shouldBe pathA
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

    test("other_root is the sentinel for EVERY surviving kind: none of them names a second root (direct SQL)") {
        withRepo { repo, driver ->
            listOf(
                IdentityIssue.DuplicateId(idX, root = main, keptPath = pathA.path, reassignedPath = pathB.path),
                IdentityIssue.PatchRefused(main, pathA.path, "frontmatter keys must be plain unquoted scalars"),
                IdentityIssue.RedirectConflict(main, pathB.path, "alias shadowed by live canonical path"),
                IdentityIssue.PathCollision(root = main, keptPath = pathB.path, loserRawName = "re\u0301union.md"),
                IdentityIssue.PathSlugCollision(root = extra, keptPath = pathA.path, loserPath = pathB.path),
            ).forEach(repo::record)
            // The row count is the anti-vacuity anchor. Without it the sentinel assertion below is also satisfied by
            // an EMPTY table, so a record() that silently stopped writing would read as a green invariant. It also
            // pins that this really is EVERY kind: add a sixth and this count fails until the kind is covered here.
            driver.queryLong("SELECT count(*) FROM identity_issue") shouldBe IdentityIssue.Kind.entries.size.toLong()
            driver.queryLong("SELECT count(*) FROM identity_issue WHERE other_root != ''") shouldBe 0L
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
