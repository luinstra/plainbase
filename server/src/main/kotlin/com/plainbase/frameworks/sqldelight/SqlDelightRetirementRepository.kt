package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

/**
 * SQLDelight adapter for [RetirementRepository] - the proof-apply transaction, over `root_observation`,
 * `id_map`, `retired_binding`, `page_checkpoint`, `dirty_page` and (C4) `git_checkpoint` (see the port for the model).
 *
 * All six tables live in the SAME app database, which is what lets the freshness compare, the deletions it
 * authorizes, and the C4 checkpoint advance be ONE transaction rather than a protocol. That is not an implementation
 * convenience; it is the entire safety argument for the reap.
 */
class SqlDelightRetirementRepository(
    private val db: PlainbaseDb,
    private val clock: Clock = Clock.System,
) : RetirementRepository {

    private val observations get() = db.rootObservationQueries
    private val idMap get() = db.idMapQueries
    private val checkpoints get() = db.pageCheckpointQueries
    private val dirty get() = db.dirtyPageQueries
    private val gitCheckpoints get() = db.gitCheckpointQueries

    override fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<RootedPageId>,
        unavailableNow: () -> Set<RootName>,
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> {
        // A baseline or empty-reap advance (C4) arrives with NO proofs by construction, so the empty-list guard
        // must check BOTH lists or the advance would never reach the transaction that lands the checkpoint.
        if (proofs.isEmpty() && advances.isEmpty()) return emptySet()
        return db.transactionWithResult {
            // INSIDE the transaction, and that is load-bearing: read as a value at the CALL SITE, a mark landing between
            // the caller's evaluation and this snapshot would be missed - the freshness-stamp bug mirrored. One read
            // serves every proof and advance below, so they all judge standing against the same instant.
            //
            // This only holds if the caller hands over a LIVE read. A lambda closing over an already-read set relocates
            // the convention rather than removing it, so the call site's job is to keep the read inside the lambda.
            val unavailable = unavailableNow()
            val retiredAt = clock.now().toEpochMilliseconds()
            val applied =
                proofs.flatMapTo(mutableSetOf()) { proof ->
                    applyProof(proof, witnessed, unavailable, retiredAt)
                }
            // The GIT checkpoint advances (C4), in the SAME transaction and behind the IDENTICAL freshness compare
            // the proofs above ride: a revoked view advances nothing, so a GIT proof discarded for freshness drops
            // its root's advance too. The same TWO stamps, one transaction, no window between the reap and the move.
            val advanced = advances.filter { advance -> applyAdvance(advance, unavailable) }
            // The advances get their own clause because the COMMON C4 pass mints no proof at all - a baseline, or a
            // range whose deletions this walk resolved by presence - and "applied 0 absence proof(s)" alone reads like
            // a pass that did nothing, on the one line an operator has to tell a moving checkpoint from a stuck one.
            logger.info {
                "applied ${proofs.size} absence proof(s): ${applied.size} binding(s) retired" +
                    advanced.joinToString(prefix = "; git checkpoint advanced: ", limit = 8) { "'${it.root}' -> ${it.head}" }
                        .takeIf { advanced.isNotEmpty() }.orEmpty()
            }
            applied
        }
    }

    private fun applyProof(
        minted: AbsenceProof,
        witnessed: Set<RootedPageId>,
        unavailable: Set<RootName>,
        retiredAt: Long,
    ): Set<RootedPageId> =
        when {
            minted.source.inferred && minted.root in unavailable -> {
                // STANDING: inferred evidence gathered before an availability hole proves nothing about the tree now.
                logger.warn {
                    "discarding a ${minted.source} proof for root '${minted.root}': the root has been marked " +
                        "unavailable since this pass gathered its evidence, so that evidence proves nothing about " +
                        "the tree that is there now"
                }
                emptySet()
            }

            else -> applySurvivingProof(minted, witnessed, retiredAt)
        }

    private fun applySurvivingProof(
        minted: AbsenceProof,
        witnessed: Set<RootedPageId>,
        retiredAt: Long,
    ): Set<RootedPageId> {
        // REFUTATION: an inferred absence is contradicted by seeing the page under any path in the same root.
        val proof = minted.survives(witnessed)
        return when {
            proof == null -> {
                logger.info {
                    "root '${minted.root}''s ${minted.source} proof is REFUTED in full: this observation READ every id it " +
                        "covers, and a page we are looking at is not a page that is absent"
                }
                emptySet()
            }

            !proofIsFresh(proof) -> emptySet()
            else -> {
                if (proof.covers.size != minted.covers.size) {
                    logger.info {
                        "refusing ${minted.covers.size - proof.covers.size} binding(s) of root '${minted.root}''s " +
                            "${minted.source} proof: this observation READ those ids somewhere, so they are not gone"
                    }
                }
                proof.covers.mapNotNullTo(mutableSetOf()) { ref -> retireBinding(proof, ref, retiredAt) }
            }
        }
    }

    private fun proofIsFresh(proof: AbsenceProof): Boolean {
        // FRESHNESS: both tokens are re-read inside the transaction and must match exactly.
        val current = observations.selectObservationAndEpoch(proof.root).executeAsOneOrNull()
        val fresh = current?.matches(proof.observationId, proof.bindingEpoch) == true
        if (!fresh) {
            logger.warn {
                "discarding a ${proof.source} proof for root '${proof.root}': it was minted under observation " +
                    "${proof.observationId.value}/binding-epoch ${proof.bindingEpoch.value}, and the root is now at " +
                    "${current?.observation_id}/${current?.binding_epoch} - the view it was minted from has been " +
                    "revoked or a binding it covers was re-created, so it authorizes nothing"
            }
        }
        return fresh
    }

    private fun retireBinding(proof: AbsenceProof, ref: BindingRef, retiredAt: Long): RootedPageId? {
        // p.root == the binding's root: BindingRef carries no root, so this lookup prevents cross-root proof replay.
        val binding = idMap.selectBinding(root = proof.root, path = ref.path).executeAsOneOrNull()
        return when {
            binding == null || binding.id != ref.id -> null
            else -> {
                idMap.retire(
                    id = binding.id,
                    root = proof.root,
                    path = ref.path,
                    materialized = binding.materialized,
                    retiredAt = retiredAt,
                )
                idMap.deleteBinding(root = proof.root, path = ref.path)
                checkpoints.deleteRow(root = proof.root, id = binding.id)
                // The dirty row is interrupted-save recovery state and is cleared only under this retirement proof.
                dirty.deleteByRootId(root = proof.root, id = binding.id)
                RootedPageId(proof.root, ref.id)
            }
        }
    }

    private fun applyAdvance(advance: GitCheckpointAdvance, unavailable: Set<RootName>): Boolean {
        // An advance consumes a range permanently, so an unavailable root withholds it.
        val current = observations.selectObservationAndEpoch(advance.root).executeAsOneOrNull()
        return when {
            advance.root in unavailable -> {
                logger.warn {
                    "withholding a GIT checkpoint advance for root '${advance.root}': it was marked unavailable " +
                        "since the range was read, and a consumed range is never re-examined"
                }
                false
            }

            current?.matches(advance.observationId, advance.bindingEpoch) != true -> {
                logger.warn {
                    "discarding a GIT checkpoint advance for root '${advance.root}': it was minted under observation " +
                        "${advance.observationId.value}/binding-epoch ${advance.bindingEpoch.value}, and the root is now at " +
                        "${current?.observation_id}/${current?.binding_epoch} - the view it was minted from has been " +
                        "revoked or a binding was re-created, so it advances nothing"
                }
                false
            }

            else -> {
                gitCheckpoints.upsertHead(root = advance.root, head = advance.head)
                true
            }
        }
    }

    override fun gitHead(root: RootName): String? = gitCheckpoints.selectHead(root).executeAsOneOrNull()

    override fun observation(root: RootName): ObservationId =
        db.transactionWithResult {
            val existing = observations.selectObservation(root).executeAsOneOrNull()
            if (existing != null) {
                ObservationId(existing)
            } else {
                // First sight. The value is arbitrary; what matters is that it is DURABLE from here on, so the
                // next restart can be told apart from this one.
                ObservationId(1).also { observations.upsertObservation(root = root, observationId = it.value) }
            }
        }

    override fun bindingEpoch(root: RootName): BindingEpoch =
        BindingEpoch(observations.selectObservationAndEpoch(root).executeAsOneOrNull()?.binding_epoch ?: 0L)

    override fun observations(): Map<RootName, ObservationId> =
        observations.selectAllObservations().executeAsList().associate { it.root to ObservationId(it.observation_id) }

    override fun revoke(root: RootName): ObservationId =
        db.transactionWithResult {
            val next = ObservationId(observations.selectObservation(root).executeAsOneOrNull() ?: 0L).next()
            observations.upsertObservation(root = root, observationId = next.value)
            logger.info { "revoked root '$root''s observation; every proof minted before ${next.value} is now worthless" }
            next
        }

    /** The two-token freshness compare: a proof (or advance) survives only if BOTH stamps still equal the root's. */
    private fun SelectObservationAndEpoch.matches(observationId: ObservationId, bindingEpoch: BindingEpoch): Boolean =
        observation_id == observationId.value && binding_epoch == bindingEpoch.value

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
