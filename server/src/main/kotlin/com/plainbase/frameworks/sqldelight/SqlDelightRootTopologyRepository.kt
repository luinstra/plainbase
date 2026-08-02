package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.RootTopologyRepository
import com.plainbase.domain.root.AtRisk
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootTopology
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * SQLDelight adapter for [RootTopologyRepository] - the durable binding latch (`root_topology`), over the SAME
 * database as `id_map` and `root_observation`.
 *
 * That is not a convenience. [observeBinding] snapshots the at-risk bindings FROM `id_map` and revokes the root's
 * observation token in `root_observation`, and it does BOTH in the transaction that records the binding: a rebind
 * that recorded itself without snapshotting what it put at risk would be a latch with nothing behind it, and a proof
 * minted against the tree we USED to be looking at must not survive the discovery that we are looking somewhere else.
 */
class SqlDelightRootTopologyRepository(private val db: PlainbaseDb) : RootTopologyRepository {

    private val topologies get() = db.rootTopologyQueries
    private val idMap get() = db.idMapQueries
    private val observations get() = db.rootObservationQueries

    override fun topology(root: RootName): RootTopology? =
        topologies.selectTopology(root).executeAsOneOrNull()?.let { row ->
            RootTopology(
                root = row.root,
                binding = RootBinding(row.binding),
                status = if (row.status == BindingStatus.TRUSTED.name) BindingStatus.TRUSTED else BindingStatus.UNRESOLVED,
                atRisk = decode(root, row.at_risk),
            )
        }

    override fun observeBinding(root: RootName, binding: RootBinding): RootTopology {
        // Both levels are read HERE, and everything this transaction has to say is captured as fields and rendered
        // after it returns: an app-DB transaction holds the write lock for its whole body, so a blocked log consumer
        // inside one would hold the lock too.
        val warnEnabled = logger.isWarnEnabled()
        val errorEnabled = logger.isErrorEnabled()
        var rebind: Rebind? = null
        var decodeFailure: DecodeFailure? = null
        var decodeDetail: String? = null
        val topology = db.transactionWithResult {
            val existing = topologies.selectTopology(root).executeAsOneOrNull()
            if (existing != null && existing.binding == binding.value) {
                return@transactionWithResult RootTopology(
                    root = root,
                    binding = binding,
                    status = if (existing.status == BindingStatus.TRUSTED.name) BindingStatus.TRUSTED else BindingStatus.UNRESOLVED,
                    // An UNCHANGED binding keeps the trust it earned - and the snapshot it earned it against, so a row
                    // that has NOT been promoted yet still has to satisfy the SAME set it was latched with.
                    atRisk = decode(root, existing.at_risk) { failure, detail ->
                        if (errorEnabled) {
                            decodeFailure = failure
                            decodeDetail = detail
                        }
                    },
                )
            }
            // First sight (null -> X) or a CHANGE. Both are the same fact: this root now claims to be somewhere we
            // have never verified, and everything its durable rows say is about somewhere else until we have.
            val atRisk = AtRisk.Bindings(
                idMap.selectAllBindings().executeAsList()
                    .filter { it.root == root }
                    .mapTo(mutableSetOf()) { BindingRef(it.path, it.id) },
            )
            topologies.upsertTopology(
                root = root,
                binding = binding.value,
                status = BindingStatus.UNRESOLVED.name,
                atRisk = encode(atRisk.refs),
            )
            // A rebind REVOKES: whatever a proof minted a moment ago was about, it was not about this tree. (The same
            // write [SqlDelightRetirementRepository.revoke] makes, in the same table, in this transaction - so a proof
            // in flight against the old binding cannot be cashed after the new one lands.)
            val revoked = observations.selectObservation(root).executeAsOneOrNull()?.plus(1) ?: 1L
            observations.upsertObservation(root = root, observationId = revoked)
            if (warnEnabled) {
                rebind = Rebind(binding = binding.value, previous = existing?.binding, atRiskCount = atRisk.refs.size)
            }
            RootTopology(root = root, binding = binding, status = BindingStatus.UNRESOLVED, atRisk = atRisk)
        }
        rebind?.let { change ->
            logger.warn {
                "root '$root' is now bound to ${change.binding}" +
                    (change.previous?.let { " (it was bound to $it)" } ?: " (first sight)") +
                    ": UNRESOLVED, with ${change.atRiskCount} binding(s) at risk. It proves NOTHING until they are witnessed " +
                    "BY IDENTITY there - a LIST of the wrong bucket is a perfectly successful LIST"
            }
        }
        decodeFailure?.let { failure -> logDecodeFailure(root, failure, decodeDetail) }
        return topology
    }

