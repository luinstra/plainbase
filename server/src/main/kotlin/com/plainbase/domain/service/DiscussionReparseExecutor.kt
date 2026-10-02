package com.plainbase.domain.service

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionWatchSink
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Suppress("TooGenericExceptionCaught")
class DiscussionReparseExecutor(
    private val reparser: DiscussionReparser,
    private val sync: DiscussionSyncState,
    private val availability: RootAvailability,
    private val alarm: RebuildScheduler.Alarm,
    private val checkDelayMillis: Long = DEFAULT_CHECK_DELAY_MILLIS,
    private val recoveryBaseMillis: Long = DEFAULT_RECOVERY_BASE_MILLIS,
    private val recoveryMaxMillis: Long = DEFAULT_RECOVERY_MAX_MILLIS,
    private val beforeClear: (RootName) -> Unit = {},
    private val afterClear: (RootName) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicLong(0)
    private val nextToken = AtomicLong(0)
    private val pendingOnes = mutableMapOf<RootName, MutableMap<DiscussionId, Long>>()
    private val oneDrainTasks = mutableMapOf<RootName, Long>()
    private val drainingOnes = mutableSetOf<RootName>()
    private val pendingChecks = mutableMapOf<RootName, MutableMap<DiscussionId, Long>>()
    private val pendingRootChecks = mutableMapOf<RootName, Long>()
    private val checkTasks = mutableMapOf<RootName, Long>()
    private val roots = mutableMapOf<RootName, RootTaskState>()
    private val recovery = sync.scopeRoots.associateWith { RecoverySlot() }

    fun start() {
        if (isClosed()) return
        sync.attach(::scheduleRecovery)
        sync.scopeRoots.forEach { root ->
            if (sync.isUnsynced(root) && isAvailable(root)) scheduleRecovery(root)
        }
    }

    fun discussionChanged(root: RootName, id: DiscussionId) {
        requireRoot(root)
        enqueueOne(root, id)
        enqueueCheck(root, id)
    }

    fun collectionChanged(root: RootName) {
        requireRoot(root)
        enqueueRoot(root)
    }

    fun sinkFor(root: RootName): DiscussionWatchSink {
        requireRoot(root)
        return object : DiscussionWatchSink {
            override fun discussionChanged(id: DiscussionId) = this@DiscussionReparseExecutor.discussionChanged(root, id)
            override fun collectionChanged() = this@DiscussionReparseExecutor.collectionChanged(root)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(0, 1)) return
        try {
            (alarm as? AutoCloseable)?.close()
        } catch (failure: Throwable) {
            logger.warn(failure) { "closing the discussion re-parse alarm failed" }
        }
    }

    private fun enqueueOne(root: RootName, id: DiscussionId) {
        var collapse = false
        var scheduleToken: Long? = null
        synchronized(lock) {
            if (isClosed()) return
            val rootTask = roots[root]
            if (rootTask?.pending == true || rootTask?.running == true) {
                if (rootTask.running) rootTask.dirty = true
                return
            }
            val pending = pendingOnes.getOrPut(root, ::mutableMapOf)
            pending.putIfAbsent(id, nextToken.incrementAndGet())
            if (pending.size > MAX_PENDING_ONES) {
                pendingOnes.remove(root)
                oneDrainTasks.remove(root)
                collapse = true
            } else if (root !in drainingOnes && root !in oneDrainTasks) {
                val token = nextToken.incrementAndGet()
                oneDrainTasks[root] = token
                scheduleToken = token
            }
        }
        if (collapse) {
            enqueueRoot(root)
        } else {
            scheduleToken?.let { scheduleOneDrain(root, it) }
        }
    }

    private fun enqueueCheck(root: RootName, id: DiscussionId) {
        val dueAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(checkDelayMillis.coerceAtLeast(0))
        var schedule: Pair<Long, Long>? = null
        synchronized(lock) {
            if (isClosed()) return
            if (root in pendingRootChecks) {
                pendingRootChecks[root] = dueAt
            } else {
                val pending = pendingChecks.getOrPut(root, ::mutableMapOf)
                pending[id] = dueAt
                if (pending.size > MAX_PENDING_ONES) {
                    pendingChecks.remove(root)
                    pendingRootChecks[root] = pending.values.maxOrNull() ?: dueAt
                }
            }
            schedule = armCheckLocked(root)
        }
        schedule?.let { (token, delayMillis) -> scheduleCheck(root, token, delayMillis) }
    }

    private fun enqueueRoot(root: RootName) {
        val token: Long
        synchronized(lock) {
            if (isClosed()) return
            val state = roots.getOrPut(root, ::RootTaskState)
            if (state.running) {
                state.dirty = true
                return
            }
            if (state.pending) return
            state.pending = true
            token = nextToken.incrementAndGet()
            state.token = token
            pendingOnes.remove(root)
            oneDrainTasks.remove(root)
        }
        scheduleRoot(root, token)
    }

    private fun scheduleOneDrain(root: RootName, token: Long) {
        try {
            alarm.after(0) { runOnes(root, token) }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (oneDrainTasks[root] == token) oneDrainTasks.remove(root)
            }
            if (failure is Error) throw failure
            logger.error(failure) { "could not schedule discussion re-parse work" }
        }
    }

    private fun scheduleRoot(root: RootName, token: Long) {
        try {
            alarm.after(0) { runRoot(root, token) }
        } catch (failure: Throwable) {
            synchronized(lock) {
                val state = roots[root]
                if (state?.token == token && state.pending) {
                    state.pending = false
                    state.dirty = false
                }
            }
            if (failure is Error) throw failure
            logger.error(failure) { "could not schedule discussion collection re-parse" }
        }
    }

    private fun runOnes(root: RootName, token: Long) {
        if (!startOneDrain(root, token)) return
        try {
            drainOnes(root)
        } finally {
            resumeOneDrain(root)
        }
    }

    private fun startOneDrain(root: RootName, token: Long): Boolean = synchronized(lock) {
        if (isClosed() || oneDrainTasks[root] != token) {
            false
        } else {
            oneDrainTasks.remove(root)
            drainingOnes.add(root)
        }
    }

    private fun drainOnes(root: RootName) {
        while (true) {
            val batch = takeOneBatch(root) ?: return
            if (batch.isEmpty()) return
            if (!drainBatch(root, batch) || hasRootWork(root)) return
        }
    }

    private fun takeOneBatch(root: RootName): List<Pair<DiscussionId, Long>>? = synchronized(lock) {
        if (isClosed()) return@synchronized null
        if (hasRootWorkLocked(root)) {
            pendingOnes.remove(root)
            return@synchronized null
        }
        pendingOnes.remove(root)?.entries?.map { it.key to it.value }.orEmpty()
    }

    private fun drainBatch(root: RootName, batch: List<Pair<DiscussionId, Long>>): Boolean {
        var index = 0
        while (index < batch.size) {
            if (hasRootWork(root)) return false
            if (!isAvailable(root) || sync.isUnsynced(root)) {
                restoreOnes(root, batch.drop(index))
                return false
            }
            if (!applyOne(root, batch, index)) return false
            index++
        }
        return true
    }

    private fun applyOne(root: RootName, batch: List<Pair<DiscussionId, Long>>, index: Int): Boolean {
        val id = batch[index].first
        return try {
            val result = reparser.settle(root, id)
            if (result is ReparseOutcome.Applied && result.state != "failed") resetBackoff(root)
            true
        } catch (failure: RootUnavailable) {
            sync.enter(root, failure.message ?: "root unavailable during discussion re-parse")
            restoreOnes(root, batch.drop(index))
            false
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (failure: Exception) {
            sync.enter(root, failure.message ?: "discussion re-parse failed")
            restoreOnes(root, batch.drop(index))
            false
        }
    }

    private fun resumeOneDrain(root: RootName) {
        val token = synchronized(lock) {
            drainingOnes.remove(root)
            if (!Thread.currentThread().isInterrupted && canScheduleOneDrainLocked(root)) reserveOneDrainLocked(root) else null
        }
        token?.let { scheduleOneDrain(root, it) }
    }

    private fun canScheduleOneDrainLocked(root: RootName): Boolean {
        if (isClosed()) return false
        if (pendingOnes[root].isNullOrEmpty()) return false
        if (hasRootWorkLocked(root)) return false
        if (root in oneDrainTasks || root in drainingOnes) return false
        if (!isAvailable(root)) return false
        if (sync.isUnsynced(root)) return false
        return true
    }

    private fun reserveOneDrainLocked(root: RootName): Long {
        val token = nextToken.incrementAndGet()
        oneDrainTasks[root] = token
        return token
    }

    private fun hasRootWork(root: RootName): Boolean = synchronized(lock) { hasRootWorkLocked(root) }

    private fun hasRootWorkLocked(root: RootName): Boolean =
        roots[root]?.let { it.pending || it.running } == true

    private fun restoreOnes(root: RootName, remaining: List<Pair<DiscussionId, Long>>) {
        synchronized(lock) {
            if (isClosed()) return
            val rootTask = roots[root]
            if (rootTask?.pending == true || rootTask?.running == true) return
            val pending = pendingOnes.getOrPut(root, ::mutableMapOf)
            remaining.forEach { (id, token) -> pending.putIfAbsent(id, token) }
        }
    }

    private fun schedulePendingOnes(root: RootName) {
        val token = synchronized(lock) {
            if (canScheduleOneDrainLocked(root)) reserveOneDrainLocked(root) else null
        }
        token?.let { scheduleOneDrain(root, it) }
    }

    private fun runChecks(root: RootName, token: Long) {
        var runRoot = false
        val dueIds = mutableListOf<DiscussionId>()
        val nextSchedule = synchronized(lock) {
            if (isClosed() || checkTasks[root] != token) return
            checkTasks.remove(root)
            val now = System.nanoTime()
            val rootDue = pendingRootChecks[root]
            if (rootDue != null) {
                if (isDue(rootDue, now)) {
                    pendingRootChecks.remove(root)
                    runRoot = true
                }
            } else {
                val pending = pendingChecks[root]
                dueIds += pending?.filterValues { isDue(it, now) }?.keys.orEmpty()
                dueIds.forEach { pending?.remove(it) }
                if (pending.isNullOrEmpty()) pendingChecks.remove(root)
            }
            armCheckLocked(root)
        }
        nextSchedule?.let { (nextToken, delayMillis) -> scheduleCheck(root, nextToken, delayMillis) }
        if (runRoot) enqueueRoot(root) else dueIds.forEach { id -> runOneDirect(root, id) }
    }

    private fun armCheckLocked(root: RootName): Pair<Long, Long>? {
        if (root in checkTasks) return null
        val dueAt = pendingRootChecks[root] ?: pendingChecks[root]?.values?.minOrNull() ?: return null
        val token = nextToken.incrementAndGet()
        checkTasks[root] = token
        val remaining = (dueAt - System.nanoTime()).coerceAtLeast(0)
        val delayMillis = if (remaining == 0L) 0L else 1L + (remaining - 1L) / NANOS_PER_MILLI
        return token to delayMillis
    }

    private fun scheduleCheck(root: RootName, token: Long, delayMillis: Long) {
        try {
            alarm.after(delayMillis) { runChecks(root, token) }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (checkTasks[root] == token) checkTasks.remove(root)
            }
            if (failure is Error) throw failure
            logger.error(failure) { "could not schedule discussion re-parse check" }
        }
    }

    private fun isDue(dueAt: Long, now: Long): Boolean = dueAt - now <= 0

    private fun runOneDirect(root: RootName, id: DiscussionId) {
        if (!isAvailable(root)) return
        try {
            val result = reparser.settle(root, id)
            if (result is ReparseOutcome.Applied && result.state != "failed") resetBackoff(root)
        } catch (failure: RootUnavailable) {
            sync.enter(root, failure.message ?: "root unavailable during discussion re-parse")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            sync.enter(root, failure.message ?: "discussion re-parse failed")
        }
    }

    private fun runRoot(root: RootName, token: Long) {
        synchronized(lock) {
            if (isClosed()) return
            val state = roots[root] ?: return
            if (!state.pending || state.token != token) return
            state.pending = false
            pendingOnes.remove(root)
            oneDrainTasks.remove(root)
            if (state.running) {
                state.dirty = true
                return
            }
            state.running = true
        }
        try {
            if (isAvailable(root)) {
                when (val result = reparser.reparseRoot(root, strict = false)) {
                    is ReparseRootResult.Complete -> if (result.applied) resetBackoff(root)
                    is ReparseRootResult.Failed -> sync.enter(root, result.cause)
                }
            }
        } catch (failure: RootUnavailable) {
            sync.enter(root, failure.message ?: "root unavailable during discussion re-parse")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            sync.enter(root, failure.message ?: "discussion collection re-parse failed")
        } finally {
            val rerun = synchronized(lock) {
                val state = roots.getValue(root)
                state.running = false
                if (!isClosed() && !Thread.currentThread().isInterrupted && state.dirty) {
                    state.dirty = false
                    state.pending = true
                    state.token = nextToken.incrementAndGet()
                    state.token
                } else {
                    null
                }
            }
            rerun?.let { next -> scheduleRoot(root, next) }
        }
    }

    private fun scheduleRecovery(root: RootName) {
        val delay: Long
        val token: Long
        synchronized(lock) {
            if (isClosed() || !isAvailable(root)) return
            val slot = recovery[root] ?: return
            if (slot.state != RecoveryState.IDLE) return
            slot.state = RecoveryState.SCHEDULING
            slot.firedWhileScheduling = false
            delay = recoveryDelay(slot.failures)
            token = nextToken.incrementAndGet()
            slot.token = token
        }
        var scheduled = false
        var schedulingFailure: Throwable? = null
        try {
            alarm.after(delay) { runRecovery(root, token) }
            scheduled = true
        } catch (failure: Throwable) {
            schedulingFailure = failure
            logger.error(failure) { "could not schedule discussion recovery" }
        }
        val runNow = synchronized(lock) {
            val slot = recovery[root] ?: return
            if (slot.token != token || slot.state != RecoveryState.SCHEDULING) return
            if (!scheduled || isClosed()) {
                slot.state = RecoveryState.IDLE
                slot.firedWhileScheduling = false
                false
            } else {
                slot.state = RecoveryState.PENDING
                slot.firedWhileScheduling.also { slot.firedWhileScheduling = false }
            }
        }
        if (schedulingFailure is Error) throw schedulingFailure
        if (runNow) postRecovery(root, token)
    }

    private fun postRecovery(root: RootName, token: Long) {
        try {
            alarm.after(0) { runRecovery(root, token) }
        } catch (failure: Throwable) {
            synchronized(lock) {
                val slot = recovery[root]
                if (slot?.token == token && slot.state == RecoveryState.PENDING) slot.state = RecoveryState.IDLE
            }
            if (failure is Error) throw failure
            logger.error(failure) { "could not re-post discussion recovery" }
        }
    }

    private fun runRecovery(root: RootName, token: Long) {
        synchronized(lock) {
            if (isClosed()) return
            val slot = recovery[root] ?: return
            if (slot.token != token) return
            if (slot.state == RecoveryState.SCHEDULING) {
                slot.firedWhileScheduling = true
                return
            }
            if (slot.state != RecoveryState.PENDING) return
            slot.state = RecoveryState.RUNNING
        }
        var cleared = false
        var cancelled = false
        try {
            if (isAvailable(root)) {
                val state = sync.current(root)
                if (state is RootSync.Unsynced) {
                    when (val result = reparser.reparseRoot(root, strict = true)) {
                        is ReparseRootResult.Complete -> {
                            beforeClear(root)
                            cleared = reparser.withRowsWrite { sync.clearIf(root, state.generation) }
                            if (cleared) {
                                afterClear(root)
                                schedulePendingOnes(root)
                            }
                        }
                        is ReparseRootResult.Failed -> Unit
                    }
                }
            }
        } catch (failure: RootUnavailable) {
            sync.enter(root, failure.message ?: "root unavailable during discussion recovery")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelled = true
        } catch (failure: Exception) {
            sync.enter(root, failure.message ?: "discussion recovery failed")
        }
        if (finishRecovery(root, cleared, cancelled)) scheduleRecovery(root)
    }

    private fun finishRecovery(root: RootName, cleared: Boolean, cancelled: Boolean): Boolean = synchronized(lock) {
        val slot = recovery.getValue(root)
        slot.state = RecoveryState.IDLE
        val state = sync.current(root)
        if (state is RootSync.Synced || cleared) slot.failures = 0 else slot.failures++
        !cancelled && !isClosed() && isAvailable(root) && state is RootSync.Unsynced
    }

    private fun resetBackoff(root: RootName) {
        synchronized(lock) { recovery[root]?.failures = 0 }
    }

    private fun recoveryDelay(failures: Int): Long {
        var delay = recoveryBaseMillis.coerceAtLeast(1)
        repeat(failures.coerceAtMost(30)) { delay = (delay * 2).coerceAtMost(recoveryMaxMillis) }
        return delay.coerceAtMost(recoveryMaxMillis)
    }

    private fun isAvailable(root: RootName): Boolean = availability.current().isAvailable(root)

    private fun requireRoot(root: RootName) {
        sync.current(root)
    }

    private fun isClosed(): Boolean = closed.get() != 0L

    private class RootTaskState {
        var pending = false
        var running = false
        var dirty = false
        var token = 0L
    }

    private class RecoverySlot {
        var state = RecoveryState.IDLE
        var failures = 0
        var token = 0L
        var firedWhileScheduling = false
    }

    private enum class RecoveryState { IDLE, SCHEDULING, PENDING, RUNNING }

    companion object {
        const val DEFAULT_CHECK_DELAY_MILLIS = 3_000L
        const val DEFAULT_RECOVERY_BASE_MILLIS = 1_000L
        const val DEFAULT_RECOVERY_MAX_MILLIS = 300_000L
        const val MAX_PENDING_ONES = 1_024
        private const val NANOS_PER_MILLI = 1_000_000L
        private val logger = KotlinLogging.logger {}
    }
}
