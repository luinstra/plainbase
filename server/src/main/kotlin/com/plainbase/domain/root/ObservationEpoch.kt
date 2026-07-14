@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.RetirementRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * **The event never carries authority. The unbroken OBSERVATION does, and the scan confirms.** (C2)
 *
 * This is the first source that mints an [AbsenceProof], and therefore the chunk that gives back ONLINE delete
 * convergence: after C0 nothing reaps at all, by design (the safety floor), and this hands back exactly the
 * deletes we can honestly prove.
 *
 * **Why not trust the delete EVENT?** Because an unmount, a rename-flip's `rm -rf site.old` landing on
 * inode-tracked watches, and a watcher fault all produce delete events - and on macOS the JDK `WatchService` is a
 * POLLER, so a "delete event" there is literally a scan-diff, which makes per-event proof circular. The event is
 * only ever a reason to LOOK; what decides is whether the tree we are looking at is one we have been watching
 * WITHOUT A GAP since we last read the page.
 *
 * ```
 * CLOSED --[a COMPLETE scan under WHOLE coverage on an identity-stable tree]--> OPEN
 *         mint a NEW ObservationId; the base witness is that scan's pages; mint NO PROOFS - it has nothing to
 *         compare against, and RETROACTIVE authority IS the decoy hole.
 *
 * OPEN --[another COMPLETE scan, epoch UNBROKEN]--> OPEN            (a CONFIRMATION scan)
 *         KEEP the id. Mint AbsenceProof(EPOCH) for every binding the epoch WITNESSED that this scan did NOT
 *         see; then fold the newly-seen pages into the witness set. *** This is what converges an online delete. ***
 *
 * OPEN --[any BREAK]--> CLOSED
 *         revoke the ObservationId, which invalidates every outstanding proof by the freshness rule. The next
 *         complete scan opens a FRESH epoch whose base is that scan - with NO authority over anything it did
 *         not just witness.
 * ```
 *
 * **The asymmetry is load-bearing: an OPENING scan proves nothing; only a CONFIRMATION scan can.** An epoch must
 * have SEEN a page before it may say the page is gone. That one rule is what stops a re-registration on a bare
 * mountpoint from reaping a corpus it never saw, and it is why [Open.witnessed] is a set rather than a flag.
 *
 * **Scoping, equally load-bearing:** an epoch's proofs cover ONLY the pages in its witness set. A durable row
 * absent from the base scan stays in LIMBO ([RootLimbo]) no matter how healthy the epoch is.
 *
 * **The DURABLE token is the truth; this holder is a cache that is only valid while it still holds it.** A break
 * arrives on a watcher thread and revokes the root's [ObservationId] in the app DB; this map is then updated
 * best-effort. So every read of an epoch re-checks its token against `root_observation` ([liveEpoch]) rather than
 * trusting the map, which means a break that raced a pass cannot leave a live-looking epoch behind: the token has
 * moved, so the epoch is dead, whatever the map says. That is the same argument the proof-apply transaction rests
 * on, applied one layer earlier - and it is what lets the holder stay lock-free with no lost-update hazard that
 * matters.
 */
