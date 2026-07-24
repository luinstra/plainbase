package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.ClaimantState
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RetiredBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * The C4 test FAKE: an [IdMapRepository] that, for ONE [ambiguousId], reports a chosen set of live/retired roots and
 * delegates EVERYTHING else to [real]. It is the ONLY way to exercise, under `UNIQUE(id)`, the resolver's Ambiguous
 * arm AND the cross-root MOVE window (a page present in the snapshot under root A while the durable claimant list
 * says root B) - the real adapter cannot bind a second live row for one id.
 *
 * Threaded into BOTH the [com.plainbase.domain.service.PageRootResolver] AND the
 * [com.plainbase.domain.service.AbsenceClassifier] (the `testRouteContext(resolver=, absence=)` seam / the MCP
 * factories), so the resolver's One/Ambiguous/None AND the classifier's 503 limbo path both read the same fake
 * `rootsHoldingId`. A resolver-only fake would leave `requireVerifiedAbsence` reading the REAL list and answering 404
 * where the window owes a 503 (the REV14 FIX-1 defect).
 */
class AmbiguousIdMap(
    private val real: IdMapRepository,
    private val ambiguousId: PageId,
    private val liveRoots: List<RootName> = emptyList(),
    private val retiredRoots: List<RootName> = emptyList(),
) : IdMapRepository by real {

    override fun rootsHoldingId(id: PageId): List<RootName> =
        if (id == ambiguousId) liveRoots else real.rootsHoldingId(id)

    override fun retiredRootsHoldingId(id: PageId): List<RootName> =
        if (id == ambiguousId) retiredRoots else real.retiredRootsHoldingId(id)

    // Post-flip the resolver reads ONE atomic [claimantState] snapshot (not rootsHoldingId + resolveRetired), so the
    // fake must pose it too - live from [liveRoots], one synthetic tombstone per [retiredRoots] entry (§6.2).
    override fun claimantState(id: PageId): ClaimantState =
        if (id == ambiguousId) ClaimantState(live = liveRoots, retired = retiredRoots.map { it.tombstone(id) }) else real.claimantState(id)
}

/** A synthetic tombstone for [id] under this root - the fakes hold no real path, so a stable placeholder stands in. */
private fun RootName.tombstone(id: PageId): RetiredBinding =
    RetiredBinding(id, RootedPath(this, TreePath.require("retired/gone.md")), materialized = false, retiredAt = 0L)

/** A rooted-miss fixture with one synthetic tombstone and a configurable live claimant set for the same id. */
class RetiredElsewhereIdMap(
    private val real: IdMapRepository,
    private val retiredId: PageId,
    private val retiredRoot: RootName,
    private val retiredPath: TreePath,
    private val liveRoots: List<RootName> = emptyList(),
) : IdMapRepository by real {

    override fun rootsHoldingId(id: PageId): List<RootName> =
        if (id == retiredId) liveRoots else real.rootsHoldingId(id)

    override fun retiredRootsHoldingId(id: PageId): List<RootName> =
        if (id == retiredId) listOf(retiredRoot) else real.retiredRootsHoldingId(id)

    override fun retiredAt(root: RootName, id: PageId): RetiredBinding? =
        if (id == retiredId && root == retiredRoot) {
            RetiredBinding(id, RootedPath(retiredRoot, retiredPath), materialized = false, retiredAt = 0L)
        } else {
            real.retiredAt(root, id)
        }

    override fun claimantState(id: PageId): ClaimantState =
        if (id == retiredId) {
            ClaimantState(
                live = liveRoots,
                retired = listOf(RetiredBinding(id, RootedPath(retiredRoot, retiredPath), materialized = false, retiredAt = 0L)),
            )
        } else {
            real.claimantState(id)
        }
}

/**
 * The C4 UNBIND-RACE fake: for ONE [racingId] the live claimant list is [holder] on the FIRST read and EMPTY on every
 * read after it, while the RETIRED list always names [holder]. Everything else delegates to [real].
 *
 * [AmbiguousIdMap] cannot pose this state and that is not an oversight - it is a CONSTANT map, so the claimant list it
 * hands `resolve()` is the same one it hands `requireVerifiedAbsence()`, and the two would agree by construction. The
 * race this models is precisely the two reads DISAGREEING: `resolve()` reads a live claimant at T1, the page is deleted
 * (unbound, tombstoned) concurrently, and the facade's re-read at T2 finds the binding gone. One instance must back BOTH
 * the resolver and the classifier for the counter to order their reads, which is why callers share a single fake rather
 * than constructing one per factory.
 *
 * Call-COUNT rather than a flag the test flips: the flip has to land in the middle of one in-flight request, and a test
 * cannot reach in there. The count IS the ordering - read 1 is the resolve, read 2 is the re-check.
 */
