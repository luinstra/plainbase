package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * **404-vs-503, in the domain, in ONE place** (C1). Every consumer of an absent read asks this; none re-derives
 * it, and no adapter may - the `LocalContentStore` "indexed-only visibility" policy is this same principle
 * implemented one layer too low, against the SCAN's snapshot instead of against the durable index, and that is
 * precisely how a page safe on an unmounted disk came to answer 404.
 *
 * > A read for a page the durable index HAS, whose bytes the store cannot produce, is **503**.
 * > A **404** only for a page the index does not have.
 *
 * There is no `stat` here, no `fileKey`, no `isBlank`, and no `available()` - not because they are expensive but
 * because they are the WRONG KIND OF FACT. Every one of them is a probe of the filesystem, and the filesystem is
 * exactly what cannot answer the question: an empty mount point, a deliberately emptied root, a partial restore
 * and a decoy tree produce IDENTICAL observations (the theorem C0 rests on). The `id_map` is not a probe. It is a
 * durable record of a page we bound, and it is the only party to this that KNOWS something.
 *
 * So ledger A4 closes by REMOVING a check rather than adding one.
 *
 * A page whose binding this returns [ContentRead.AbsenceUnknown] for is in LIMBO ([com.plainbase.domain.root
 * .RootLimbo] is the same set, derived per pass for reporting). It is not a deletion, and it must never become
 * one by accident: the consumer matrix in C1 makes every arm end in "come back later" rather than in "it's gone",
 * and [AbsenceUnverified] is the carrier that says so on the wire.
 */
class AbsenceClassifier(private val idMap: IdMapRepository) {

    /** The ONE rule, over a store read that has already happened. */
    fun classify(target: RootedPath, read: StoreRead): ContentRead = when (read) {
        is StoreRead.Bytes -> ContentRead.Bytes(read.bytes)
        StoreRead.RootDown -> ContentRead.RootDown
        StoreRead.NoBytes -> absenceAt(target)
    }

    /** Read [target] from [store] and classify it - the shape almost every consumer wants. */
    fun read(store: ContentStore, target: RootedPath): ContentRead = classify(target, store.readClassified(target.path))

    /**
     * The rule ALONE, for an absence a consumer observed some other way than through [ContentStore.readClassified]:
     * a `CasResult.Deleted` (the CAS resolved no file to swap), a page missing from a published snapshot. Those are
     * the same NO-BYTES fact arriving by a different road, and they get the same answer - which is the whole reason
     * this is one classifier and not a rule copied into each caller.
     */
    fun absenceAt(target: RootedPath): ContentRead =
        if (idMap.find(target) != null) ContentRead.AbsenceUnknown else ContentRead.ConfirmedAbsent

    /**
     * The id-addressed twin: the page is not in the snapshot the pass published - does the durable index still
     * BIND that id? Same rule, same authority, keyed by the identity rather than by the location, because an
     * id-addressed read (`/api/v1/pages/{id}`, `/p/{id}`) has no path to ask about until the index gives it one.
     */
    fun absenceOf(id: PageId): ContentRead =
        if (idMap.pathOf(id) != null) ContentRead.AbsenceUnknown else ContentRead.ConfirmedAbsent

    /**
     * **The gate every id-addressed surface owes, and the shape the 404 lie actually took.** A page MISSING FROM THE
     * SNAPSHOT while the durable index still BINDS it is not a page that does not exist - it is a page the last pass
     * could not read - and each of the three guarded facades used to resolve exactly that state into its own flavor
     * of "gone": a 404 on a read, a `page_deleted` conflict on a write, a `StaleBase` on a propose. Three surfaces,
     * three vocabularies, ONE wrong belief.
     *
     * So the belief is checked in ONE place, and it throws rather than returning: an "it's gone" answer must not be
     * reachable from here by forgetting to look at the result. A miss whose id the index does NOT bind passes
     * straight through - that is a genuine 404, and the caller's own not-found outcome is right.
     *
     * [root] is the id's OWNING root, already resolved and already availability-gated by the caller: root-down
     * outranks absence (if the disk is gone we know nothing at all), so `RootUnavailable` fires first and this is
     * only ever asked about a root that is UP.
     */
    fun requireVerifiedAbsence(root: RootName, id: PageId, snapshot: PageIndex) {
        if (id in snapshot.byId) return
        if (absenceOf(id) == ContentRead.AbsenceUnknown) throw AbsenceUnverified(root, id.value)
    }
}

/**
 * **The bytes are not there, and we cannot prove they are gone** - the [ContentRead.AbsenceUnknown] carrier, and
 * the twin of [RootUnavailable] for the case where the ROOT is perfectly healthy and one PAGE is in doubt.
 *
 * Mapped in the same two places `RootUnavailable` is (the `guarded {}` funnel and the MCP catch funnels) to
 * **503 `absence_unverified` + `Retry-After`** - and it is a DISTINCT code, deliberately. Reporting a limbo page
 * as `root_unavailable` would be a second lie on top of the first: the disk is fine, the other pages of that root
 * are serving normally, and an operator sent to remount a healthy volume is an operator sent to fix nothing.
 *
 * It also self-heals on its own, which `root_unavailable` never does: the page is witnessed again, it drops out
 * of limbo, and no code runs. Hence the SHORT retry window.
 *
 * [subject] is the page id or the rooted path the caller was asking about - a message, never a decision.
 */
class AbsenceUnverified(val root: RootName, val subject: String) : RuntimeException(
    "the absence of '$subject' in root '${root.value}' is UNVERIFIED: the durable index still binds it, and the " +
        "store cannot produce its bytes. It is neither present nor proven deleted - do not treat it as gone.",
) {
    constructor(target: RootedPath) : this(target.root, target.path.value)
}