class ObservationEpoch(
    private val retirements: RetirementRepository,
    /** Watch coverage, read (never written) here: an epoch may not OPEN on a tree whose watcher cannot see all of it. */
    private val convergence: RootConvergence,
) {

    /** One root's observation. */
    sealed interface Epoch {

        /**
         * **Nobody is watching.** No epoch can be earned here at all, and this is the state a root starts in - so
         * the authority is opt-IN, from the ONE place that can honestly grant it ([observing], called where the
         * watcher is installed). Without this, a pass that scans a root nothing is watching would happily "confirm"
         * a delete: two scans with an `rm` between them look exactly like two scans with an unmounted submount
         * between them, and it is the WATCHER - not the scan - that tells those apart.
         *
         * A watcher that DIES returns the root here rather than merely closing its epoch, which is also what C5's
         * recoverable availability will need: *recovery restores READS ONLY; it is not an epoch and mints no
         * absence authority.*
         */
        data object Unobserved : Epoch

        /** Watched, but with no unbroken observation to stand on: nothing this root fails to show us is evidence yet. */
        data object Closed : Epoch

        /** An unbroken observation since [observationId] was minted, having WITNESSED exactly [witnessed]. */
        data class Open(val observationId: ObservationId, val witnessed: Set<TreePath>) : Epoch
    }

    private val holder = AtomicReference<Map<RootName, Epoch>>(emptyMap())

    /** Whether [root] currently holds an unbroken observation (health/reporting; the proofs come from [scanned]). */
    fun isOpen(root: RootName): Boolean = liveEpoch(root) != null

    /**
     * **[root] is now under continuous, break-reporting observation** - called from the one place that can say so:
     * where its watcher is installed. It grants only the RIGHT TO EARN an epoch, never an epoch: the next complete
     * scan opens one, and that scan still proves nothing.
     */
    fun observing(root: RootName) {
        holder.store(holder.load() + (root to Epoch.Closed))
        retirements.revoke(root) // whatever a previous watcher's epoch could say, this one has not seen it
    }

    /**
     * A BREAK: the observation now has a HOLE in it, so everything it could say about absence is worthless.
     *
     * The revoke is what actually does the work - it invalidates every outstanding proof by the freshness rule,
     * including one a pass minted moments ago and has not yet applied - and the map update merely stops the next
     * pass from having to discover the same thing. Both are safe to run twice.
     *
     * A [BreakCause.blinding] one goes further and takes the root back to [Epoch.Unobserved]: a dead watcher and a
     * lost root do not leave a tree we are merely out of date about, they leave a tree nobody is watching, and the
     * next complete scan of it must not be allowed to open an epoch on the strength of a watcher that is gone.
     */
    fun broke(root: RootName, cause: BreakCause) {
        val revoked = retirements.revoke(root)
        holder.store(holder.load() + (root to if (cause.blinding) Epoch.Unobserved else Epoch.Closed))
        logger.warn {
            "the observation epoch for root '$root' BROKE ($cause): it can no longer prove any page gone. Its rows " +
                "stay in limbo until a fresh epoch witnesses them (observation ${revoked.value})"
        }
    }

    /**
     * One root's COMPLETE scan, [witnessed] being the pages it read. Returns the `EPOCH` proof it earned, which is
     * null on the opening scan of an epoch - by construction, not by omission.
     *
     * [durable] is the root's id_map rows as they stood BEFORE this pass touched them: what the proof is ABOUT is
     * a binding, and the binding is the durable fact. A row whose path this epoch never witnessed is not covered
     * however long the epoch has been open (the scoping rule).
     */
    fun scanned(root: RootName, witnessed: Set<TreePath>, durable: Set<BindingRef>): AbsenceProof? {
        val state = holder.load()[root] ?: Epoch.Unobserved
        if (state == Epoch.Unobserved) return null // nobody is watching: a scan-diff is not an observation
        val epoch = liveEpoch(root)
        if (!convergence.isWhole(root)) {
            // A tree with an unwatched subtree in it is not an observation, it is a sample: an edit under that
            // subtree raises NO event at all. The LOSS of coverage already broke the epoch (the watcher reports the
            // transition); this is the REOPEN guard, because coverage that STAYS partial reports nothing further -
            // and it is the belt for a live epoch that somehow outlived the transition.
            if (epoch != null) broke(root, BreakCause.COVERAGE_LOST)
            return null
        }
        if (epoch == null) return open(root, witnessed)
        val gone = durable.filterTo(mutableSetOf()) { it.path in epoch.witnessed && it.path !in witnessed }
        holder.store(holder.load() + (root to epoch.copy(witnessed = epoch.witnessed + witnessed)))
        if (gone.isEmpty()) return null
        logger.info {
            "the observation epoch for root '$root' witnessed ${gone.size} page(s) that this scan does not see: it has " +
                "watched this tree without a gap since it read them, so they are DELETED - ${gone.joinToString { it.path.value }}"
        }
        return AbsenceProof(root = root, source = ProofSource.EPOCH, observationId = epoch.observationId, covers = gone)
    }

    /**
     * Opens a fresh epoch over [witnessed], and mints NOTHING. The new [ObservationId] is what makes the break that
     * closed the last one final: a proof minted under the old token cannot be cashed against the new one.
     */
    private fun open(root: RootName, witnessed: Set<TreePath>): AbsenceProof? {
        val observationId = retirements.revoke(root)
        holder.store(holder.load() + (root to Epoch.Open(observationId, witnessed)))
        logger.info {
            "opened an observation epoch for root '$root' (observation ${observationId.value}) over the ${witnessed.size} " +
                "page(s) it just read. It proves NOTHING yet: an epoch must witness a page before it may say the page is gone"
        }
        return null
    }

    /**
     * [root]'s epoch, or null when there is none TO TRUST - which is not the same question as "what does the map
     * say". A break revokes the durable token from another thread and updates the map afterwards, so an epoch
     * whose token has moved on is already dead however the map races: the token, not this holder, is the fact.
     */
    private fun liveEpoch(root: RootName): Epoch.Open? =
        (holder.load()[root] as? Epoch.Open)?.takeIf { retirements.observation(root) == it.observationId }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * A hole in an observation - the ONE thing a watcher reports besides the change itself (`ContentStore.watch`'s
 * `onBreak`), and the reason its `onChange` stays deliberately uninterpreted: an event kind is not evidence, and a
 * seam built to carry paths must not be talked into carrying authority.
 *
 * Every one of these revokes WHOLESALE. There is no partial break, because a gap in an observation is not scoped
 * to the paths we happen to have noticed it on - that is the whole point of it being a gap.
 */
enum class BreakCause(
    /**
     * Does this cause leave the root UNWATCHED, rather than merely out of date? A dead watcher and a lost root do -
     * nobody is observing that tree any more - so they must not leave behind a state in which the next complete scan
     * can open a fresh epoch and start reaping on the strength of a watcher that no longer exists.
     */
    internal val blinding: Boolean = false,
) {
    /** The event queue overflowed: the JDK dropped an unknown set of events, so the observation has an unknown hole. */
    OVERFLOW,

    /** A subtree stopped being watchable (the inotify watch limit, a `chmod 000` directory): its edits raise NO event. */
    COVERAGE_LOST,

    /**
     * A watch key died while its directory is still THERE - a rename-flip, a remount, an unmounted submount. An
     * ordinary `rm -rf subdir` cancels its key too, but it delivers every child's delete FIRST and leaves no
     * directory behind, so it is observed rather than a gap.
     */
    WATCH_KEY_CANCELLED,

    /** The tree at the root's path was REPLACED (a deploy rename-in, a fresh clone): everything witnessed was witnessed
     *  against the OLD inodes, and inodes are what the watches are tracking. */
    IDENTITY_REBIND,

    /** The root itself went away. Whatever happened under it while it was gone, we did not see it - and we stop watching. */
    ROOT_LOST(blinding = true),

    /** The watch worker died: this tree stops converging on events entirely, so nothing it fails to show us means anything. */
    WATCHER_DIED(blinding = true),

    /** A pass could not scan the root, or could not see all of it. A view with holes in it is not an observation. */
    SCAN_FAILED,
}
