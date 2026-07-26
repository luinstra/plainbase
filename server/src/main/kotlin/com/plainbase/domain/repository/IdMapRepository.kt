package com.plainbase.domain.repository

import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RetiredBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * Persistence port for the page-identity map, its TOMBSTONES, and the identity issues list (§5.2, chunk 4b;
 * (root, path)-keyed since multi-root C2, ADR-0011; retirement since the C0 absence-authority floor).
 *
 * The id_map binds each rooted content path to its [PageId] plus the materialization state — whether the id
 * also lives in the file's frontmatter. Pre-materialization identity is path-keyed by accepted design (§5.2):
 * durability is only promised for materialized ids. That sentence is exactly why `retired_binding` exists: for
 * an UNMATERIALIZED page the id_map row is the SOLE record that a path ever had that id, so a hard delete
 * takes the permalink with it and every agent citation to it dies.
 *
 * **[bind] no longer supersedes on trust (C0).** It is still key-complete - one binding per (root, path) AND
 * per (root, id), so the adapter enforces PER-ROOT id-uniqueness structurally (post-flip `UNIQUE(id, root)`, C5:
 * the same id in two roots is legal) - but removing ANOTHER (root, path)'s row is a
 * NEGATIVE CLAIM about that page ("it no longer holds this id"), and a negative claim needs authority like
 * any other. The caller states what it may displace ([Supersession]); an incumbent outside that authority is
 * REFUSED ([BindOutcome.Refused]) rather than swept, and the claimant mints a fresh id instead. A copied
 * `id:` in an unwitnessed root's file therefore steals nothing.
 *
 * The three legal retirements, and only the third is an absence claim:
 *  - **DISPLACEMENT** ([bind]): the file at (root, path) now carries a DIFFERENT id. The displaced id is
 *    tombstoned in the SAME transaction as the new bind - `/p/{root}/{oldId}` stays a 410, never a 404.
 *  - **CONTEST** (WITHIN one root; per-root identity dissolved the cross-root transfer, ADR-0012): two paths under
 *    the same root claim one id. §5.2 decides it - the previously-bound path keeps the id and the other reassigns -
 *    and registry rank plays NO part, because every page in a root shares that root's rank. First-detection order
 *    matters only when neither path is bound yet, and it differs by caller: the index pass sorts frontmatter-first
 *    then by path, `AdoptionPass` takes plain path order. The id moves to the winner and stays LIVE. Nothing is
 *    tombstoned, because nothing died - the permalink follows the id.
 *  - **ABSENCE**: needs an `AbsenceProof`, and is applied nowhere but the one proof-apply transaction
 *    ([AbsenceReaper]). In C0 no production code mints a proof, so nothing is ever reaped by inference.
 *
 * Pure domain port: only chunk 1.5/4a/C1 domain types appear; the at-rest representation (16-byte
 * BLOBs, decision log #6) is invisible here — that is the storage adapter's single concern.
 */
interface IdMapRepository {

    /** The binding for [path], or null when the rooted path is unmapped. */
    fun find(path: RootedPath): IdBinding?

    /**
     * Every root holding a LIVE binding for [id] - the Option B bare-id resolver's durable claimant list. Post-flip
     * `UNIQUE(id, root)` legalizes the same id in several roots, so this is 0..N names, one per root holding it live;
     * the One/Ambiguous/None contract classifies the count.
     */
    fun rootsHoldingId(id: PageId): List<RootName>

    /** Every root holding a TOMBSTONE for [id] (C4) - the retired-claimant list behind the permalink 410 arm. */
    fun retiredRootsHoldingId(id: PageId): List<RootName>

    /** The tombstone at the full ([root], [id]) key, or null - the resolved retirement's last-known path (C4). */
    fun retiredAt(root: RootName, id: PageId): RetiredBinding?

    /** The binding for [id] WITHIN [root], or null - the root-scoped `ownerOf` seam (C5). */
    fun bindingInRoot(root: RootName, id: PageId): IdBinding?

    /** Every tombstone, for reporting and tests. */
    fun retiredBindings(): List<RetiredBinding>

    /** One durable snapshot of every claim on [id]: who holds it LIVE, and who holds a TOMBSTONE (with its path). */
    fun claimantState(id: PageId): ClaimantState

    /**
     * Binds [path] to [id] under the stated [supersession] authority, tombstoning whatever id this key held
     * before (the DISPLACEMENT rule), and un-retiring [id] if this very page has come home to it.
     *
     * Refuses - writing NOTHING - when [id] is not this page's to take: another LIVE binding holds it and
     * [supersession] does not authorize displacing that binding, or a TOMBSTONE reserves it for a different
     * page. Both refusals mean the same thing: taking an id from a page that is not here to defend it is a
     * negative claim, and we have no authority for it. The caller mints fresh and records the issue.
     */
    fun bind(
        path: RootedPath,
        id: PageId,
        materialized: Boolean,
        supersession: Supersession = Supersession.NONE,
    ): BindOutcome

    /** Marks [path]'s binding materialized — called after the patched file write lands (§5.2). */
    fun markMaterialized(path: RootedPath)

    /** Every binding, for reporting and tests. */
    fun bindings(): List<IdBinding>

    /** Every root name holding at least one binding - the boot detached-root guard + C4 health seam. */
    fun roots(): Set<RootName>

    /** Records [issue] for the admin issues list. Recording the same issue again is a no-op. */
    fun record(issue: IdentityIssue)

    /** Every recorded issue. */
    fun issues(): List<IdentityIssue>
}

/** One id_map binding: the page at [path] is [id]; [materialized] iff the id also lives in the file. */
data class IdBinding(
    val path: RootedPath,
    val id: PageId,
    val materialized: Boolean,
)

/** One durable snapshot of every claim on an id: the roots holding it [live], and the [retired] tombstones (C5). */
data class ClaimantState(
    val live: List<RootName>,
    val retired: List<RetiredBinding>,
)

/**
 * What a pass is ALLOWED to displace - the C0 gate on the key-complete bind, consulted BOTH by the identity
 * resolver ([com.plainbase.domain.service.BindingVisibility]) and inside the bind transaction itself, so the
 * two can never disagree about who owns what.
 *
 * Taking an id from a binding is a NEGATIVE CLAIM about that page ("it no longer holds this id"), and a
 * negative claim needs authority like any other. There are exactly three grounds, and every one of them is
 * POSITIVE evidence:
 *
 *  1. **WITNESSED.** We READ the file at the incumbent's (root, path) this pass. Whatever it carries now, we
 *     are looking at it - the page is not in doubt, so the contest may be settled against it. This is what lets a
 *     WITHIN-root contest resolve at all (root-scoped since the cross-root transfer dissolved, ADR-0012): every
 *     page in it is witnessed.
 *
 *  2. **PROVEN.** An [com.plainbase.domain.root.AbsenceProof] covers the binding - a commit range, an unbroken
 *     observation epoch, a complete bucket LIST, an operator. In C0 there are no proof sources, so [proven] is
 *     always empty. It is threaded here anyway because the alternative is a later chunk bolting authority on
 *     from the side, which is how this bug class started.
 *
 *  3. **MATERIALIZED, and unwitnessed.** The incumbent's root was scanned and its path was NOT found, and its
 *     id LIVES IN THE FILE. That id is free to travel: a file turning up elsewhere carrying it may well be
 *     this very page, MOVED. A move and a copy are observationally IDENTICAL (design §10, which rules the
 *     distinction an identity-RANK problem and puts it firmly out of scope), so C0 does not guess and does not
 *     change what already ships - the §A4/§B3 move keeps its permalink.
 *
 * **And the case this class exists for: an UNWITNESSED, UNMATERIALIZED incumbent may NEVER be displaced.**
 * `materialized = false` means the id was never in the file - it lives ONLY in that `id_map` row (see this
 * file's header: *"durability is only promised for materialized ids"*). So a file appearing elsewhere WITH
 * that id in its frontmatter **cannot be this page moved**; the id could not have travelled in bytes that
 * never held it. It is a copy, a paste or a restore. And the row it is reaching for is the SOLE record that
 * this path ever had that id: hard-delete it and the page comes back with a fresh id, its permalink dead
 * forever and no trace anywhere of what it used to be. That is the one loss nothing can undo, and it is the
 * one this gate refuses. The claimant mints fresh, an issue names both sides, and the unobservable incumbent
 * keeps its permalink.
 */
class Supersession(
    private val witnessed: Set<RootedPath>,
    private val scannedRoots: Set<RootName>,
    private val registeredRoots: Set<RootName>,
    private val proven: Set<BindingRef> = emptySet(),
) {

    /** May this pass take [incumbent]'s id away from it? */
    fun mayDisplace(incumbent: IdBinding): Boolean = when {
        incumbent.path in witnessed -> true
        BindingRef(incumbent.path.path, incumbent.id) in proven -> true
        // DETACHED (D2): a binding under a root no longer in `roots {}` is not an OWNER at all, so there is no
        // negative claim here to authorize - the operator already made it, deliberately and backup-first, by
        // removing the root. It stays sweepable, and it must: the resolver does not treat it as an owner either,
        // so refusing here would mean the two disagree and a live claimant would be denied an id nobody holds.
        // (Guarded on a KNOWN registry: a [NONE] that has never been told what is registered cannot conclude
        // that anything is detached, and "I do not know" is never a licence in this design.)
        registeredRoots.isNotEmpty() && incumbent.path.root !in registeredRoots -> true
        // A root the pass could not LOOK AT is untouchable however the two sides rank (D16/D-C4-10): rank cannot
        // settle a contest one side did not turn up to.
        incumbent.path.root !in scannedRoots -> false
        else -> incumbent.materialized
    }

    companion object {
        /**
         * Displaces nothing - the default, and deliberately so: a bind that has not stated its authority does
         * not get to make a negative claim about somebody else's page. The create path (a freshly minted id
         * nobody can be holding) binds under it without ever noticing it is there.
         */
        val NONE = Supersession(witnessed = emptySet(), scannedRoots = emptySet(), registeredRoots = emptySet())
    }
}

/** What [IdMapRepository.bind] did. A [Refused] is not an error to swallow: the id was NOT this page's to take. */
sealed interface BindOutcome {

    /** The binding landed (and any displaced id was tombstoned, and any tombstone on this id was reclaimed). */
    data object Bound : BindOutcome

    /**
     * Nothing was written. [id] belongs to [heldBy] - a LIVE binding this pass has no authority to displace, or
     * a RETIRED page that is not this one - so the claimant must mint a fresh id and record the contest.
     */
    data class Refused(val id: PageId, val heldBy: RootedPath, val retired: Boolean) : BindOutcome
}
