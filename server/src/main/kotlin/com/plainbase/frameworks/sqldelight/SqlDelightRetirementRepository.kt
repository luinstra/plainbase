package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.RootName
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

/**
 * SQLDelight adapter for [RetirementRepository] - the proof-apply transaction, over `root_observation`,
 * `id_map`, `retired_binding`, `page_checkpoint` and `dirty_page` (see the port for the model).
 *
 * All five tables live in the SAME app database, which is what lets the freshness compare and the deletions it
 * authorizes be ONE transaction rather than a protocol. That is not an implementation convenience; it is the
 * entire safety argument for the reap.
 */
class SqlDelightRetirementRepository(
    private val db: PlainbaseDb,
    private val clock: Clock = Clock.System,
) : RetirementRepository {

    private val observations get() = db.rootObservationQueries
    private val idMap get() = db.idMapQueries
    private val checkpoints get() = db.pageCheckpointQueries
    private val dirty get() = db.dirtyPageQueries

    override fun applyProofs(proofs: List<AbsenceProof>, witnessed: Set<PageId>): Set<BindingRef> {
        if (proofs.isEmpty()) return emptySet() // C0's steady state: no source mints one, so nothing is ever reaped
        return db.transactionWithResult {
            val retiredAt = clock.now().toEpochMilliseconds()
            val applied = mutableSetOf<BindingRef>()
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
                // FRESHNESS, re-read inside the transaction. A revocation that committed before this
                // transaction opened is visible here, the compare fails, and the whole proof is worth nothing -
                // which is precisely what "a restart is itself a revocation" has to mean for it to be true.
                val current = observations.selectObservation(proof.root).executeAsOneOrNull()
                if (current != proof.observationId.value) {
                    logger.warn {
                        "discarding a ${proof.source} proof for root '${proof.root}': it was minted under observation " +
                            "${proof.observationId.value}, and the root's current observation is $current - the view it " +
                            "was minted from has been revoked, so it authorizes nothing"
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
                    checkpoints.deleteRow(binding.id)
                    // The dirty_page row is an interrupted save's ONLY recovery record, and it is USER CONTENT.
                    // It is cleared HERE, under the proof that retires the binding it belongs to, and NOWHERE
                    // else - never on a bare "the file was not there" read (ledger A1's worst consequence).
                    dirty.deleteById(binding.id)
                    applied += ref
                }
            }
            logger.info { "applied ${proofs.size} absence proof(s): ${applied.size} binding(s) retired" }
            applied
        }
    }

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

    override fun observations(): Map<RootName, ObservationId> =
        observations.selectAllObservations().executeAsList().associate { it.root to ObservationId(it.observation_id) }

    override fun revoke(root: RootName): ObservationId =
        db.transactionWithResult {
            val next = ObservationId(observations.selectObservation(root).executeAsOneOrNull() ?: 0L).next()
            observations.upsertObservation(root = root, observationId = next.value)
            logger.info { "revoked root '$root''s observation; every proof minted before ${next.value} is now worthless" }
            next
        }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
