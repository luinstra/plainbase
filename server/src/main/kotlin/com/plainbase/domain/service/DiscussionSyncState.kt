package com.plainbase.domain.service

import com.plainbase.domain.root.RootName
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicReference

sealed interface RootSync {
    val generation: Long

    data class Synced(override val generation: Long) : RootSync

    data class Unsynced(override val generation: Long, val cause: String) : RootSync
}

class DiscussionSyncState(scopeRoots: Collection<RootName>) {
    private val logger = KotlinLogging.logger {}
    private val states = scopeRoots.associateWith { AtomicReference<RootSync>(RootSync.Synced(0)) }
    val scopeRoots: Set<RootName> get() = states.keys
    private val schedule = AtomicReference<((RootName) -> Unit)?>(null)

    fun attach(schedule: (RootName) -> Unit) {
        this.schedule.set(schedule)
    }

    fun current(root: RootName): RootSync = state(root).get()

    fun isUnsynced(root: RootName): Boolean = current(root) is RootSync.Unsynced

    fun enter(root: RootName, cause: String) {
        val ref = state(root)
        var enteredFromSynced = false
        while (true) {
            val seen = ref.get()
            val next = RootSync.Unsynced(seen.generation + 1, cause)
            if (ref.compareAndSet(seen, next)) {
                enteredFromSynced = seen is RootSync.Synced
                break
            }
        }
        if (enteredFromSynced) logger.error { "discussion index for root '${root.value}' became unsynced: $cause" }
        schedule.get()?.invoke(root)
    }

    fun clearIf(root: RootName, generation: Long): Boolean {
        val ref = state(root)
        while (true) {
            val seen = ref.get()
            if (seen !is RootSync.Unsynced || seen.generation != generation) return false
            if (ref.compareAndSet(seen, RootSync.Synced(generation))) return true
        }
    }

    private fun state(root: RootName): AtomicReference<RootSync> = states[root]
        ?: throw IllegalArgumentException("not a discussion root: ${root.value}")
}
