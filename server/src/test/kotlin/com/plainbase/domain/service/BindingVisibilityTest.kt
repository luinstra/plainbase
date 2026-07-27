package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.Witness
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

    val main = RootName.PRIMARY
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

    // ...but only against a KNOWN registry. With an empty one nothing is detached, because nothing is known -
    // the same guard `Supersession`'s detached arm carries. Without it the two sides answer differently on this
    // input (resolver: detached, so not an owner; gate: refuses to displace an unmaterialized incumbent), and a
    // resolver/bind disagreement is a `check(outcome is Bound)` crash rather than a wrong answer.
    test("an EMPTY registry detaches nothing: 'I do not know' is never a licence, on either side") {
        val unknown = Supersession(witnessed = emptySet(), scannedRoots = emptySet(), registeredRoots = emptySet())
        BindingVisibility.isLive(binding(gone), emptySet(), emptySet(), emptySet(), unknown) shouldBe true
        unknown.mayDisplace(binding(gone)) shouldBe false
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

    // [BindingVisibility.isOwner] = isLive + the path-reuse gate. The two passes each pin ONE row of this at the
    // pass level (a pasted copy, a moved page); the whole table belongs here, because the gate's entire job is
    // telling two different meanings of "the witnessed file carries no id" apart.
    //
    // NOT observable here, deliberately: a SHAPE-INVALID `id:` is collapsed to null by `PageId.of` when the
    // callers build the `Witness`, so by the time it reaches this predicate it is indistinguishable from a file
    // with no `id:` line at all - which is the correct bucket (an unparseable value does not keep the promise
    // materialization made) but cannot be asserted from here. Pin that at the pass level if it ever needs pinning.
    fun isOwner(owner: IdBinding, seen: Map<RootedPath, PageId?>) =
        BindingVisibility.isOwner(
            owner,
            seen.mapValues { (_, observed) -> Witness(observed) },
            scannedRoots,
            registered,
            Supersession(witnessed = seen.keys, scannedRoots = scannedRoots, registeredRoots = registered),
        )

    val here = RootedPath(main, page)
    val otherId = PageId.require("0197b555-1111-7222-8333-444455556666")

    test("isOwner: a witnessed file carrying the SAME id is the page witnessing itself - still the owner") {
        isOwner(binding(main), mapOf(here to id)) shouldBe true
    }

    // Re-identification, NOT path reuse: the page is still there, it is changing its own id. That TOMBSTONES the
    // old id rather than freeing it, so a claimant must lose the contest and reassign. Calling this "not an owner"
    // is what made the resolver promise an id the bind then refused, crashing the pass on an id swap.
    test("isOwner: a witnessed file carrying a DIFFERENT id is re-identification - the incumbent still owns the old id") {
        isOwner(binding(main, materialized = true), mapOf(here to otherId)) shouldBe true
        isOwner(binding(main, materialized = false), mapOf(here to otherId)) shouldBe true
    }

    // The row the whole gate exists for: NO id in the file means path reuse ONLY if the binding promised one.
    test("isOwner: a witnessed file carrying NO id is reuse only when the binding was MATERIALIZED") {
        isOwner(binding(main, materialized = true), mapOf(here to null)) shouldBe false
        isOwner(binding(main, materialized = false), mapOf(here to null)) shouldBe true
    }

    // Absence is not evidence: an unwitnessed owner never reaches the gate, so the D16/Supersession answer stands
    // exactly as it did before `isOwner` existed.
    test("isOwner: an UNWITNESSED owner short-circuits to the D16 rule, unchanged by the gate") {
        isOwner(binding(main, materialized = true), emptyMap()) shouldBe false // the accepted move residue
        isOwner(binding(main, materialized = false), emptyMap()) shouldBe true // the row is the ONLY record of its id
    }
})