class RacingUnbindIdMap(
    private val real: IdMapRepository,
    private val racingId: PageId,
    private val holder: RootName,
) : IdMapRepository by real {

    // The unbind lands right after the resolve's FIRST claimant read: T1 sees the page LIVE, every read after it sees the
    // page GONE with the DELETE's tombstone standing (§6.2). Post-flip the resolve reads ONE [claimantState], so the flip
    // is driven off that read - `rootsHoldingId`/`retiredRootsHoldingId` (the classifier's reads) follow the same phase.
    private var raced = false

    override fun claimantState(id: PageId): ClaimantState {
        if (id != racingId) return real.claimantState(id)
        if (!raced) {
            raced = true
            return ClaimantState(live = listOf(holder), retired = emptyList()) // T1: live, not yet tombstoned
        }
        return ClaimantState(live = emptyList(), retired = listOf(holder.tombstone(id))) // T2: unbound + tombstoned
    }

    override fun rootsHoldingId(id: PageId): List<RootName> =
        if (id != racingId) {
            real.rootsHoldingId(id)
        } else if (raced) {
            emptyList()
        } else {
            listOf(holder)
        }

    /** The unbind was a DELETE, so the tombstone outlives the live binding - the fact the 409 arm is owed. */
    override fun retiredRootsHoldingId(id: PageId): List<RootName> =
        if (id != racingId) {
            real.retiredRootsHoldingId(id)
        } else if (raced) {
            listOf(holder)
        } else {
            emptyList()
        }
}

/**
 * The ROOTED-PERMALINK unbind-race fake: models the unbind committing BETWEEN `permalinkAt`'s two `rootsHoldingId`
 * reads - `bindsLive` (`GuardedReadFacade.kt:319`) then the `requireVerifiedAbsence` recheck (via `AbsenceClassifier`,
 * `:321`) - and BEFORE its single `claimantState` read at `:326`. Unlike [RacingUnbindIdMap] (which advances on
 * `claimantState`, the BARE write path's FIRST read), this advances a SHARED read counter on EVERY id read, so the
 * FIRST read sees the page LIVE and every read after it sees it GONE + tombstoned. That is the read ORDER `permalinkAt`
 * uses (two `rootsHoldingId`, then `claimantState`), where [RacingUnbindIdMap] would leave both `rootsHoldingId` reads
 * live and answer 503.
 *
 * The counter is why it also RED-catches the fix's back-out: the correct code reads `bindsLive` FRESH first (read 1 =
 * live -> gated), rechecks (read 2 = gone -> absence verified), then reads the tombstone (read 3) -> 410; collapsing
 * `bindsLive` into ONE first-taken `claims` snapshot makes that read the STALE live one, so the tombstone is never seen
 * and it flips to 404.
 */
class RootedUnbindRaceIdMap(
    private val real: IdMapRepository,
    private val racingId: PageId,
    private val holder: RootName,
) : IdMapRepository by real {

    private var reads = 0

    override fun rootsHoldingId(id: PageId): List<RootName> {
        if (id != racingId) return real.rootsHoldingId(id)
        return if (reads++ == 0) listOf(holder) else emptyList() // read 1 = live (bindsLive); the recheck = gone
    }

    override fun retiredRootsHoldingId(id: PageId): List<RootName> =
        if (id == racingId) listOf(holder) else real.retiredRootsHoldingId(id)

    override fun claimantState(id: PageId): ClaimantState {
        if (id != racingId) return real.claimantState(id)
        return if (reads++ == 0) {
            ClaimantState(live = listOf(holder), retired = emptyList()) // the STALE snapshot, if read FIRST
        } else {
            ClaimantState(live = emptyList(), retired = listOf(holder.tombstone(id))) // the DELETE's tombstone, post-unbind
        }
    }
}
