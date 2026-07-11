package com.plainbase.domain.service

import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * The ADR-0011 D16 ownership rule for a partial-visibility pass, shared by `IndexBuilder` and
 * `AdoptionPass` so the two cannot drift apart: may a pass treat [isLive]'s owner binding as a
 * live, untouchable claim?
 *
 *  - A binding under a SCANNED root is live iff its path was actually seen on disk (a missing
 *    path is a moved file, supersedable - the pre-C2 rule).
 *  - A binding under an UNSCANNED-but-CONFIGURED root is ALWAYS a live owner: the pass cannot see
 *    that root's disk, so treating its rows as detached would let even a LOWER-ranked page take
 *    the id silently, with no D17 rank contest and no issue - the cross-root steal this rule closes.
 *  - A binding under a root ABSENT from the registry is detached and supersedable (D2; the boot
 *    detached-root WARN is its visibility).
 *
 * When a pass's sources cover the whole registry the middle arm is empty and the rule collapses
 * structurally to the scanned-live check.
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
}
