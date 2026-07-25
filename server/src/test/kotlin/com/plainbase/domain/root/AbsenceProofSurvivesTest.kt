package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The R12 unit for [AbsenceProof.survives] - the §5.1 witness signature at the domain level, PER-ROOT (C5). The
 * witness is keyed by [RootedPageId], so an id read in root B refutes only an absence claimed in root B; a stale copy
 * of one root's corpus mounted under another no longer holds up this root's legitimate deletes. A non-inferred
 * (`OPERATOR`/`API_DELETE`) proof survives whole regardless of the witness.
 */
class AbsenceProofSurvivesTest : FunSpec({

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val x = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val y = PageId.require("0197b555-1111-7222-8333-444455556666")

    fun proof(source: ProofSource, root: RootName, vararg ids: PageId) = AbsenceProof(
        root = root,
        source = source,
        observationId = ObservationId(1),
        bindingEpoch = BindingEpoch(0),
        covers = ids.mapTo(mutableSetOf()) { BindingRef(TreePath.require("guides/gone.md"), it) },
    )

    test("an INFERRED proof for root A is refuted by A's own witness of the id - survives is null") {
        proof(ProofSource.EPOCH, main, x).survives(setOf(RootedPageId(main, x))).shouldBeNull()
    }

    test("per-root: the SAME id witnessed under root B does NOT refute an inferred absence claimed in root A") {
        val p = proof(ProofSource.EPOCH, main, x)
        // Bare-id membership would refute this (the pre-C5 bug); the per-root witness keeps it alive.
        p.survives(setOf(RootedPageId(extra, x))) shouldBe p
    }

    test("partial: only the witnessed id is removed; the rest of the proof survives") {
        val survivor = proof(ProofSource.EPOCH, main, x, y).survives(setOf(RootedPageId(main, x)))
        survivor.shouldNotBeNull()
        survivor.covers.map { it.id } shouldContainExactlyInAnyOrder listOf(y)
    }

    test("a NON-inferred proof (OPERATOR) survives whole even when its own root witnesses the id - caused, not inferred") {
        val p = proof(ProofSource.OPERATOR, main, x)
        p.survives(setOf(RootedPageId(main, x))) shouldBe p
    }

    test("an empty witness never refutes anything, whatever the source") {
        val p = proof(ProofSource.EPOCH, main, x)
        p.survives(emptySet()) shouldBe p
    }
})
