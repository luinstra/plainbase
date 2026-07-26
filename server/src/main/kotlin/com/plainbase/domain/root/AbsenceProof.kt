package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId

/**
 * The absence-authority machinery: the ONE licence to assert that a binding is gone.
 *
 * > **A scan proves the pages it READ. It does not prove the pages it did not read are DELETED.**
 *
 * An empty mount point, a deliberately emptied root, a partially-restored tree and a decoy tree produce
 * IDENTICAL filesystem observations. A corpus mass-deleted while the server was down is permanently
 * indistinguishable from an outage, by any oracle - that is a theorem, not a bug. So nothing deletes on an
 * INFERENCE from what a scan failed to see; it deletes on a PROOF, and a proof is a value, not a mood.
 *
 * There are exactly three ways a binding may leave `id_map`, and only ONE of them is an absence claim:
 *
 *  - **DISPLACEMENT** - we READ the file at (root, path) and it carries a DIFFERENT id (or none). Positive
 *    evidence: the page is right in front of us. No proof needed; the displaced id is TOMBSTONED
 *    ([RetiredBinding]) in the same transaction as the new bind, so `/p/{root}/{oldId}` stays a 410 rather than
 *    becoming a 404.
 *  - **CONTEST** - WITHIN one root, two pages resolve to the same id and BOTH are witnessed (the same id in two
 *    DIFFERENT roots is legal post-flip and contests nothing, C5). The PREVIOUSLY-BOUND path keeps the id (on first
 *    detection, whichever the pass resolves first); the loser reassigns to a fresh id and records a `DuplicateId`
 *    (`PageIdentityService`). Registry rank compares ROOTS, so it decides nothing here - within one root the
 *    order is the caller's: `IndexBuilder` takes frontmatter-carrying pages first and then path, while
 *    `AdoptionPass` takes plain path order. Nothing is tombstoned, because nothing DIED: the contested id is
 *    still live, at its keeper, and the permalink follows the id.
 *  - **ABSENCE** - we did NOT see it. *This is the only one that needs a proof, and it is the only one that
 *    has ever destroyed a corpus.*
 *
 * **C0 shipped with ZERO proof sources** - `proofs` was always empty and nothing could be reaped by inference at all,
 * which was the safety floor and a property of the TYPE rather than of a policy anyone could forget. `EPOCH` (C2),
 * `OBJECT_LIST` (C3) and `GIT` (C4) have since bought delete convergence back with EVIDENCE rather than with a guess;
 * `OPERATOR` (C5) ships as the `admin force-retire` un-wedge hatch, and `API_DELETE` is still to come. A page no
 * source can account for sits in limbo ([RootLimbo]) and
 * reads 503 - never a 404, and never a reap.
 *
 * ---
 *
 * [ProofSource] carries, in [ProofSource.inferred], the ONE thing the apply transaction needs to know about a claim:
 * **was it concluded from NOT SEEING something?** That is the fault line the five sources actually fall along, and
 * three corpus bugs came from not drawing it:
 *
 *  - An **INFERRED** absence is a conclusion drawn from a gap in what we observed - *"the epoch witnessed it and this
 *    scan does not see it"*, *"the LIST does not hold it"*. Such a conclusion is **REFUTED BY SEEING**: if this pass
 *    READ that id anywhere at all, the page is not gone and the inference was simply wrong. A RENAME is the everyday
 *    case (the page is right there under a new name); a copy and a restore are the others.
 *  - A **CAUSED or ACCEPTED** absence is not a conclusion at all. `API_DELETE` says *"we deleted it ourselves"* and
 *    `OPERATOR` says *"a human read the exact reap set and signed it"*. No observation can refute either, because
 *    neither is an observation - and refuting them would itself be the bug: a page an operator DELIBERATELY deleted,
 *    whose bytes some stale copy still carries, must still converge, or `reconcile` would be vetoed by the very copy
 *    it was run to resolve.
 *
 * So [ProofSource.inferred] lives on the TYPE rather than in a `when` somebody must remember to extend, and the
 * refutation is demanded by [com.plainbase.domain.repository.RetirementRepository.applyProofs]'s SIGNATURE rather than
 * applied by a caller who must remember to call it. **A new proof source cannot reach the database without answering
 * the question** - which is the difference between a rule and a convention, and the convention is what shipped the bugs.
 */
enum class ProofSource(val inferred: Boolean) {
    /** A delete through Plainbase's own API. We did not INFER this absence, we CAUSED it - so nothing can refute it. */
    API_DELETE(inferred = false),

    /** A CONFIRMATION scan under an unbroken observation epoch: it witnessed the page, and now it does not. */
    EPOCH(inferred = true),

