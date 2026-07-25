@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.root

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * WHY a root is not serving (ADR-0011 D5) - a FIXED, enumerable vocabulary, because it reaches the
 * UNAUTHENTICATED health wire. Free-text detail (paths, exception messages) belongs in the LOG at the
 * mark site, never here.
 *
 * Three causes are HOLDER-recorded ([MISSING_AT_BOOT], [VANISHED], [WATCHER_FAILED]); [DETACHED] is
 * REGISTRY-derived and is never stored in [RootAvailability], whose invariant is that only REGISTERED
 * roots ever get an entry. A detached root has no store to probe and no availability status to consult -
 * it is recognized by its absence from the registry ([com.plainbase.domain.service.PageRootResolver
 * .statusOf]) and carried on the wire through this same vocabulary, because the operator action differs:
 * a vanished root wants its disk back; a detached root wants its name back in `roots {}`.
 */
enum class UnavailableCause {
    /** The root's path was already missing/unreadable when serve() probed it - it was never scanned this process. */
    MISSING_AT_BOOT,

    /** The root's path disappeared (or became unreadable) mid-run - a rebuild probe, or a write's probe, caught it. */
    VANISHED,

    /** The root's file watcher died unexpectedly, so its changes would silently stop converging. */
    WATCHER_FAILED,

    /**
     * The root's tree is THERE and reads as EMPTY, while durable rows say it holds pages - and nothing this
     * process has seen says the corpus was ever there to be deleted (the D5 corpus-loss tripwire,
     * `IndexBuilder`). A volume that is not mounted, a bind mount the container runtime created as an empty
     * directory, a tree not yet restored: the root is not a corpus, so it is refused delete authority and
     * serves 503 rather than 404-ing a corpus that still exists everywhere but here.
     */
    CORPUS_MISSING,

    /** WIRE-ONLY: the root's name is gone from `roots {}` but durable rows still name it (ADR-0011 D2/D15). */
    DETACHED,
}

/**
 * Runtime AVAILABILITY of the configured roots - the mutable companion the immutable [RootRegistry]
 * deliberately excludes (`RootRegistry` KDoc). One holder, one immutable published [Snapshot] behind a
 * CAS'd [AtomicReference] (the `IndexBuilder` house pattern - never `@Volatile`, never j.u.c), so every
 * reader observes a complete, consistent map, old or new, never torn, and stays lock-free.
 *
 * **Monotonic and sticky-until-restart** (the D5 contract): a root is marked at most ONCE - the first
 * writer's [UnavailableCause] wins - and NEVER flips back. A root whose path REAPPEARS mid-run stays
 * Unavailable and is never rescanned until a restart, because a vanished root's scan and identity state
 * cannot be trusted afterwards. Recovery is an operator restart, and the health/503 surfaces say so.
 *
 * Publishing immutable snapshot OBJECTS (rather than mutating a concurrent set) is load-bearing: the
 * tree JSON memo keys on `(pageIndex, availabilitySnapshot)` identity, so a watcher-failure flip that
 * publishes no page snapshot still invalidates the cached `available: true`.
 */
class RootAvailability(private val clock: Clock) {

    /** One root's unavailability: WHY, and SINCE when (the health wire's `reason`; `since` is for the operator). */
    data class Unavailable(val cause: UnavailableCause, val since: Instant)

    /**
     * An immutable availability snapshot. Identity (`===`) is the memo key, so this is a plain class:
     * two equal-valued snapshots are still two publications.
     *
     * [isAvailable] is meaningful ONLY for a REGISTERED root: the holder tracks only roots that were
     * MARKED, and a root the registry never heard of (a DETACHED name read back off a durable row) was
     * never probed, so it has no entry and this answers a vacuous `true`. Every row-sourced root name
     * must therefore go through [com.plainbase.domain.service.PageRootResolver.statusOf], which checks
     * DETACHED first - never through here (ADR-0011 D15).
     */
    class Snapshot(val unavailable: Map<RootName, Unavailable>) {

        fun isAvailable(root: RootName): Boolean = root !in unavailable
    }

    private val holder = AtomicReference(Snapshot(emptyMap()))

    /** The published snapshot - always complete and consistent. */
    fun current(): Snapshot = holder.load()

    /**
     * Marks [root] unavailable with [cause], idempotently: an already-marked root keeps its FIRST cause
     * (monotonic - a VANISHED root whose watcher then dies is still, and only, VANISHED). Called from
     * the serve() gate loop, the rebuild probe, the watcher failure callback, and the store's
     * mark-then-throw guard.
     *
     * **A MARK IS FOREVER, AND SOMETHING ELSE'S SAFETY LEANS ON THAT. Read this before adding a way to CLEAR one.**
     *
     * A mark does not revoke any freshness token - deliberately, because a revoke is a transaction and the loss paths
     * run on request and write threads, where a second BEGIN on the shared driver is a 500. So the reaper compensates
     * by refusing INFERRED proofs for a currently-marked root
     * ([com.plainbase.domain.repository.RetirementRepository.applyProofs]'s `unavailableNow`), read INSIDE the apply
     * transaction. That works only because marks are MONOTONIC and STICKY-until-restart: a late read is guaranteed to
     * see every mark that landed since the pass began gathering evidence.
     *
     * The moment a mark can be CLEARED - which "recoverable availability" is named as a plan in
     * [com.plainbase.domain.root.ObservationEpoch]'s `Unobserved` KDoc - a root that is lost and recovers WITHIN one
     * pass becomes invisible again: the late read sees no mark, a bindless on-disk restore moves no `binding_epoch`, and
     * the mark moved no `observation_id`. That is the exact durable-loss shape the `unavailableNow` gate exists to close,
     * reborn, and it reaps a page whose file is back on disk plus its `dirty_page` recovery row - real user content.
     *
     * So whoever builds recovery must FIRST make availability transitions DURABLE (a per-root event/generation counter
     * the apply compares), not merely re-publishable in this in-memory snapshot. Removing stickiness without that is a
     * silent regression no existing gate can see.
     */
    fun markUnavailable(root: RootName, cause: UnavailableCause) {
        while (true) {
            val current = holder.load()
            if (root in current.unavailable) return // first writer's cause wins; no flip-back
            val next = Snapshot(current.unavailable + (root to Unavailable(cause, clock.now())))
            if (holder.compareAndSet(expectedValue = current, newValue = next)) return
        }
    }
}
