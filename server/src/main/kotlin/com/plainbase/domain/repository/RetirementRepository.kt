package com.plainbase.domain.repository

import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId

/**
 * The **proof-apply transaction**: the ONE place in the system where an ABSENCE retires a binding, and the two
 * durable freshness stamps that make it safe to.
 *
 * **The app DB is the authoritative linearization boundary.** [applyProofs] re-reads each root's stamps - BOTH the
 * [ObservationId] (epoch continuity: a restart, break or unmount mints a new one) and the [BindingEpoch] (binding
 * freshness: every successful bind advances it) - compares them to the proof's, and, only if BOTH still match,
 * applies the `id_map` -> `retired_binding` moves, the `page_checkpoint` deletes and the `dirty_page` clears, ALL IN
 * ONE TRANSACTION. The two are orthogonal on purpose: either advance alone invalidates a proof, and a bind must be
 * able to revoke one WITHOUT collapsing the epoch. Both revocations ([revoke], and the increment inside a bind) are
 * writes to the same table in the same database, so one landing between publish and apply serializes AGAINST the reap
 * and the reap becomes a NO-OP. There is no window - which is also why there is no test for a revocation landing
 * "between the compare and the deletes": the compare and the deletes are one transaction, so that interleaving cannot
 * occur and a test for it would pass vacuously.
 *
 * `search.db` cannot join that transaction and does not need to. Per ADR-0004 it is a SEPARATE database on raw
 * JDBC precisely because it is DERIVED state: the app-DB commit is the point of truth, `SearchIndexer.sync`
 * brings search into line afterwards, and a crash between the two leaves a STALE SEARCH ROW - a wrong hit, not
 * a lost page, and exactly the failure ADR-0004 already accepts. Do not invent an outbox for a derived store.
 *
 * **C0 shipped this idle** - nothing minted an [AbsenceProof], so the reaper was real, tested, and unusable. Since
 * then EPOCH (C2), OBJECT_LIST (C3), GIT (C4 - which also rides checkpoint advances through here) and OPERATOR (C5's
 * `plainbase admin force-retire`) mint against it; API_DELETE arrives later. The safety floor stands: a reaper that
 * cannot be handed a licence cannot reap, and a source that cannot answer *"what did we SEE?"* cannot call this at all.
 */
interface RetirementRepository {

