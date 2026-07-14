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
 *    ([RetiredBinding]) in the same transaction as the new bind, so `/p/{oldId}` stays a 410 rather than
 *    becoming a 404.
 *  - **CONTEST** - two roots hold the same id and BOTH pages are witnessed. Registry rank decides
 *    (`RootRegistry.rank`); the loser reassigns to a fresh id and records a `CrossRootDuplicateId`. Nothing is
 *    tombstoned, because nothing DIED: the contested id is still live, at its new home, and the permalink
 *    follows the id.
 *  - **ABSENCE** - we did NOT see it. *This is the only one that needs a proof, and it is the only one that
 *    has ever destroyed a corpus.*
 *
 * **C0 shipped with ZERO proof sources** - `proofs` was always empty and nothing could be reaped by inference at all,
 * which was the safety floor and a property of the TYPE rather than of a policy anyone could forget. `EPOCH` (C2) and
 * `OBJECT_LIST` (C3) have since bought delete convergence back with EVIDENCE rather than with a guess; `GIT` (C4),
 * `OPERATOR` (C5) and `API_DELETE` are still to come. A page no source can account for sits in limbo ([RootLimbo]) and
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
 * A per-root freshness token, DURABLE (`root_observation`) because **a restart is itself a revocation** and an
 * in-memory token could not prove that after a crash. Any break, binding change or restart mints a new one,
 * which invalidates every outstanding proof by the freshness rule (see [AbsenceProof.observationId]).
 */
@JvmInline
value class ObservationId(val value: Long) {
    /** The next token. Monotonic per root; minting one IS the revocation of the last. */
    fun next(): ObservationId = ObservationId(value + 1)
}

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
 * [observationId] is re-read from `root_observation` INSIDE the apply transaction and must still match. A
 * revocation that commits before the apply opens therefore serializes AGAINST it and the reap becomes a
 * no-op - there is no window, because the compare and the deletes are ONE transaction.
 */
data class AbsenceProof(
    val root: RootName,
    val source: ProofSource,
    val observationId: ObservationId,
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
     * `/p/{id}` into a permanent 410. That shipped. So did its two siblings, and all three were the same mistake:
     * concluding from what we had FAILED TO LOOK AT.
     *
     * [witnessed] is keyed by **IDENTITY**, and that is the whole point: every enforcement of this rule that came before
     * was keyed by PATH, and a rename is precisely the event that changes the path. An id is the only key that survives one.
     *
     * It is also GLOBAL - an id read in ANY root refutes an absence claimed in ANY root. That coupling is deliberate and
     * it fails CLOSED (the row waits in limbo, 503, self-healing), but it is worth knowing about: a stale copy of one
     * root's corpus, mounted somewhere else, will hold up that root's legitimate deletes until it is gone.
     *
     * Two residues, both correct rather than merely tolerated. An **UNMATERIALIZED** page carries no id in its bytes, so
     * it cannot be witnessed this way at all and still splits on a move - its bytes cannot testify to which page they
     * are, and a move really is indistinguishable from a delete-and-recreate (it SOFT-retires, so the old id answers 410,
     * never 404). And a **COPY** carrying a live id refutes just as well as a move does - the two produce IDENTICAL
     * observations, so telling them apart belongs to the rank policy and never to the admission oracle.
     */
    fun survives(witnessed: Set<PageId>): AbsenceProof? {
        if (!source.inferred || witnessed.isEmpty()) return this
        val gone = covers.filterNotTo(mutableSetOf()) { it.id in witnessed }
        return takeIf { gone.isNotEmpty() }?.copy(covers = gone)
    }
}

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
 * A tombstoned binding: the id is RESERVED FOREVER, and `/p/{id}` answers **410 Gone** naming [path] rather
 * than the 404 that tells an agent to drop its citation.
 *
 * Tombstones live OUTSIDE the live `(root, path)` key space (their own table, keyed by [id] alone), so
 * ordinary path reuse - delete a page, later create a different page at the same path - cannot collide with
 * one. [path] is the LAST-KNOWN path, and it is deliberately NOT a key: reuse is legal.
 *
 * **Reclaim only at the page's OWN (root, path).** A retired id turning up at a different path is exactly as
 * likely to be a paste as a return, and a page that moved and a file that was COPIED are observationally
 * identical - so we must not guess. Returning to your own path is the one case that carries its own evidence.
 * Anyone else who claims the id mints a fresh one and raises a `CrossRootDuplicateId`.
 */
data class RetiredBinding(
    val id: PageId,
    val path: RootedPath,
    val materialized: Boolean,
    val retiredAt: Long,
)