    /** A commit range that DELETED the path, on a HEAD that descends from the recorded one. Recorded human intent. */
    GIT(inferred = true),

    /** A complete, generation-bound bucket LIST under a TRUSTED binding. */
    OBJECT_LIST(inferred = true),

    /** An operator accepting an exact observation digest (`plainbase root reconcile --accept`) - ACCEPTED, not inferred. */
    OPERATOR(inferred = false),
}

/**
 * A per-root CONTINUITY token, DURABLE (`root_observation`) because **a restart is itself a revocation** and an
 * in-memory token could not prove that after a crash. Any break, unmount or restart mints a new one, which invalidates
 * every outstanding proof by the freshness rule (see [AbsenceProof.observationId]).
 *
 * A binding change does NOT move this one - that is [BindingEpoch]'s job, and the split is deliberate: a restore's
 * re-bind must revoke the proof that would have reaped the page it just re-created WITHOUT collapsing the root's epoch.
 */
@JvmInline
value class ObservationId(val value: Long) {
    /** The next token. Monotonic per root; minting one IS the revocation of the last. */
    fun next(): ObservationId = ObservationId(value + 1)
}

/**
 * The SECOND freshness stamp, ORTHOGONAL to [ObservationId] (`root_observation.binding_epoch`, C5 revoke-before-stamp).
 *
 * A per-root, monotonic, never-reset counter that ONLY a successful `bind` advances. Where [ObservationId] gates epoch
 * CONTINUITY - a break/restart/unmount mints a new one and kills every live epoch - this gates BINDING freshness: a
 * restore's re-bind of a covered key advances it, so an inferred proof minted under the old value loses `applyProofs`'
 * two-token compare and cannot reap the binding (and its `dirty_page` USER-CONTENT recovery row) the restore just
 * re-created - WITHOUT touching the observation token, so the epoch that shares that token is not collapsed. Because it
 * is monotonic and durable (never reset by observation churn), there is no ABA hazard: a value never recurs.
 */
@JvmInline
value class BindingEpoch(val value: Long)

/**
 * The thing an absence proof is ABOUT: a (path, id) PAIR, never a bare path.
 *
 * A path is not a page. A proof minted for `guides/deploy.md` would otherwise survive a delete-and-recreate at
 * that path and retire the BRAND-NEW page living there. The id is what the proof is actually about.
 */
data class BindingRef(val path: TreePath, val id: PageId)

/**
 * A positive licence to assert that a SPECIFIC BINDING is gone.
 *
 * [root] is load-bearing and is checked at apply time: [BindingRef] carries no root, so without it a proof
 * minted for root A could authorize retiring a binding in root B whose path and id happened to match -
 * cross-root proof replay, in a MULTI-ROOT feature. Authority is per-root, always.
 *
 * [observationId] and [bindingEpoch] are BOTH re-read from `root_observation` INSIDE the apply transaction and must
 * still match, exact-equality, fail-closed. A revocation (a new observation) OR a re-bind (an advanced binding epoch)
 * that commits before the apply opens therefore serializes AGAINST it and the reap becomes a no-op - there is no
 * window, because the compare and the deletes are ONE transaction. The two tokens are orthogonal: [observationId]
 * dies on an epoch break, [bindingEpoch] advances on a bind, and either mismatch alone discards the proof.
 */
data class AbsenceProof(
    val root: RootName,
    val source: ProofSource,
    val observationId: ObservationId,
    val bindingEpoch: BindingEpoch,
    val covers: Set<BindingRef>,
) {

    /**
     * **What survives being looked at** - this proof with every binding whose id [witnessed] holds removed, or null when
     * that empties it. A [ProofSource.inferred]`= false` proof survives whole: nothing observed can refute *"we caused
     * this"* or *"a human signed this"*.
     *
     * > **A page we are LOOKING AT is not a page that is absent.** Proving otherwise is not a proof, it is a contradiction.
     *
     * A proof covers a `(path, id)` whose PATH a complete pass did not see, and a RENAMED page's old path is exactly
     * that - so without this, every inferred source mints a proof over a page sitting in front of it under a different
     * name, the apply transaction tombstones the id its frontmatter still plainly carries, and one `git mv` turns
     * `/p/{root}/{id}` into a permanent 410. That shipped. So did its two siblings, and all three were the same mistake:
     * concluding from what we had FAILED TO LOOK AT.
     *
     * [witnessed] is keyed by **IDENTITY**, and that is the whole point: every enforcement of this rule that came before
     * was keyed by PATH, and a rename is precisely the event that changes the path. An id is the only key that survives one.
     *
     * It is PER-ROOT (per-root identity, C5): the witness is `RootedPageId(this.root, it.id)`, so an id read in root B
     * refutes only an absence claimed in root B. A stale copy of one root's corpus mounted under ANOTHER root no longer
     * holds up this root's legitimate deletes - the copy witnesses its OWN root's id, not this one's.
     *
     * Two residues, both correct rather than merely tolerated. An **UNMATERIALIZED** page carries no id in its bytes, so
     * it cannot be witnessed this way at all and still splits on a move - its bytes cannot testify to which page they
     * are, and a move really is indistinguishable from a delete-and-recreate (it SOFT-retires, so the old id answers 410,
     * never 404). And a **COPY** carrying a live id refutes just as well as a move does - the two produce IDENTICAL
     * observations, so telling them apart belongs to the rank policy and never to the admission oracle.
     */
    fun survives(witnessed: Set<RootedPageId>): AbsenceProof? {
        if (!source.inferred || witnessed.isEmpty()) return this
        val gone = covers.filterNotTo(mutableSetOf()) { RootedPageId(root, it.id) in witnessed }
        return takeIf { gone.isNotEmpty() }?.copy(covers = gone)
    }
}

