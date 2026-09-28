package com.plainbase.domain.service

import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPersistenceFailure
import com.plainbase.domain.discussion.DiscussionRowWriter
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.RowDerivation
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.root.RootName
import io.github.oshai.kotlinlogging.KotlinLogging

sealed interface ReparseOutcome {
    data class Applied(val state: String, val stamp: Stamp?) : ReparseOutcome
    data class Failed(val cause: String) : ReparseOutcome
}

sealed interface ReparseRootResult {
    data class Complete(val applied: Boolean) : ReparseRootResult
    data class Failed(val cause: String) : ReparseRootResult
}

@Suppress("TooGenericExceptionCaught")
class DiscussionReparser(
    private val scopeRoots: Set<RootName>,
    private val rows: DiscussionRows,
    private val store: DiscussionStore,
    private val fullReads: DiscussionFullReads,
) {
    fun reparseOne(root: RootName, id: DiscussionId): ReparseOutcome {
        requireRoot(root)
        return try {
            rows.writing { reparseLocked(root, id, this) }
        } catch (failure: RootUnavailable) {
            throw failure
        } catch (failure: DiscussionPersistenceFailure) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            ReparseOutcome.Failed(failure.message ?: "discussion read or apply failed")
        }
    }

    fun settle(root: RootName, id: DiscussionId): ReparseOutcome {
        val first = reparseOne(root, id)
        if (first is ReparseOutcome.Applied) return first
        var reason: String? = null
        val second = rows.writing {
            when (val retry = reparseLocked(root, id, this)) {
                is ReparseOutcome.Applied -> retry
                is ReparseOutcome.Failed -> {
                    reason = retry.cause
                    apply(root, id, RowUpdate.Failed(retry.cause), null, dropMatch = false)
                    ReparseOutcome.Applied("failed", null)
                }
            }
        }
        val failedReason = reason ?: return second
        logger.error { "discussion re-parse failed twice for root ${root.value}, discussion ${id.value}: $failedReason" }
        return second
    }

    fun <T> withRowsWrite(block: DiscussionRowWriter.() -> T): T = rows.writing(block)

    fun reparseRoot(root: RootName, strict: Boolean): ReparseRootResult {
        requireRoot(root)
        return try {
            var applied = false
            val visit = store.visit(root) { id, _ ->
                val currentStamp = if (strict) {
                    null
                } else {
                    try {
                        store.stamp(root, id)
                    } catch (failure: RootUnavailable) {
                        throw failure
                    } catch (failure: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw failure
                    } catch (_: Exception) {
                        null
                    }
                }
                if (currentStamp != null && !currentStamp.racy && rows.row(root, id)?.stamp == currentStamp) return@visit
                if (settle(root, id).wasSuccessfullyApplied()) applied = true
            }
            when (visit) {
                CollectionVisit.Symlinked -> return ReparseRootResult.Failed("symlink")
                is CollectionVisit.Failed -> return ReparseRootResult.Failed(visit.cause)
                is CollectionVisit.Visited, CollectionVisit.Absent -> Unit
            }
            var after: DiscussionId? = null
            while (true) {
                val batch = rows.ids(root, after, ID_BATCH_SIZE)
                if (batch.isEmpty()) break
                batch.forEach { id ->
                    val probe = store.read(root, id, only = emptySet())
                    if (probe !is EntriesRead.Present && settle(root, id).wasSuccessfullyApplied()) applied = true
                }
                after = batch.last()
            }
            ReparseRootResult.Complete(applied)
        } catch (failure: RootUnavailable) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            logger.warn(failure) { "discussion re-parse failed for root ${root.value}" }
            ReparseRootResult.Failed(failure.message ?: "discussion re-parse failed")
        }
    }

    private fun requireRoot(root: RootName) {
        require(root in scopeRoots) { "not a discussion root: ${root.value}" }
    }

    private fun ReparseOutcome.wasSuccessfullyApplied(): Boolean =
        this is ReparseOutcome.Applied && state != "failed"

    private fun reparseLocked(root: RootName, id: DiscussionId, writer: DiscussionRowWriter): ReparseOutcome = try {
        val beforeReadStamp = store.stamp(root, id)
        val (stamp, derived) = fullReads.withPermit {
            val read = store.read(root, id)
            val afterReadStamp = store.stamp(root, id)
            beforeReadStamp.takeIf { it == afterReadStamp } to RowDerivation.derive(id, read)
        }
        val update = derived.update
        if (update is RowUpdate.Unknown) {
            ReparseOutcome.Failed(update.cause)
        } else {
            writer.apply(root, id, update, stamp, dropMatch = false)
            ReparseOutcome.Applied(update.state ?: "absent", stamp)
        }
    } catch (failure: RootUnavailable) {
        throw failure
    } catch (failure: DiscussionPersistenceFailure) {
        throw failure
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
    } catch (failure: Exception) {
        ReparseOutcome.Failed(failure.message ?: "discussion read or apply failed")
    }

    companion object {
        private const val ID_BATCH_SIZE = 500
        private val logger = KotlinLogging.logger {}
    }
}
