package com.plainbase.domain.root

/**
 * **WHERE a root's content actually lives** - the local path, or the `endpoint|bucket|prefix` of its bucket. ONE
 * mechanism, both backends (C3), because the hole it closes is the same hole on both.
 *
 * A root's NAME is not its identity: `handbook` can point at `/srv/handbook` today and at `/tmp/decoy` tomorrow, and
 * the durable rows of the first would silently attach to the second. That is the bind-mount attack arriving through
 * the CONFIG FILE rather than the mount table, and its object-mode twin is the one that eats corpora: a wrong bucket
 * name, a wrong prefix, or credentials scoped to a different EMPTY bucket produce a **successful empty LIST** - a
 * readable, complete, entirely authoritative view of the WRONG UNIVERSE.
 *
 * So the binding is recorded, and a CHANGE to it is treated as what it is: a claim that this root is now somewhere
 * else, which nothing has yet verified. See [BindingStatus] and [BindingLatch].
 */
@JvmInline
value class RootBinding(val value: String)

/** Do we know that the tree behind a root's [RootBinding] is the one its durable rows describe? */
enum class BindingStatus {

    /**
     * **A binding nothing has verified**: first sight, or one that CHANGED. Its rows are not deleted, not displaced,
     * and not believed - the root serves what it can and proves nothing, across polls AND restarts (which is why this
     * is a DURABLE status and not a process flag: an earlier draft persisted only the current binding, so poll 1
     * limboed the rows while recording the binding, and poll 2 saw the binding as "unchanged" and reaped the corpus).
     */
    UNRESOLVED,

    /**
     * The tree behind this binding has been WITNESSED BY IDENTITY to be the one the durable rows describe (or an
     * operator said so). Only now may a complete LIST of it prove a page gone.
     */
    TRUSTED,
}

/**
 * The at-risk snapshot: the bindings that existed **at the moment a root's binding last changed** - the set a new
 * binding must witness before anything it says about absence is worth anything.
 *
 * Snapshotting it AT THE CHANGE is what makes it unfakeable. Evaluating "every pre-existing row" LIVE would be
 * vacuously true on an empty root, so a wrong binding could trust itself against an empty universe; and no amount of
 * content CREATED AFTERWARDS can satisfy a snapshot taken before it existed.
 */
sealed interface AtRisk {

    /**
     * Exactly these bindings were at risk. An **EMPTY** one is trivially satisfied, and that is SAFE rather than a
     * loophole: a proof authorizes only RETIREMENTS, and there is nothing to retire. A wrong binding on a root with
     * no durable rows produces an EMPTY site, not a LOST one - the real corpus sits untouched in the bucket the
     * config no longer names.
     */
    data class Bindings(val refs: Set<BindingRef>) : AtRisk

    /**
     * The snapshot did not decode. It is not empty and it is not knowable, so **nothing can satisfy it**: the root
     * stays [BindingStatus.UNRESOLVED] and reaps nothing until an operator reconciles. Corruption fails CLOSED, like
     * everything else here.
     */
    data object Unreadable : AtRisk
}

/** One root's durable latch row: where it points, whether that is believed, and what a rebind put at risk. */
data class RootTopology(
    val root: RootName,
    val binding: RootBinding,
    val status: BindingStatus,
    val atRisk: AtRisk,
)
