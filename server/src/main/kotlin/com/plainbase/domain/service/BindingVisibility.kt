package com.plainbase.domain.service

import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * The ADR-0011 D16 ownership rule for a partial-visibility pass, shared by `IndexBuilder` and
 * `AdoptionPass` so the two cannot drift apart. It answers TWO questions about an owner binding,
 * and they are NOT the same question:
 *
 * [isLive] - may the pass treat this binding as a live claim at all (does it enter the duplicate
 * contest)?
 *  - A binding under a SCANNED root is live iff its path was actually seen on disk (a missing
 *    path is a moved file - the pre-C2 rule).
 *  - A binding under an UNSCANNED-but-CONFIGURED root is ALWAYS a live owner: the pass cannot see
 *    that root's disk, so treating its rows as detached would let even a LOWER-ranked page take
 *    the id silently, with no contest and no issue - the cross-root steal this rule closes.
 *  - A binding under a root ABSENT from the registry is detached (D2; the boot detached-root WARN
 *    is its visibility).
 *
 * [isSupersedable] - may the pass TAKE that id away from the owner (the D17 rank contest), knowing
 * the winner's key-complete bind DELETES the owner's row? ONLY when the pass actually SCANNED the
 * owner's root. Rank decides a contest between two roots that both showed up; it cannot decide one
 * for a root that is not there. A pass has no authority to destroy durable identity state for a
 * root it could not look at: it cannot know the root still holds the page, its section is being
 * CARRIED FORWARD verbatim (so the id would ALSO still be in the snapshot - a duplicate id, i.e. a
 * rebuild crash), and an outage must never silently cost a page its permalink (D-C4-10). So the
 * SCANNED claimant reassigns instead, exactly like any other duplicate-id loser, and the contest
 * waits until both roots can actually take part in it.
 *
 * The two arms compose into three outcomes: scanned-and-on-disk owner -> contested by rank;
 * unscanned-but-registered owner -> untouchable, the claimant always reassigns; detached owner ->
 * not an owner at all, the claimant simply takes the id and the bind sweeps the stale row.
 *
 * When a pass's sources cover the whole registry the middle arm is empty and both rules collapse
 * structurally to the scanned-live check - which is the steady state the runtime runs in, since C4
 * wires every registered root as a source. Partial visibility is the OUTAGE (and single-root
 * `adopt`) shape, and these are its rules.
 */
object BindingVisibility {

    fun isLive(
        owner: RootedPath,
        scannedLive: Set<RootedPath>,
        scannedRoots: Set<RootName>,
        registered: Set<RootName>,
    ): Boolean = when {
        owner.root in scannedRoots -> owner in scannedLive
        owner.root in registered -> true
        else -> false
    }

    fun isSupersedable(owner: RootedPath, scannedRoots: Set<RootName>): Boolean = owner.root in scannedRoots
}
