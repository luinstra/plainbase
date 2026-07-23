package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
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
}

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

    private var liveReads = 0

    override fun rootsHoldingId(id: PageId): List<RootName> {
        if (id != racingId) return real.rootsHoldingId(id)
        liveReads++
        return if (liveReads == 1) listOf(holder) else emptyList()
    }

    /** The unbind was a DELETE, so the tombstone outlives the live binding - the fact the 409 arm is owed. */
    override fun retiredRootsHoldingId(id: PageId): List<RootName> =
        if (id == racingId) listOf(holder) else real.retiredRootsHoldingId(id)
}
