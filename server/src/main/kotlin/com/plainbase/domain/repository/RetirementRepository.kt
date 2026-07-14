package com.plainbase.domain.repository

import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ObservationId
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
 * **In C0 nothing calls [applyProofs] with a non-empty list.** No production code mints an [AbsenceProof] -
 * all five sources arrive in later chunks - so this machinery is real, tested, and idle. That is the safety
 * floor: a reaper that cannot be handed a licence cannot reap.
 */
interface RetirementRepository {

    /**
     * Retires every binding an [AbsenceProof] covers, and returns the ids actually retired (which is what the
     * publication sinks then act on - they never re-derive the authority for themselves).
     *
     * A proof is applied only when ALL of these hold, re-checked INSIDE the transaction:
     *  - its [AbsenceProof.observationId] still equals the root's CURRENT token (freshness - a restart, a
     *    watcher break or a rebind has not revoked it since it was minted);
     *  - `proof.root == the binding's root` (no cross-root proof replay: a [BindingRef] carries no root, so
     *    without this a proof minted for root A could retire a same-named, same-id binding in root B);
     *  - the live binding at that (root, path) still carries exactly the id the proof names (the page was not
     *    replaced underneath us between the observation and the apply).
     */
    fun applyProofs(proofs: List<AbsenceProof>): Set<BindingRef>

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
    override fun applyProofs(proofs: List<AbsenceProof>): Set<BindingRef> = emptySet()
    override fun observation(root: RootName): ObservationId = ObservationId(0)
    override fun observations(): Map<RootName, ObservationId> = emptyMap()
    override fun revoke(root: RootName): ObservationId = ObservationId(0)
}
