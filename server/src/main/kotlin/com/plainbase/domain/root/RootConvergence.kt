@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.root

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Which roots' watchers cannot see their WHOLE tree right now - the CONVERGENCE holder, and deliberately not a
 * second [RootAvailability].
 *
 * "I cannot watch all of this tree" is not "this root is gone". The inotify watch limit is a host-wide kernel
 * resource and a `chmod 000` subdirectory is fixed in place; both leave a root that exists, reads correctly and
 * serves every byte it is asked for. Answering them with availability - which is sticky until a restart that
 * would only re-register, re-fail and re-mark - would turn a slow-convergence condition into a permanent,
 * restart-proof 503 for a healthy root. So this holder is the OTHER half of the pair: NOT sticky, NOT a 503, and
 * it flips back the moment a retry re-registers the tree (the watcher retries in place, and schedules a full
 * converging pass meanwhile, so an edit under an unwatched subtree lands late rather than never).
 *
 * Same immutable-snapshot publication as [RootAvailability] (CAS'd [AtomicReference], no `@Volatile`, no j.u.c):
 * one holder, whole snapshots, lock-free readers.
 */
class RootConvergence {

    private val holder = AtomicReference<Set<RootName>>(emptySet())

    /** The roots whose watch coverage is currently PARTIAL - a complete, consistent snapshot. */
    fun degraded(): Set<RootName> = holder.load()

    /** Whether [root]'s tree is fully watched (the health wire's per-root flag). */
    fun isWhole(root: RootName): Boolean = root !in holder.load()

    /** Records [root]'s current coverage, in either direction - idempotent, and never sticky. */
    fun record(root: RootName, whole: Boolean) {
        while (true) {
            val current = holder.load()
            val next = if (whole) current - root else current + root
            if (next == current) return
            if (holder.compareAndSet(expectedValue = current, newValue = next)) return
        }
    }
}
