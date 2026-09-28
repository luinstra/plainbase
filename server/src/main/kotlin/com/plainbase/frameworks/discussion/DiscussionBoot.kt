package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.BootTombstone
import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionReparser
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.ReparseRootResult
import com.plainbase.frameworks.runtime.HistoryProviders
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@Suppress("TooGenericExceptionCaught")
class DiscussionBoot(
    private val rows: DiscussionRows,
    private val store: DiscussionStore,
    internal val fullReads: DiscussionFullReads,
    private val reparser: DiscussionReparser,
    private val sync: DiscussionSyncState,
    private val histories: HistoryProviders,
    private val monitor: ContentWriteMonitor,
    private val availability: RootAvailability,
    private val registry: RootRegistry,
    private val clock: Clock,
    private val identity: CommitIdentity = CommitIdentity("Plainbase", "plainbase@localhost"),
) {
    private val scopedRoots = registry.roots.filter { it.editable && it.backend is RootBackend.Local }

    fun run() {
        rows.writing { truncate() }
        scopedRoots.forEach { root ->
            try {
                if (!availability.current().isAvailable(root.name)) {
                    sync.enter(root.name, "not available at boot")
                    return@forEach
                }
                val tombstones = store.sweepBootResidue(root.name, clock.now(), 24.hours)
                val history = histories[root.name]
                if (history.enabled) {
                    restoreTombstones(root.name, history, tombstones)
                    reconcilePuts(root.name, history)
                }
                when (val result = reparser.reparseRoot(root.name, strict = true)) {
                    is ReparseRootResult.Complete -> Unit
                    is ReparseRootResult.Failed -> sync.enter(root.name, result.cause)
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                logger.warn(failure) { "discussion boot reconciliation failed for root ${root.name.value}" }
                val cause = if (failure.message?.startsWith("symlink:") == true) {
                    "symlink"
                } else {
                    failure.message ?: "discussion boot reconciliation failed"
                }
                sync.enter(root.name, cause)
            }
        }
    }

    private fun restoreTombstones(
        root: RootName,
        history: HistoryProvider,
        tombstones: List<BootTombstone>,
    ) {
        tombstones.forEach { item ->
            if (!item.targetMissing) {
                logger.warn { "kept discussion tombstone for ${item.tombstone.entry.id.value}: target is still present" }
                return@forEach
            }
            val directory = discussionDirectory(item.tombstone.entry.id)
            val head = history.headBlobs(listOf(directory))
            val target = discussionEntryPath(item.tombstone.entry.id, item.tombstone.entry.name)
            val tombstoneBlob = store.tombstoneBytes(root, item.tombstone)?.let(history::blobId)
            if (head == null || tombstoneBlob == null || head[target] != tombstoneBlob) {
                logger.warn { "kept discussion tombstone for ${item.tombstone.entry.id.value}: HEAD blob does not match" }
                return@forEach
            }
            when (val restored = store.restore(root, item.tombstone)) {
                is StoreWrite.Written -> logger.info { "restored committed discussion entry ${target.value}" }
                else -> logger.warn { "could not restore committed discussion entry ${target.value}: $restored" }
            }
        }
    }

    private fun reconcilePuts(root: RootName, history: HistoryProvider) {
        val batch = ArrayList<DiscussionId>(HEAD_BATCH_SIZE)
        val visit = store.visit(root) { id, _ ->
            batch += id
            if (batch.size == HEAD_BATCH_SIZE) {
                reconcileBatch(root, history, batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) reconcileBatch(root, history, batch.toList())
        when (visit) {
            is CollectionVisit.Visited,
            CollectionVisit.Absent,
            -> Unit
            CollectionVisit.Symlinked -> {
                logger.warn { "discussion collection is symlinked at boot for root ${root.value}" }
            }
            is CollectionVisit.Failed -> {
                logger.warn { "discussion collection visit failed at boot for root ${root.value}: ${visit.cause}" }
                sync.enter(root, visit.cause)
            }
        }
    }

    private fun reconcileBatch(root: RootName, history: HistoryProvider, ids: List<DiscussionId>) {
        val directories = ids.map(::discussionDirectory)
        val head = history.headBlobs(directories)
        if (head == null) {
            logger.warn { "could not verify discussion HEAD for root ${root.value}; skipping ${ids.size} id(s)" }
            return
        }
        ids.forEach { id ->
            val changes = fullReads.withFullRead(root, id) { read ->
                val present = read as? EntriesRead.Present ?: return@withFullRead emptyList()
                val changes = mutableListOf<HistoryChange>()
                present.entries.forEach entryLoop@{ entry ->
                    if (!entry.complete) return@entryLoop
                    val bytes = entry.take()
                    val objectId = history.blobId(bytes) ?: return@entryLoop
                    val path = discussionEntryPath(id, entry.name)
                    if (head[path] != objectId) changes += HistoryChange.Put(path, bytes)
                }
                changes
            }
            if (changes.isEmpty()) return@forEach
            val outcome = monitor.withLock {
                history.commitChanges(changes, "discussion: reconcile ${id.value}", identity, identity)
            }
            when (outcome) {
                is CommitOutcome.Committed -> logger.info {
                    "reconciled discussion ${id.value} at boot for root ${root.value}"
                }
                is CommitOutcome.NotCommitted -> logger.warn(outcome.cause) {
                    "discussion reconcile was not committed for root ${root.value}, id ${id.value}"
                }
                is CommitOutcome.Unknown -> logger.warn(outcome.cause) {
                    "discussion reconcile outcome is unknown for root ${root.value}, id ${id.value}"
                }
            }
        }
    }

    private fun discussionDirectory(id: DiscussionId): TreePath =
        TreePath.require(".plainbase/discussions/${id.value}")

    private fun discussionEntryPath(id: DiscussionId, name: EntryName): TreePath =
        TreePath.require(".plainbase/discussions/${id.value}/${name.fileName}")

    private companion object {
        const val HEAD_BATCH_SIZE = 64
        val logger = KotlinLogging.logger {}
    }
}