/**
 * A GIT-checkpoint advance (C4): move [root]'s recorded HEAD to [head] - but ONLY if [observationId] AND
 * [bindingEpoch] BOTH still equal the root's current tokens INSIDE the proof-apply transaction, the SAME two-token
 * freshness gate a proof rides, so a view revoked (or a binding re-created) between the mint and the apply advances
 * nothing.
 *
 * It carries no bindings because it is not a reap. It is the record that says *"every committed deletion up to
 * [head] has been ACCOUNTED FOR"* - retired, refuted by a witness, or already gone - and it rides the same
 * transaction as the retirements it travels with, so the advance and the deletes it authorized share ONE
 * linearization point. That is what makes the crash semantics fall out: die before the commit and the next boot
 * re-diffs the identical range from the unmoved checkpoint; die after and the range is consumed with its deletions.
 *
 * The advance is RESOLUTION-based, not reap-based: an EMPTY effective reap set still advances (a restored file
 * that pinned the checkpoint would otherwise re-diff an ever-growing range forever), while an UNREAD path in the
 * range withholds it (a page the walk saw and the read failed on is not accounted for). See the C4 mint.
 *
 * **One stated residue, fail-closed:**
 *  - **[head] is compared by STRING EQUALITY, so the G2 bracket has an ABA residue.** A mid-pass `A -> B -> A`
 *    (a commit and a `reset --hard` back, inside one walk) reads A at both ends and the bracket sees nothing move.
 *    The window is one pass wide and needs a history rewrite landing inside it; the alternative - a reflog walk
 *    per pass - buys a narrower race at a cost the oracle is not worth. Named, not fixed.
 */
data class GitCheckpointAdvance(
    val root: RootName,
    val observationId: ObservationId,
    val bindingEpoch: BindingEpoch,
    val head: String,
)

/**
 * What a pass actually SAW at one rooted path: the page was READ, and [observedId] is the id its frontmatter
 * carried (null = it carries none, i.e. an unmaterialized page).
 *
 * **The witness must carry IDENTITY, not just presence.** A bare path-witness cannot decide anything: the
 * supersession gate needs to know that the incumbent NO LONGER CARRIES the id, and reading a *path* does not
 * tell you the *id the file now holds*.
 *
 * Absence from the witness map is NOT a licence. It means "we did not read this", which is exactly as
 * consistent with an unmounted disk as with a delete.
 */
data class Witness(val observedId: PageId?)

/**
 * A tombstoned binding: the id is RESERVED within its root, and `/p/{root}/{id}` answers **410 Gone** naming
 * [path] rather than the 404 that tells an agent to drop its citation.
 *
 * Tombstones live OUTSIDE the live `(root, path)` key space (their own table, keyed by `(root, id)` post-flip so
 * two roots may each tombstone the same id, C5), so ordinary path reuse - delete a page, later create a different
 * page at the same path - cannot collide with one. [path] is the LAST-KNOWN path, and it is deliberately NOT a
 * key: reuse is legal.
 *
 * **Reclaim only at the page's OWN (root, path).** A retired id turning up at a different path is exactly as
 * likely to be a paste as a return, and a page that moved and a file that was COPIED are observationally
 * identical - so we must not guess. Returning to your own path is the one case that carries its own evidence.
 * Anyone else claiming the id WITHIN that root mints a fresh one and raises a `DuplicateId` (the same id in
 * another root is legal post-flip and claims nothing here, C5).
 */
data class RetiredBinding(
    val id: PageId,
    val path: RootedPath,
    val materialized: Boolean,
    val retiredAt: Long,
)
