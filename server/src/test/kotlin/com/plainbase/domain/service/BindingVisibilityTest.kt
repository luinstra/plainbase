package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The D16 classification table and the C0 supersession gate, tested DIRECTLY: this is where the truth table
 * lives; the pass-level cases in IndexBuilderMultiRootTest/AdoptionPassTest prove each pass actually routes
 * through it.
 *
 * The whole point of C0 is the difference between the two sets these rules take: `scannedRoots` says which
 * roots the pass WALKED, and `witnessed` says which PAGES it actually READ. Every bug in the ledger's
 * data-loss class lives in the gap between those two sentences.
 */
class BindingVisibilityTest : FunSpec({

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val gone = RootName.require("gone")
    val page = TreePath.require("guides/a.md")
    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    val witnessed = setOf(RootedPath(main, page))
    val scannedRoots = setOf(main)
    val registered = setOf(main, extra)
    val supersession = Supersession(witnessed = witnessed, scannedRoots = scannedRoots, registeredRoots = registered)

    fun binding(root: RootName, path: TreePath = page, materialized: Boolean = true) =
        IdBinding(RootedPath(root, path), id, materialized)

    fun isLive(owner: IdBinding, gate: Supersession = supersession) =
        BindingVisibility.isLive(owner, witnessed, scannedRoots, registered, gate)

    test("witnessed page: a live owner") {
        isLive(binding(main)) shouldBe true
    }

    test("scanned root, MATERIALIZED page not found: the accepted move residue - not an owner, the id travels") {
        isLive(binding(main, TreePath.require("moved.md"), materialized = true)) shouldBe false
    }

    test("scanned root, UNMATERIALIZED page not found: an untouchable owner - that row is the ONLY record of its id") {
        isLive(binding(main, TreePath.require("moved.md"), materialized = false)) shouldBe true
    }

    test("unscanned-but-registered root: always an untouchable live owner") {
        isLive(binding(extra)) shouldBe true
    }

    test("unregistered root: detached, not an owner at all (D2 - the boot WARN is its visibility)") {
        isLive(binding(gone)) shouldBe false
    }

    // The OTHER half: being a live owner and being TAKEABLE are different questions. Taking an id away is a
    // NEGATIVE claim about the incumbent, and it needs positive evidence.
    test("a WITNESSED incumbent is supersedable - we are looking at the file, so rank may settle the contest") {
        supersession.mayDisplace(binding(main)) shouldBe true
    }

    test("an UNSCANNED root's binding is NON-supersedable, however the two roots rank (D-C4-10)") {
        supersession.mayDisplace(binding(extra)) shouldBe false
    }

    test("an unwitnessed MATERIALIZED incumbent is supersedable: its id lives in the file and may travel with it") {
        supersession.mayDisplace(binding(main, TreePath.require("moved.md"), materialized = true)) shouldBe true
    }

    // THE C0 GATE. The id was never in the file, so a file elsewhere carrying it CANNOT be this page moved - and
    // this row is the only record the id ever existed. Hard-delete it and the permalink is gone forever.
    test("an unwitnessed UNMATERIALIZED incumbent is NEVER supersedable - the one loss nothing can undo") {
        supersession.mayDisplace(binding(main, TreePath.require("moved.md"), materialized = false)) shouldBe false
    }

    test("...unless a PROOF covers it: an absence proof is authority, and it is the only other thing that is") {
        val moved = TreePath.require("moved.md")
        val proven = Supersession(
            witnessed = witnessed,
            scannedRoots = scannedRoots,
            registeredRoots = registered,
            proven = setOf(BindingRef(moved, id)),
        )
        proven.mayDisplace(binding(main, moved, materialized = false)) shouldBe true
    }

    // A DETACHED row is not an owner (isLive says so above), so there is no negative claim here to authorize -
    // the operator made it themselves, deliberately, when they took the root out of `roots {}`. It must stay
    // sweepable or the resolver and this gate would disagree, and a live claimant would be denied an id that
    // nobody is holding.
    test("a DETACHED root's binding is sweepable - the operator already made the claim by removing the root (D2)") {
        supersession.mayDisplace(binding(gone)) shouldBe true
    }

    test("Supersession.NONE displaces nothing at all - an unstated authority is no authority") {
        Supersession.NONE.mayDisplace(binding(main)) shouldBe false
        Supersession.NONE.mayDisplace(binding(gone)) shouldBe false // ...and it cannot call anything detached either
    }
})
