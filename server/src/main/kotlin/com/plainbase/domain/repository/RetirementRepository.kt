package com.plainbase.domain.repository

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName

/**
 * The **proof-apply transaction**: the ONE place in the system where an ABSENCE retires a binding, and the
 * durable [ObservationId] that makes it safe to.
 *
 * **The app DB is the authoritative linearization boundary.** [applyProofs] re-reads each root's observation
 * token, compares it to the proof's, and - only if they still match - applies the `id_map` -> `retired_binding`
 * moves, the `page_checkpoint` deletes and the `dirty_page` clears, ALL IN ONE TRANSACTION. Revocation
 * ([revoke]) is itself a write to the same table in the same database, so a revoke that lands between publish
 * and apply serializes AGAINST the reap and the reap becomes a NO-OP. There is no window - which is also why
 * there is no test for a revocation landing "between the compare and the deletes": the compare and the deletes
 * are one transaction, so that interleaving cannot occur and a test for it would pass vacuously.
 *
 * `search.db` cannot join that transaction and does not need to. Per ADR-0004 it is a SEPARATE database on raw
 * JDBC precisely because it is DERIVED state: the app-DB commit is the point of truth, `SearchIndexer.sync`
 * brings search into line afterwards, and a crash between the two leaves a STALE SEARCH ROW - a wrong hit, not
 * a lost page, and exactly the failure ADR-0004 already accepts. Do not invent an outbox for a derived store.
 *
 * **C0 shipped this idle** - nothing minted an [AbsenceProof], so the reaper was real, tested, and unusable. Since
 * then EPOCH (C2), OBJECT_LIST (C3) and GIT (C4 - which also rides checkpoint advances through here) mint against it;
 * OPERATOR (C5) and API_DELETE arrive later. The safety floor stands: a reaper that cannot be handed a licence cannot
 * reap, and a source that cannot answer *"what did we SEE?"* cannot call this at all.
 */
interface RetirementRepository {

    /**
     * Retires every binding an [AbsenceProof] covers, and returns the ids actually retired (which is what the
     * publication sinks then act on - they never re-derive the authority for themselves).
     *
     * A proof is applied only when ALL of these hold, re-checked INSIDE the transaction:
     *  - it **SURVIVES [witnessed]** ([AbsenceProof.survives]) - an INFERRED absence is a conclusion drawn from a gap
     *    in what we observed, and SEEING the page refutes it. This is first because it is the one that ships bugs;
     *  - its [AbsenceProof.observationId] still equals the root's CURRENT token (freshness - a restart, a
     *    watcher break or a rebind has not revoked it since it was minted);
     *  - `proof.root == the binding's root` (no cross-root proof replay: a [BindingRef] carries no root, so
     *    without this a proof minted for root A could retire a same-named, same-id binding in root B);
     *  - the live binding at that (root, path) still carries exactly the id the proof names (the page was not
     *    replaced underneath us between the observation and the apply).
     *
     * **[witnessed] is a REQUIRED argument, and that is the entire point of it being here.** The refutation used to be
     * a filter the CALLER applied on its way in - one expression, at one call site, enforcing a rule the type system
     * knew nothing about. That is a convention, and a convention is exactly what a new proof source walks around: a
     * `GIT` oracle minting at boot, an `OPERATOR` path minting from a CLI, anything that does not happen to route
     * through the pass that remembered. Demanding the witness set at the DOOR OF THE ONLY DELETER means a source that
     * has not answered *"what did we actually SEE?"* cannot retire anything, because it cannot call this at all.
     *
     * Pass every [com.plainbase.domain.page.PageId] this observation READ - from any root, since an id seen ANYWHERE
     * refutes an absence claimed anywhere. A caller with no observation behind it (a boot replay of an `API_DELETE`
     * intent, an operator's accepted digest) passes the empty set truthfully: those sources are not
     * [ProofSource.inferred], so nothing can refute them anyway, and the empty set says the honest thing rather than
     * a convenient one.
     *
     * There is deliberately NO DEFAULT. An empty witness set means *"we saw nothing"*, which is the OPTIMISTIC value -
     * it refutes no proof and reaps the most - and a safety input that silently defaults to the optimistic value is
     * precisely how all three of these bugs came to exist.
     *
     * [advances] are the GIT-checkpoint moves (C4) that ride THIS transaction, each behind its own freshness compare,
     * so an advance and the retirements it accounts for commit or roll back together. It HAS a default and [witnessed]
     * does not, and the asymmetry is the point: omitting [witnessed] would default to the optimistic value (reap the
     * most), while omitting [advances] defaults to the PESSIMISTIC one (the checkpoint does not move, the next boot
     * re-derives) - a safety input gets no default, a fail-closed convenience may. A baseline or empty-reap advance
     * arrives with NO proofs, which is why the empty-list early return must guard on BOTH lists.
     */
    fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<PageId>,
        advances: List<GitCheckpointAdvance> = emptyList(),
    ): Set<BindingRef>

    /** [root]'s recorded GIT checkpoint HEAD (C4), or null when no baseline has been written for it yet. */
    fun gitHead(root: RootName): String?

    /** [root]'s CURRENT freshness token, minting a fresh one on first sight. */
    fun observation(root: RootName): ObservationId

    /** Every root's current token - the value a pass stamps into the proofs it mints. */
    fun observations(): Map<RootName, ObservationId>

    /**
     * Mints [root] a NEW token, which invalidates every outstanding proof against it. A restart is itself a
     * revocation (the token is durable, so it is the only thing that could prove that after a crash); so is a
     * watcher break, a coverage loss, an availability mark and a binding change.
     */
    fun revoke(root: RootName): ObservationId
}

/**
 * A [RetirementRepository] with no proofs to apply and no tokens to mint - the default for the many single-root
 * constructions (tests, the CLI's shadow passes) that never reap anything. It is not a stub: "hold no proof,
 * grant no authority" is the C0 behavior everywhere, and this object simply cannot be talked out of it.
 */
object NoRetirements : RetirementRepository {
    override fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<PageId>,
        advances: List<GitCheckpointAdvance>,
    ): Set<BindingRef> = emptySet()
    override fun gitHead(root: RootName): String? = null
    override fun observation(root: RootName): ObservationId = ObservationId(0)
    override fun observations(): Map<RootName, ObservationId> = emptyMap()
    override fun revoke(root: RootName): ObservationId = ObservationId(0)
}