    /**
     * Retires every binding an [AbsenceProof] covers, and returns the rooted ids actually retired (which is what
     * the publication sinks then act on - they never re-derive the authority for themselves).
     *
     * A proof is applied only when ALL of these hold, re-checked INSIDE the transaction:
     *  - it **SURVIVES [witnessed]** ([AbsenceProof.survives]) - an INFERRED absence is a conclusion drawn from a gap
     *    in what we observed, and SEEING the page refutes it. This is first because it is the one that ships bugs;
     *  - its [AbsenceProof.observationId] still equals the root's CURRENT token (epoch CONTINUITY - a restart, an
     *    unmount or a watcher break has not revoked it since it was minted);
     *  - its [AbsenceProof.bindingEpoch] still equals the root's CURRENT binding epoch (binding FRESHNESS - no bind has
     *    landed since the proof was stamped, so it cannot reap a binding a restore just re-created, nor that binding's
     *    `dirty_page` recovery row). A bind advances THIS and deliberately leaves the observation token alone: a restore
     *    must revoke the proof without collapsing the epoch;
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
     * Pass every [RootedPageId] this observation READ - the id qualified by the root it was read in, since per-root
     * identity (C5) an id read in root B refutes only an absence claimed in root B. A caller with no observation
     * behind it (a boot replay of an `API_DELETE` intent, an operator's accepted digest) passes the empty set
     * truthfully: those sources are not [ProofSource.inferred], so nothing can refute them anyway, and the empty set
     * says the honest thing rather than a convenient one.
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
     *
     * **[unavailable] is the second required safety input, and it is here for the same reason [witnessed] is.** A root
     * that has been marked unavailable has a HOLE in what we know about it - it vanished, its watcher died, or a probe
     * could not traverse it - and evidence gathered before that hole cannot be cashed after it. The dangerous shape is
     * vanish-and-RESTORE inside one pass: the tree comes back with the page on it, a bindless restore moves no
     * [BindingEpoch], and a mark moves no [ObservationId] (`RootAvailability.markUnavailable` publishes the mark and
     * nothing else - deliberately, because a revoke is a transaction and the loss paths run on request and write
     * threads, where a second BEGIN on the shared driver is a 500). So neither stamp catches it, and this set does.
     * Asked here, at the door of the only deleter, for the same reason as the witness: a caller that cannot say which
     * roots it still has standing on must not be able to retire anything.
     *
     * It gates the INFERRED sources only, exactly as the witness refutation does. An `OPERATOR` proof is an accepted
     * human decision rather than a conclusion drawn from an observation, so an operator retiring a page in a root they
     * have already been told is unavailable is doing precisely what they asked to do.
     */
    fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<RootedPageId>,
        unavailable: Set<RootName>,
        advances: List<GitCheckpointAdvance> = emptyList(),
    ): Set<RootedPageId>

    /** [root]'s recorded GIT checkpoint HEAD (C4), or null when no baseline has been written for it yet. */
    fun gitHead(root: RootName): String?

    /** [root]'s CURRENT freshness token, minting a fresh one on first sight. */
    fun observation(root: RootName): ObservationId

    /**
     * [root]'s CURRENT binding epoch (`root_observation.binding_epoch`) - the SECOND stamp a producer captures at
     * MINT time and [applyProofs] re-checks. Orthogonal to [observation]: this advances on a `bind`, that revokes on a
     * break. Zero when the root has no observation row yet (nothing to be fresh against, and no proof outstanding).
     */
    fun bindingEpoch(root: RootName): BindingEpoch

    /** Every root's current token. Reporting/health; a pass stamps the value `ObservationEpoch.establish` hands it. */
    fun observations(): Map<RootName, ObservationId>

    /**
     * Mints [root] a NEW token, which invalidates every outstanding proof against it. A restart is itself a
     * revocation (the token is durable, so it is the only thing that could prove that after a crash); so is a watcher
     * break, a coverage loss, and an epoch OPEN (which is why the open is hoisted above a pass's evidence rather than
     * happening at mint - see `ObservationEpoch.establish`).
     *
     * Two things deliberately do NOT come through here:
     *  - a BINDING change, which advances [bindingEpoch] instead. A restore's re-bind must revoke the proof that would
     *    have reaped the page it just re-created WITHOUT collapsing the root's epoch; that is the whole point of the
     *    second stamp being orthogonal.
     *  - an AVAILABILITY mark. `RootAvailability.markUnavailable` publishes the mark and nothing else. For the
     *    watcher-driven losses that is covered, because the watcher reports the break FIRST (`WATCHER_DIED` before
     *    `onFailure`, `ROOT_LOST`) and the break revokes. **It is NOT covered for a loss discovered by probing** -
     *    `RootLossClassifier.markIfGone` holds no [ObservationEpoch] - so a vanish-and-restore between a pass's mint and
     *    its apply moves neither token and leaves a stale proof applicable. Known gap, pre-dating C5; do not read this
     *    KDoc as a claim that every availability transition revokes.
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
        witnessed: Set<RootedPageId>,
        unavailable: Set<RootName>,
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> = emptySet()
    override fun gitHead(root: RootName): String? = null
    override fun observation(root: RootName): ObservationId = ObservationId(0)
    override fun bindingEpoch(root: RootName): BindingEpoch = BindingEpoch(0)
    override fun observations(): Map<RootName, ObservationId> = emptyMap()
    override fun revoke(root: RootName): ObservationId = ObservationId(0)
}
