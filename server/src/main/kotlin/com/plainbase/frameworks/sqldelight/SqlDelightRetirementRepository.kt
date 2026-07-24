package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
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
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> {
        // A baseline or empty-reap advance (C4) arrives with NO proofs by construction, so the empty-list guard
        // must check BOTH lists or the advance would never reach the transaction that lands the checkpoint.
        if (proofs.isEmpty() && advances.isEmpty()) return emptySet()
        return db.transactionWithResult {
            val retiredAt = clock.now().toEpochMilliseconds()
            val applied = mutableSetOf<RootedPageId>()
            for (minted in proofs) {
                // REFUTATION, before anything else: an INFERRED absence is a conclusion drawn from a gap in what we
                // observed, and SEEING the page refutes it. A renamed page's old path is "absent" to every source we
                // have, and its id is sitting in the file we just read under the new name - so this is what stands
                // between a `git mv` and a permanently 410'd permalink. Asked HERE, at the door of the only deleter,
                // rather than trusted to a caller: a source that cannot answer "what did we SEE?" cannot reap.
                val proof = minted.survives(witnessed) ?: run {
                    logger.info {
                        "root '${minted.root}''s ${minted.source} proof is REFUTED in full: this observation READ every id it " +
                            "covers, and a page we are looking at is not a page that is absent"
                    }
                    continue
                }
                if (proof.covers.size != minted.covers.size) {
                    logger.info {
                        "refusing ${minted.covers.size - proof.covers.size} binding(s) of root '${minted.root}''s " +
                            "${minted.source} proof: this observation READ those ids somewhere, so they are not gone"
                    }
                }
                // FRESHNESS, re-read inside the transaction - BOTH tokens, exact-equality, fail-closed. A revocation
                // (a new observation) OR a re-bind (an advanced binding epoch) that committed before this transaction
                // opened is visible here, the compare fails, and the whole proof is worth nothing. The observation
                // half is "a restart is itself a revocation"; the binding-epoch half is "a restore's re-bind revokes
                // the proof that would have reaped the page it just re-created" - orthogonal, and either kills it.
                val current = observations.selectObservationAndEpoch(proof.root).executeAsOneOrNull()
                if (current == null || !current.matches(proof.observationId, proof.bindingEpoch)) {
                    logger.warn {
                        "discarding a ${proof.source} proof for root '${proof.root}': it was minted under observation " +
                            "${proof.observationId.value}/binding-epoch ${proof.bindingEpoch.value}, and the root is now at " +
                            "${current?.observation_id}/${current?.binding_epoch} - the view it was minted from has been " +
                            "revoked or a binding it covers was re-created, so it authorizes nothing"
                    }
                    continue
                }
                for (ref in proof.covers) {
                    // p.root == the binding's root: BindingRef carries no root, so this lookup is the ONLY thing
                    // standing between a proof minted for root A and a same-path, same-id binding in root B.
                    val binding = idMap.selectBinding(root = proof.root, path = ref.path).executeAsOneOrNull() ?: continue
                    if (binding.id != ref.id) continue // the page at that path is SOMEONE ELSE'S now - the proof is not about it
                    idMap.retire(
                        id = binding.id,
                        root = proof.root,
                        path = ref.path,
                        materialized = binding.materialized,
                        retiredAt = retiredAt,
                    )
                    idMap.deleteBinding(root = proof.root, path = ref.path)
                    checkpoints.deleteRow(root = proof.root, id = binding.id)
                    // The dirty_page row is an interrupted save's ONLY recovery record, and it is USER CONTENT.
                    // It is cleared HERE, under the proof that retires the binding it belongs to, and NOWHERE
                    // else - never on a bare "the file was not there" read (ledger A1's worst consequence).
                    dirty.deleteByRootId(root = proof.root, id = binding.id)
                    applied += RootedPageId(proof.root, ref.id)
                }
            }
            // The GIT checkpoint advances (C4), in the SAME transaction and behind the IDENTICAL freshness compare
            // the proofs above ride: a revoked view advances nothing, so a GIT proof discarded for freshness drops
            // its root's advance too. The same TWO stamps, one transaction, no window between the reap and the move.
            val advanced = mutableListOf<GitCheckpointAdvance>()
            for (advance in advances) {
                val current = observations.selectObservationAndEpoch(advance.root).executeAsOneOrNull()
                if (current == null || !current.matches(advance.observationId, advance.bindingEpoch)) {
                    logger.warn {
                        "discarding a GIT checkpoint advance for root '${advance.root}': it was minted under observation " +
                            "${advance.observationId.value}/binding-epoch ${advance.bindingEpoch.value}, and the root is now at " +
                            "${current?.observation_id}/${current?.binding_epoch} - the view it was minted from has been " +
                            "revoked or a binding was re-created, so it advances nothing"
                    }
                    continue
                }
                gitCheckpoints.upsertHead(root = advance.root, head = advance.head)
                advanced += advance
            }
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
