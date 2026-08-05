package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.ProofSource
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
 *
 * @param clock read INSIDE the proof-apply transaction, so `now()` must neither log nor block: an app-DB
 *   transaction holds the write lock for its whole body.
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

    /** Unbounded batch, hold linear in covers, over budget past 150k: `docs/reports/issue-23-write-lock-hold-measurement-report.md` */
    override fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<RootedPageId>,
        unavailableNow: () -> Set<RootName>,
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> {
        // A baseline or empty-reap advance (C4) arrives with NO proofs by construction, so the empty-list guard
        // must check BOTH lists or the advance would never reach the transaction that lands the checkpoint.
        if (proofs.isEmpty() && advances.isEmpty()) return emptySet()
        // Every line this pass produces is ACCUMULATED as fields and rendered after the transaction returns: an
        // app-DB transaction holds the write lock for its whole body, and a blocked log consumer would hold it too.
        // The two level reads happen HERE so even they sit off the lock, and a level that is off accumulates nothing.
        val log = DeferredProofLog(infoEnabled = logger.isInfoEnabled(), warnEnabled = logger.isWarnEnabled())
        val applied = db.transactionWithResult {
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
                    applyProof(proof, witnessed, unavailable, retiredAt, log)
                }
            // The GIT checkpoint advances (C4), in the SAME transaction and behind the IDENTICAL freshness compare
            // the proofs above ride: a revoked view advances nothing, so a GIT proof discarded for freshness drops
            // its root's advance too. The same TWO stamps, one transaction, no window between the reap and the move.
            val advanced = advances.filter { advance -> applyAdvance(advance, unavailable, log) }
            log.advanceSample(advanced)
            applied
        }
        emit(log, proofs.size, applied.size)
        return applied
    }

    private fun applyProof(
        minted: AbsenceProof,
        witnessed: Set<RootedPageId>,
        unavailable: Set<RootName>,
        retiredAt: Long,
        log: DeferredProofLog,
    ): Set<RootedPageId> =
        when {
            minted.source.inferred && minted.root in unavailable -> {
                // STANDING: inferred evidence gathered before an availability hole proves nothing about the tree now.
                log.unavailable(minted.root, minted.source)
                emptySet()
            }

            else -> applySurvivingProof(minted, witnessed, retiredAt, log)
        }

    private fun applySurvivingProof(
        minted: AbsenceProof,
        witnessed: Set<RootedPageId>,
        retiredAt: Long,
        log: DeferredProofLog,
    ): Set<RootedPageId> {
        // REFUTATION: an inferred absence is contradicted by seeing the page under any path in the same root.
        val proof = minted.survives(witnessed)
        return when {
            proof == null -> {
                log.refuted(minted.root, minted.source)
                emptySet()
            }

            !proofIsFresh(proof, log) -> emptySet()
            else -> {
                if (proof.covers.size != minted.covers.size) {
                    log.refusedBindings(minted.root, minted.source, minted.covers.size - proof.covers.size)
                }
                proof.covers.mapNotNullTo(mutableSetOf()) { ref -> retireBinding(proof, ref, retiredAt) }
            }
        }
    }

    private fun proofIsFresh(proof: AbsenceProof, log: DeferredProofLog): Boolean {
        // FRESHNESS: both tokens are re-read inside the transaction and must match exactly.
        val current = observations.selectObservationAndEpoch(proof.root).executeAsOneOrNull()
        val fresh = current?.matches(proof.observationId, proof.bindingEpoch) == true
        if (!fresh) {
            log.stale(
                root = proof.root,
                source = proof.source,
                mintedObservation = proof.observationId.value,
                mintedEpoch = proof.bindingEpoch.value,
                currentObservation = current?.observation_id,
                currentEpoch = current?.binding_epoch,
            )
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

    private fun applyAdvance(advance: GitCheckpointAdvance, unavailable: Set<RootName>, log: DeferredProofLog): Boolean {
        // An advance consumes a range permanently, so an unavailable root withholds it.
        val current = observations.selectObservationAndEpoch(advance.root).executeAsOneOrNull()
        return when {
            advance.root in unavailable -> {
                log.advanceWithheld(advance.root)
                false
            }

            current?.matches(advance.observationId, advance.bindingEpoch) != true -> {
                log.advanceStale(
                    root = advance.root,
                    mintedObservation = advance.observationId.value,
                    mintedEpoch = advance.bindingEpoch.value,
                    currentObservation = current?.observation_id,
                    currentEpoch = current?.binding_epoch,
                )
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

    override fun revoke(root: RootName): ObservationId {
        val next = db.transactionWithResult {
            val revoked = ObservationId(observations.selectObservation(root).executeAsOneOrNull() ?: 0L).next()
            observations.upsertObservation(root = root, observationId = revoked.value)
            revoked
        }
        // AFTER the transaction, like every other emission here: the write lock is held for the whole body.
        logger.info { "revoked root '$root''s observation; every proof minted before ${next.value} is now worthless" }
        return next
    }

    /**
     * Renders everything one [applyProofs] pass accumulated, in ONE total order: the notes in first-occurrence
     * order, then the refusal totals, then the cap-overflow notice, then the pass summary. Deduplicated records
     * carry an occurrence suffix. One line changes text deliberately: the refusal count is now the batch SUM over a
     * `(root, source)` pair rather than one proof's delta. Every other byte is what the in-transaction call wrote.
     */
    private fun emit(log: DeferredProofLog, proofCount: Int, appliedCount: Int) {
        log.notes.forEach { (key, occurrences) ->
            val suffix = suffixFor(key.reason, occurrences)
            when (key.reason) {
                ProofNoteReason.UNAVAILABLE -> logger.warn {
                    "discarding a ${key.source} proof for root '${key.root}': the root has been marked " +
                        "unavailable since this pass gathered its evidence, so that evidence proves nothing about " +
                        "the tree that is there now" + suffix
                }

                ProofNoteReason.REFUTED -> logger.info {
                    "root '${key.root}''s ${key.source} proof is REFUTED in full: this observation READ every id it " +
                        "covers, and a page we are looking at is not a page that is absent" + suffix
                }

                ProofNoteReason.STALE -> logger.warn {
                    "discarding a ${key.source} proof for root '${key.root}': it was minted under observation " +
                        "${key.mintedObservation}/binding-epoch ${key.mintedEpoch}, and the root is now at " +
                        "${key.currentObservation}/${key.currentEpoch} - the view it was minted from has been " +
                        "revoked or a binding it covers was re-created, so it authorizes nothing" + suffix
                }

                ProofNoteReason.ADVANCE_WITHHELD -> logger.warn {
                    "withholding a GIT checkpoint advance for root '${key.root}': it was marked unavailable " +
                        "since the range was read, and a consumed range is never re-examined" + suffix
                }

                ProofNoteReason.ADVANCE_STALE -> logger.warn {
                    "discarding a GIT checkpoint advance for root '${key.root}': it was minted under observation " +
                        "${key.mintedObservation}/binding-epoch ${key.mintedEpoch}, and the root is now at " +
                        "${key.currentObservation}/${key.currentEpoch} - the view it was minted from has been " +
                        "revoked or a binding was re-created, so it advances nothing" + suffix
                }
            }
        }
        log.refusals.forEach { (key, total) ->
            logger.info {
                "refusing ${total.delta} binding(s) of root '${key.root}''s " +
                    "${key.source} proof: this observation READ those ids somewhere, so they are not gone" +
                    suffixFor(reason = null, occurrences = total.occurrences)
            }
        }
        if (log.suppressed > 0) {
            logger.warn {
                "suppressed ${log.suppressed} deferred log record(s) with new keys: this pass reached the ${log.cap}-key cap"
            }
        }
        // The advances get their own clause because the COMMON C4 pass mints no proof at all - a baseline, or a
        // range whose deletions this walk resolved by presence - and "applied 0 absence proof(s)" alone reads like
        // a pass that did nothing, on the one line an operator has to tell a moving checkpoint from a stuck one.
        //
        // Gated on the CAPTURED flag, not the live level: a level flipped on mid-pass would otherwise claim a batch
        // applied N proofs while silently omitting the advances the sink never sampled.
        if (log.infoEnabled) {
            logger.info {
                "applied $proofCount absence proof(s): $appliedCount binding(s) retired" +
                    log.advances
                        .joinToString(prefix = "; git checkpoint advanced: ", limit = ADVANCE_JOIN_LIMIT) { "'${it.root}' -> ${it.head}" }
                        .takeIf { log.advances.isNotEmpty() }.orEmpty()
            }
        }
    }

    /** The two-token freshness compare: a proof (or advance) survives only if BOTH stamps still equal the root's. */
    private fun SelectObservationAndEpoch.matches(observationId: ObservationId, bindingEpoch: BindingEpoch): Boolean =
        observation_id == observationId.value && binding_epoch == bindingEpoch.value

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * The JOINT retained-record budget across BOTH maps, not one each: what is bounded is records held while the write
 * lock is up, and two independent budgets would bound twice as many.
 */
private const val MAX_DEFERRED_NOTES = 64

/** The `limit` the summary's advance clause renders with, so the retained SAMPLE and the render cannot drift. */
private const val ADVANCE_JOIN_LIMIT = 8

private enum class ProofNoteReason { UNAVAILABLE, REFUTED, STALE, ADVANCE_WITHHELD, ADVANCE_STALE }

/**
 * The natural key a deferred line dedupes on. Every field a record does not carry is stored NULL: an advance has
 * no proof source, and the two no-token reasons carry neither stamp pair.
 */
private data class ProofNoteKey(
    val reason: ProofNoteReason,
    val root: RootName,
    val source: ProofSource?,
    val mintedObservation: Long?,
    val mintedEpoch: Long?,
    val currentObservation: Long?,
    val currentEpoch: Long?,
)

private data class RefusalKey(val root: RootName, val source: ProofSource)

/** A refusal keeps BOTH numbers: [delta] renders in the message, [occurrences] decides the suffix. */
private class RefusalTotal(var delta: Long, var occurrences: Int)

/**
 * What a proof-apply pass accumulates INSTEAD of logging, so no `write(2)` sits inside the app-DB transaction.
 *
 * It holds NO logger and formats nothing: the fields go in, `SqlDelightRetirementRepository.emit` renders them out
 * after the transaction returns. The level flags are captured at CONSTRUCTION - before the transaction is entered -
 * so a level that is off costs one read per level and accumulates nothing at all, which is what today's lazy
 * `logger.warn { }` already costs.
 */
private class DeferredProofLog(
    val infoEnabled: Boolean,
    private val warnEnabled: Boolean,
    val cap: Int = MAX_DEFERRED_NOTES,
) {
    val notes = LinkedHashMap<ProofNoteKey, Int>()
    val refusals = LinkedHashMap<RefusalKey, RefusalTotal>()
    val advances = mutableListOf<GitCheckpointAdvance>()
    var suppressed = 0

    fun unavailable(root: RootName, source: ProofSource) {
        if (!warnEnabled) return
        admit(noteKey(ProofNoteReason.UNAVAILABLE, root, source))
    }

    fun refuted(root: RootName, source: ProofSource) {
        if (!infoEnabled) return
        admit(noteKey(ProofNoteReason.REFUTED, root, source))
    }

    fun stale(
        root: RootName,
        source: ProofSource,
        mintedObservation: Long,
        mintedEpoch: Long,
        currentObservation: Long?,
        currentEpoch: Long?,
    ) {
        if (!warnEnabled) return
        // A root with no observation row renders `null/null`, which says nothing an operator can act on. Dropped
        // HERE rather than at emission so it never takes a budget slot from a record that would have been printed.
        if (currentObservation == null && currentEpoch == null) return
        admit(noteKey(ProofNoteReason.STALE, root, source, mintedObservation, mintedEpoch, currentObservation, currentEpoch))
    }

    fun refusedBindings(root: RootName, source: ProofSource, delta: Int) {
        // An INFO render, like the refuted note, so it rides the INFO flag rather than the WARN one.
        if (!infoEnabled) return
        val key = RefusalKey(root, source)
        val total = refusals[key]
        when {
            total != null -> {
                total.delta += delta
                total.occurrences++
            }

            admits() -> refusals[key] = RefusalTotal(delta = delta.toLong(), occurrences = 1)
            else -> suppressed++
        }
    }

    fun advanceWithheld(root: RootName) {
        if (!warnEnabled) return
        admit(noteKey(ProofNoteReason.ADVANCE_WITHHELD, root, source = null))
    }

    fun advanceStale(root: RootName, mintedObservation: Long, mintedEpoch: Long, currentObservation: Long?, currentEpoch: Long?) {
        if (!warnEnabled) return
        // Deliberately does NOT drop the `null/null` render that `stale` above drops: for an advance, the
        // missing-row case is the only record that a git range was dropped. Considered, not an oversight.
        admit(
            noteKey(ProofNoteReason.ADVANCE_STALE, root, source = null, mintedObservation, mintedEpoch, currentObservation, currentEpoch),
        )
    }

    /**
     * Retains one more advance than the summary clause prints, which is exactly enough for the render to be
     * byte-identical at every batch size: the extra element is what makes `joinToString` write its truncation marker.
     */
    fun advanceSample(advanced: List<GitCheckpointAdvance>) {
        if (!infoEnabled) return
        advances += advanced.take(ADVANCE_JOIN_LIMIT + 1)
    }

    private fun admit(key: ProofNoteKey) {
        val occurrences = notes[key]
        when {
            occurrences != null -> notes[key] = occurrences + 1
            admits() -> notes[key] = 1
            else -> suppressed++
        }
    }

    private fun admits(): Boolean = notes.size + refusals.size < cap

    private fun noteKey(
        reason: ProofNoteReason,
        root: RootName,
        source: ProofSource?,
        mintedObservation: Long? = null,
        mintedEpoch: Long? = null,
        currentObservation: Long? = null,
        currentEpoch: Long? = null,
    ): ProofNoteKey = ProofNoteKey(reason, root, source, mintedObservation, mintedEpoch, currentObservation, currentEpoch)
}

/** Empty at a single occurrence, so an undeduplicated line renders exactly the bytes it rendered before. */
private fun suffixFor(reason: ProofNoteReason?, occurrences: Int): String = when {
    occurrences <= 1 -> ""
    // An advance is not a proof, and a pass can carry advances with zero proofs, so the wording follows the record.
    reason == ProofNoteReason.ADVANCE_WITHHELD || reason == ProofNoteReason.ADVANCE_STALE -> " (x $occurrences advance(s))"
    else -> " (x $occurrences proof(s))"
}
