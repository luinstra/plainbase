@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.root

import com.plainbase.domain.page.PageId
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The durable rows this pass could NOT account for: `limbo(root) = durableRows(root) - witnessed - retired`.
 *
 * A row whose page the pass did not witness, and which no [AbsenceProof] covers, is neither present nor
 * deleted - it is UNKNOWN, and the honest answer to a read for it is "come back later", never "it's gone".
 * Per ROW, never per root: today's tripwire 503s a whole root including the pages it is holding in its hand.
 *
 * **Limbo is DERIVED, never stored.** A persisted limbo flag would be another snapshot from T used at T+n -
 * the exact class of fact this redesign exists to abolish - and it would need its own invalidation. Deriving
 * it every pass makes **"limbo self-heals on reappearance"** true BY CONSTRUCTION: the page comes back, it
 * lands in `witnessed`, it drops out of the next derivation, and no code runs.
 *
 * Same immutable-snapshot publication as [RootConvergence] / [RootAvailability] (an [AtomicReference], no
 * `@Volatile`, no j.u.c). It is a WHOLE-VALUE swap rather than a CAS loop because limbo is re-derived
 * wholesale by the serialized rebuild - there is one writer and nothing to merge, so a compare-and-set would
 * be ceremony around a fact that is already atomic. Readers stay lock-free and always see a complete,
 * internally consistent set.
 */
class RootLimbo {

    private val holder = AtomicReference<Map<RootName, Set<BindingRef>>>(emptyMap())

    /** The current limbo set per root - a complete, consistent snapshot (empty when every row was accounted for). */
    fun current(): Map<RootName, Set<BindingRef>> = holder.load()

    /** Whether [root]'s [id] is in limbo: the durable row is there, the page is not, and nothing proved it gone. */
    fun holds(root: RootName, id: PageId): Boolean = current()[root]?.any { it.id == id } == true

    /** How many of [root]'s rows this pass could not account for (the `/healthz` per-root figure). */
    fun count(root: RootName): Int = current()[root]?.size ?: 0

    /** Publishes the freshly DERIVED limbo set. Roots with nothing in limbo are absent from [limbo], never empty entries. */
    fun publish(limbo: Map<RootName, Set<BindingRef>>) {
        holder.store(limbo.filterValues { it.isNotEmpty() })
    }
}
