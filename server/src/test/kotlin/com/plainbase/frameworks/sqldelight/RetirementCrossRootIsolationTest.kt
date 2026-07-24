package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * The C2 cross-root isolation of the proof-apply deletes, LATENT until C5 legalizes duplicate ids: two rows that
 * share an id X but live under different roots must not die together. Retiring `(main, X)`'s binding reaps ONLY
 * main's `dirty_page` + `page_checkpoint` rows; extra's same-id rows SURVIVE, because the deletes are now scoped
 * `WHERE root = :root AND id = :id` rather than the bare `WHERE id = :id` C5 will make ambiguous.
 *
 * The `(extra, X)` rows are seeded DIRECTLY at the repositories: pre-flip `id_map UNIQUE(id)` forbids a second
 * binding for X, so the two rows only coexist because the re-keyed tables' PK is now `(root, id)`.
 *
 * RED + back-out: revert `SqlDelightRetirementRepository`'s deletes to `checkpoints.deleteRow(binding.id)` /
 * `dirty.deleteById(binding.id)` (and the `.sq` to `WHERE id = :id`); retiring main then wipes extra's rows and
 * the survival assertions FAIL. Root-scoping greens them.
 */
class RetirementCrossRootIsolationTest : FunSpec({

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val x = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val mainPath = RootedPath(main, TreePath.require("notes/x.md"))
    val extraPath = RootedPath(extra, TreePath.require("archive/x.md"))

    test("retiring (main, X) reaps only main's dirty_page + page_checkpoint rows; extra's same-id rows survive") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val idMap = SqlDelightIdMapRepository(db)
            val dirty = SqlDelightDirtyPageRepository(db)
            val checkpoints = SqlDelightPageCheckpointRepository(db)
            val retirements = SqlDelightRetirementRepository(db)

            // The one legal live binding for X (main). extra's rows are seeded at the repos directly - a second
            // id_map binding for X is impossible under UNIQUE(id).
            idMap.bind(mainPath, x, materialized = false)
            dirty.mark(x, mainPath, expectedHash = "sha256:main", stage = Stage.WRITING)
            dirty.mark(x, extraPath, expectedHash = "sha256:extra", stage = Stage.WRITING)
            // ONE replace carries BOTH roots' rows: replace() truncates the whole table, so two sequential
            // per-root calls would wipe the first root's row.
            checkpoints.replace(
                mapOf(
                    RootedPageId(main, x) to TreePath.require("notes/x"),
                    RootedPageId(extra, x) to TreePath.require("archive/x"),
                ),
            )

            val observation = retirements.observation(main) // mint main's freshness token; the proof stamps it
            val proof = AbsenceProof(
                root = main,
                source = ProofSource.EPOCH,
                observationId = observation,
                bindingEpoch = retirements.bindingEpoch(main), // the epoch the bind above left; the two-token gate re-checks it
                covers = setOf(BindingRef(mainPath.path, x)),
            )
            // witnessed EXCLUDES X so the refutation does not veto the proof; advances is the C4 checkpoint list, empty.
            val retired = retirements.applyProofs(proofs = listOf(proof), witnessed = emptySet(), advances = emptyList())

            retired shouldContainExactly setOf(RootedPageId(main, x))
            dirty.get(RootedPageId(main, x)).shouldBeNull()
            dirty.get(RootedPageId(extra, x)).shouldNotBeNull()
            checkpoints.load().keys shouldNotContain RootedPageId(main, x)
            checkpoints.load().keys shouldContain RootedPageId(extra, x)
        }
    }
})
