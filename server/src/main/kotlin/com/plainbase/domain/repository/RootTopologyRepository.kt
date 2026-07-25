package com.plainbase.domain.repository

import com.plainbase.domain.root.AtRisk
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootTopology

/**
 * The DURABLE binding latch (`root_topology`): where each root points, whether that is believed, and what a rebind
 * put at risk.
 *
 * **Durable is the whole point.** The wrong-bucket wipe survives a restart, so the thing that stops it has to as
 * well: an in-memory latch would limbo the rows on the first poll, record the binding, and then - one restart or one
 * poll later - see the binding as "unchanged" and reap the corpus. The fix would have rebuilt the bug two polls
 * later, which is exactly what an earlier draft of this design did.
 */
interface RootTopologyRepository {

    /** [root]'s latch row, or null when it has none (never observed) or its at-risk snapshot is undecodable. */
    fun topology(root: RootName): RootTopology?

    /**
     * Records where [root] now points, and returns the row now in force.
     *
     * First sight (`null -> X`) or **ANY change** latches [com.plainbase.domain.root.BindingStatus.UNRESOLVED],
     * snapshots [AtRisk] from the root's durable bindings AT THAT MOMENT, and REVOKES the root's observation token
     * (a rebind invalidates every proof minted against the tree we used to be looking at). An UNCHANGED binding is
     * left exactly as it is - status included, which is what lets a TRUSTED root stay trusted across restarts.
     */
    fun observeBinding(root: RootName, binding: RootBinding): RootTopology

    /** Promotes [root] to TRUSTED. Called ONLY by the latch, and only once its at-risk set is witnessed by identity. */
    fun trust(root: RootName)
}

/**
 * A latch with no durable table behind it: it records nothing, so it can promote nothing, so it grants nothing.
 *
 * The default for the constructions that hold no app DB (the `plainbase root` boot gate, the terse test builders).
 * Not a stub: "no durable latch, no absence authority" is the correct answer for a graph that may not open the
 * database, and this object simply cannot be talked out of it.
 */
object NoTopology : RootTopologyRepository {
    override fun topology(root: RootName): RootTopology? = null
    override fun observeBinding(root: RootName, binding: RootBinding): RootTopology =
        RootTopology(root, binding, BindingStatus.UNRESOLVED, AtRisk.Unreadable)

    override fun trust(root: RootName) = Unit
}