    /** The [observeBinding] warn's fields, captured inside the transaction and rendered after it returns. */
    private data class Rebind(val binding: String, val previous: String?, val atRiskCount: Int)

    override fun trust(root: RootName) {
        topologies.updateStatus(status = BindingStatus.TRUSTED.name, root = root)
    }

    /**
     * The at-risk snapshot, sorted by the raw UTF-8 bytes of the NFC path (the SAME total order the store's NFC
     * collision rule uses) so the durable document is deterministic rather than map-iteration-shaped.
     */
    private fun encode(refs: Set<BindingRef>): String {
        val document = AtRiskDocument(
            version = AT_RISK_VERSION,
            bindings = refs
                .sortedWith { left, right -> RawByteOrder.compare(left.path.value, right.path.value) }
                .map { AtRiskBinding(path = it.path.value, id = it.id.value) },
        )
        return Json.encodeToString(document)
    }

    /**
     * A snapshot that does not decode is [AtRisk.Unreadable], NEVER an empty set - an empty set is trivially
     * satisfied, and a corrupt safety latch that promotes itself to TRUSTED is worse than no latch at all.
     */
    private fun decode(
        root: RootName,
        raw: String,
        // [topology] is NOT in a transaction and must keep logging immediately, while [observeBinding] reaches this
        // from inside one and captures instead. Both render from the ONE `when` below, which is what keeps the three
        // texts identical between the two routes.
        onFailure: (DecodeFailure, String?) -> Unit = { failure, detail -> logDecodeFailure(root, failure, detail) },
    ): AtRisk {
        val document = runCatching {
            Json.decodeFromString<AtRiskDocument>(raw)
        }.getOrElse { failure ->
            when (failure) {
                is SerializationException -> {
                    onFailure(DecodeFailure.UNDECODABLE, failure.message)
                    return AtRisk.Unreadable
                }
                else -> throw failure
            }
        }
        if (document.version != AT_RISK_VERSION) {
            onFailure(DecodeFailure.WRONG_VERSION, document.version.toString())
            return AtRisk.Unreadable
        }
        val refs = document.bindings.map { binding ->
            val path = TreePath.of(binding.path)
            val id = PageId.of(binding.id)
            if (path == null || id == null) {
                onFailure(DecodeFailure.INVALID_BINDING, binding.path)
                return AtRisk.Unreadable
            }
            BindingRef(path, id)
        }
        return AtRisk.Bindings(refs.toSet())
    }

    private fun logDecodeFailure(root: RootName, failure: DecodeFailure, detail: String?) {
        when (failure) {
            DecodeFailure.UNDECODABLE -> logger.error {
                "root '$root''s at-risk snapshot is undecodable ($detail): " +
                    "it can satisfy NOTHING, so the root stays UNRESOLVED"
            }

            DecodeFailure.WRONG_VERSION -> logger.error {
                "root '$root''s at-risk snapshot is version $detail (expected $AT_RISK_VERSION): it can satisfy " +
                    "NOTHING, so the root stays UNRESOLVED"
            }

            DecodeFailure.INVALID_BINDING -> logger.error {
                "root '$root''s at-risk snapshot names an invalid binding ('$detail'): the root stays UNRESOLVED"
            }
        }
    }

    private enum class DecodeFailure { UNDECODABLE, WRONG_VERSION, INVALID_BINDING }

    @Serializable
    private data class AtRiskDocument(val version: Int, val bindings: List<AtRiskBinding>)

    @Serializable
    private data class AtRiskBinding(val path: String, val id: String)

    companion object {
        private const val AT_RISK_VERSION = 1
        private val logger = KotlinLogging.logger {}
    }
}
