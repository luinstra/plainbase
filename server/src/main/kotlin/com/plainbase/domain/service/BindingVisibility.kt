package com.plainbase.domain.service

import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * The ADR-0011 D16 ownership rule for a partial-visibility pass, shared by `IndexBuilder` and `AdoptionPass` so
 * the two cannot drift apart. Since C0 it is **WITNESS-granular, not root-granular**: "the root was scanned" is
 * not "this page was read", and the gap between those two sentences is where a corpus goes missing.
 *
 * [isLive] - may the pass treat this binding as a live claim at all (does it enter the duplicate contest)?
 *  - a binding whose path this pass **WITNESSED** is live, whatever the file turned out to carry;
 *  - a binding under a root ABSENT from the registry is detached (D2; the boot detached-root WARN is its
 *    visibility) and is not an owner at all;
 *  - a binding under a root the pass could NOT LOOK AT is ALWAYS a live owner. The pass knows precisely
 *    nothing about it, and treating its rows as detached would let even a lower-ranked page take the id
 *    silently, with no contest and no issue - the cross-root steal this rule closes;
 *  - and the case C0 exists for: a binding under a SCANNED root whose path the scan **did not find**. See
 *    [Supersession] - the answer turns on whether that id was ever IN THE FILE, and it is the only place left
 *    in this codebase where "a scan did not see it" is allowed to mean anything at all.
 *
 * [Supersession] - may the pass TAKE that id away from the owner (the D17 rank contest), knowing the winner's
 * key-complete bind DELETES the owner's row? That is a NEGATIVE CLAIM about a page ("it no longer holds this
 * id"), so it needs authority like any other. The rule lives in ONE object that both this resolver and the bind
 * transaction consult: the repository cannot be talked into a supersession the domain would refuse, and the
 * domain cannot resolve one the repository would.
 *
 * The outcomes compose: witnessed owner -> contested by rank; unscanned-root owner -> untouchable, the claimant
 * always reassigns; unwitnessed MATERIALIZED owner -> the accepted move residue, the id travels with the file;
 * unwitnessed UNMATERIALIZED owner -> untouchable, because that row is the ONLY record its id ever existed;
 * detached owner -> not an owner at all.
 */
object BindingVisibility {

    fun isLive(
        owner: IdBinding,
        witnessed: Set<RootedPath>,
        scannedRoots: Set<RootName>,
        registered: Set<RootName>,
        supersession: Supersession,
    ): Boolean = when {
        owner.path in witnessed -> true
        owner.path.root !in registered -> false
        owner.path.root !in scannedRoots -> true
        // A SCANNED root that did not turn this page up. It is NOT an owner exactly when its id is free to
        // travel with the file - which is the question [Supersession] already answers, asked from the other
        // side. Keeping it as ONE question is what stops a page from being an untouchable owner here and a
        // supersedable one three lines later.
        else -> !supersession.mayDisplace(owner)
    }
}
