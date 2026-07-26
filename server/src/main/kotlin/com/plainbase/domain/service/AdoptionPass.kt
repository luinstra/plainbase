package com.plainbase.domain.service

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The adoption pass (§5.2, chunk 4b): scan the content trees, resolve every page's identity through
 * the 4a precedence/duplicate logic ([PageIdentityService]), persist the resulting id_map bindings
 * and issues, and — in [Mode.MATERIALIZE] only — write `id:` lines into accepted pages through the
 * surgical patcher and the ContentStore's atomic write.
 *
 * **TWO PHASES, and the split is the whole design (D19).** [plan] resolves the WHOLE corpus - every
 * configured root - and writes NOTHING: no file, no row. [apply] then materializes exactly that plan.
 * Both of the bugs the split closes were faces of one sequential per-root loop:
 *  - **Identity, and it is HISTORICAL.** The split's identity motivation was a cross-root rank contest,
 *    which per-root identity (ADR-0012) dissolved. Nothing replaced it HERE: `witnessed` holds every
 *    draft (see [plan]), so every on-disk binding is a live owner, `ownerOf` hands the incumbent to every
 *    other draft, and no draft's row is ever key-complete-swept. The id_map-only-loser hazard that
 *    motivates `IndexBuilder`'s resolve-then-bind note is NOT reachable in this pass - it needs
 *    `IndexBuilder`'s `observedId` path-reuse gate, which adopt has no equivalent of. The within-root
 *    hazard that does survive belongs to scan-all-before-resolve, not to this split; [plan] states it.
 *  - **Atomicity.** A root vanishing mid-loop escaped AFTER earlier roots had already been mutated,
 *    leaving a half-adopted corpus. The plan is read-only, so an abort in phase 1 costs nothing, and
 *    [apply] RE-PROBES every root before its first write, so a root lost between the phases aborts
 *    with nothing written rather than half-applied.
 *
 * The plan is also what the DRY RUN prints. [Mode.PREVIEW] renders the very same [Plan] object - down
 * to the patched BYTES - that a [Mode.MATERIALIZE] run would put on disk, so a preview that disagrees
 * with the write it previews is not merely unlikely, it is unconstructible.
 *
 * **Rooted keys, per-root identity (D16, ADR-0012):** adopt covers every configured root or refuses to
 * run at all (the caller's precondition), so its scanned set IS the registry and the D16 middle arm -
 * configured-but-unscanned, hence non-supersedable - is structurally empty. What remains is the rule's
 * other two arms: a scanned root's binding is live iff its path is on disk, and a DETACHED root's
 * binding (a root absent from the registry) is not an owner for any OTHER root's resolution. It is not
 * swept either: the bind is root-scoped, so the detached row SURVIVES untouched and its `/p/{root}/{id}`
 * keeps its meaning if that root is ever re-added. Every contest adopt settles is within ONE root.
 *
 * **Read-only first index (frozen policy):** [Mode.RECORD] performs ZERO ContentStore writes —
 * unidentified pages get id_map rows only. [Mode.PREVIEW] writes nothing at all, file or row: it
 * reports exactly what a MATERIALIZE run would do, including every would-refuse page with its
 * rule-naming reason — the §A3 asymmetric-freeze measurement input.
 *
 * **Materialization (frozen order):** patcher output is written via the ContentStore's atomic
 * compare-and-swap, THEN the binding is marked materialized — an interruption between the two
 * re-resolves cleanly on the next run (the file's own frontmatter id wins by precedence). `Refused`
 * records an [IdentityIssue.PatchRefused] with the patcher's rule-naming message; the page keeps its
 * map identity. A file already carrying a column-0 `id` key that is NOT this page's assigned id (a
 * copied duplicate, or a shape-invalid value) comes back `AlreadyPresent` and is never overwritten —
 * reconciling frontmatter-vs-map is exactly this pass's policy, and the policy is "keep the map
 * identity, surface the issue".
 *
 * **It CANNOT create, and that is a safety property, not an optimization.** The write is
 * [ContentStore.compareAndSwapWrite], never [ContentStore.write]: `write` is a create-or-replace that
 * MAKES MISSING PARENTS, so a root deleted or unmounted between the plan and the write would be
 * RECREATED on disk holding only the pages this run patched - a partial skeleton of the operator's
 * tree, laid down wherever that path now resolves (possibly a different mount entirely), by the run
 * that then reported SUCCESS. CAS cannot do that: it replaces a file it already resolved and creates
 * nothing, so a vanished root aborts as a classified [RootUnavailable] and a vanished PAGE aborts as
 * [PlanStale] instead of being conjured back into existence.
 *
 * The same CAS also refuses to IMPROVISE. Its precondition is the hash of the very bytes the patch was
 * computed from ([PlannedWrite.baseHash]), so a page edited under a plan that was already resolved
 * comes back `Mismatch` rather than being overwritten with bytes derived from a stale read. The plan
 * is executed or it is abandoned; it is never partially reinterpreted against a corpus that moved.
 *
 * **What an abort leaves behind (and why that is enough).** A filesystem cannot be transacted against
 * SQLite, so this pass does not pretend to two-phase commit. It guarantees the properties that
 * actually matter: it never resurrects a root, never writes into a path whose root is gone, and always
 * aborts LOUDLY. What survives an abort is a page whose `id_map` row exists while its file has no `id:`
 * line yet - which is precisely the state adopt EXISTS to repair, and re-running converges on it
 * (adopt deletes nothing and is idempotent). So: if a root disappears mid-run, adopt aborts; restore
 * the root and re-run.
 *
 * **Write durability (debate item 9):** every ContentStore write is announced through the
 * `logIntent` callback (path + id) BEFORE it is performed, so an interrupted run leaves a log from
 * which the completed/pending split is reconstructable; idempotence (a second MATERIALIZE run
 * performs zero writes) makes re-running safe. On NFS/SMB the underlying atomic rename falls back
 * to copy+delete (not crash-atomic) — the caveat the `adopt` output documents.
 *
 * **Root loss** takes the shared exit boundary ([RootLossClassifier]) on both phases, so a vanished
 * disk aborts the run as a classified [RootUnavailable] the CLI can report, while a genuine fault (a
 * corrupt file, a bug) still propagates as itself instead of being laundered into "the disk is gone".
 *
 * Git-mode single batched commit for `adopt --write-ids` is Phase 3 (no Git layer exists yet) — a
 * deferred hook, not a dropped requirement.
 *
 * Pure domain orchestration over ports; roots are resolved in registry (D7) rank order and pages in
 * path order, so duplicate resolution and the intent log are deterministic.
 */
class AdoptionPass(
    sources: List<Source>,
    private val idMap: IdMapRepository,
    private val identity: PageIdentityService,
    private val patcher: FrontmatterPatcher,
    private val rootLoss: RootLossClassifier,
    // The frozen content hash, passed as the domain object every other pass holds: it is the CAS
    // PRECONDITION here, so the value adopt compares against must be bit-for-bit the one the write
    // pipeline and the citations use, never a second definition that could drift from it.
    private val citations: CitationFactory,
    rootRank: (RootName) -> Int,
    private val registeredRoots: Set<RootName>,
) {

    /** One root's tree. */
    data class Source(val root: RootName, val store: ContentStore)

    init {
        val names = sources.map { it.root }
        require(names.size == names.toSet().size) {
            "duplicate source root(s): ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.joinToString(", ")}"
        }
        // A root the rank source does not know comes back -1, which would otherwise silently sort it
        // FIRST - i.e. silently seat an unknown root as the top-rank winner (the `IndexBuilder` guard).
        sources.forEach { source ->
            require(rootRank(source.root) >= 0) { "source root '${source.root}' is unknown to the registry rank" }
        }
    }

    // Sorted by the shared D7 rank, never trusted from the caller: the plan must be DETERMINISTIC, and the
    // same corpus must produce the same report and the same patched bytes on every run.
    private val sources: List<Source> = sources.sortedBy { rootRank(it.root) }

    private val storeOf: Map<RootName, ContentStore> = this.sources.associate { it.root to it.store }

    /** The roots this pass can see. Adopt covers every configured root or refuses, so this IS the registry. */
    private val scannedRoots: Set<RootName> = this.sources.mapTo(mutableSetOf()) { it.root }

    /** The ONE 404-vs-503 rule (C1), over the SAME durable index this pass binds into. Never re-derived here. */
    private val absence = AbsenceClassifier(idMap)

    /** The three `adopt` modes — see the class header for the frozen write policy of each. */
    enum class Mode {
        /** Default `adopt`: id_map rows (and issues) only; zero ContentStore writes. */
        RECORD,

        /** `adopt --write-ids`: RECORD plus materialization of every accepted page. */
        MATERIALIZE,

        /** `adopt --write-ids --dry-run`: report only; no file writes, no db writes. */
        PREVIEW,
    }

    /** What happened (or would happen) to one page. */
    enum class Disposition {
        /** Identity lives in id_map only — the file was not (and would not be) touched. */
        MAPPED,

        /** The file already carries its assigned id; nothing to write. */
        ALREADY_MATERIALIZED,

        /** MATERIALIZE: the id line was written into the file. */
        MATERIALIZED,

        /** PREVIEW: a MATERIALIZE run would patch this page. */
        WOULD_MATERIALIZE,

        /** The patcher refused (rule-naming message in [PageReport.issues]); map identity kept. */
        REFUSED,
    }

    /** One page's resolved identity and outcome, plus any issues raised on the way. */
    data class PageReport(
        val root: RootName,
        val path: TreePath,
        val id: PageId,
        val source: PageIdentityService.Source,
        val disposition: Disposition,
        val issues: List<IdentityIssue>,
    )

    /** One root's section of a [Plan] — the `adopt` output contract's unit, per-tree by construction. */
    data class Report(val mode: Mode, val pages: List<PageReport>) {

        val issues: List<IdentityIssue> get() = pages.flatMap { it.issues }

        fun pages(disposition: Disposition): List<PageReport> = pages.filter { it.disposition == disposition }
    }

    /**
     * One page's planned file write: the patched [bytes], and [baseHash] over the bytes they were computed
     * FROM. The base hash is what makes the write a CAS rather than a hopeful overwrite - the plan's authority
     * to replace a file expires the moment that file stops being the one it read.
     */
    class PlannedWrite(val baseHash: String, val bytes: ByteArray)

    /**
     * The whole corpus, resolved but not yet applied: what every page's identity WILL be, and — for the
     * pages that get patched — the exact bytes that will land. [apply] is a pure execution of this, and
     * the dry run is a pure rendering of it, which is what makes the two impossible to disagree.
     */
    class Plan(
        val mode: Mode,
        /** Every page, in rank-then-path order (the bind order [apply] must keep). */
        val pages: List<PageReport>,
        private val patched: Map<RootedPath, PlannedWrite>,
        /**
         * The authority the plan was RESOLVED under, carried so [apply] binds under exactly the same rule (C0).
         * Re-deriving it here would let the two drift, and the drift would be silent: the resolve would refuse to
         * take an id and the bind would take it anyway.
         */
        internal val supersession: Supersession = Supersession.NONE,
    ) {

        /** [root]'s section of the report — the per-tree unit the `adopt` output prints. */
        fun report(root: RootName): Report = Report(mode, pages.filter { it.root == root })

        /** The write [apply] will perform for [page], or null when the page needs none. */
        fun writeFor(page: RootedPath): PlannedWrite? = patched[page]

        /** The bytes [apply] will write for [page], or null when the page needs no write (what a PREVIEW renders). */
        fun bytesFor(page: RootedPath): ByteArray? = patched[page]?.bytes
    }

    /**
     * The whole pass: [plan], then — unless the mode is [Mode.PREVIEW], whose contract is zero writes — [apply] of
     * THAT plan. Returns it, because the plan is also the report: the dry run renders the very same object the write
     * phase executes, so a preview cannot disagree with the write it previews.
     */
    fun run(mode: Mode, logIntent: (RootedPath, PageId) -> Unit = { _, _ -> }): Plan =
        plan(mode).also { if (mode != Mode.PREVIEW) apply(it, logIntent) }

    /**
     * PHASE 1, READ-ONLY: scans every configured root, resolves the whole corpus's identity in ONE pass
     * (every duplicate contest within its own root), and computes each accepted page's patched bytes. Writes nothing —
     * not a file, not a row — so an abort here (a root that vanishes mid-scan) costs nothing.
     *
     * ALL roots are scanned before the FIRST resolve. The original reason - a not-yet-scanned root's binding
     * reading as DETACHED - cannot arise here: [scannedRoots] is fixed to every source root up front. What
     * genuinely depends on scanning first is WITHIN-root witness completeness, and note the strictly required
     * rule is weaker than the one enforced: a root must be scanned FULLY before any of ITS OWN pages resolve
     * (`ownerOf` is root-scoped, so other roots cannot matter). Scanning everything up front is simply the
     * cheapest way to guarantee that. Break it and, for an owner whose binding is MATERIALIZED, the unwitnessed
     * owner sits under a root that IS in [scannedRoots], the visibility gate falls through to `materialized`,
     * it stops counting as an owner, and a claimant resolved first takes its id - inverting §5.2. The damage is
     * the BINDING: `/p/{root}/{id}` moves to the claimant, the beaten owner is minted a fresh id, and the
     * `DuplicateId` is recorded the wrong way round. Its FILE survives, and not by luck - a materialized owner's
     * `id:` is already in the file, so the patcher answers `AlreadyPresent` and the page plans as MAPPED. An
     * UNMATERIALIZED owner is undisplaceable and never inverts at all.
     */
    fun plan(mode: Mode): Plan {
        val drafts = sources.flatMap { source -> scan(source) }
        val witnessed = drafts.mapTo(mutableSetOf()) { it.page }
        val claimed = HashMap<RootedPageId, RootedPath>()
        val patched = HashMap<RootedPath, PlannedWrite>()
        // The SAME supersession rule `IndexBuilder` resolves and binds under (C0) - one object, so the two passes
        // cannot drift into disagreeing about whose id is whose. This pass mints no proofs either.
        val supersession = Supersession(witnessed = witnessed, scannedRoots = scannedRoots, registeredRoots = registeredRoots)

        val pages = drafts.map { draft ->
            val assignment = identity.resolve(
                path = draft.page,
                rawFrontmatterId = patcher.readIdValue(draft.bytes),
                mappedId = idMap.find(draft.page)?.id,
                // Duplicate-detection seam, ROOT-SCOPED to this draft's own root (per-root identity, C5): within-run
                // claims first, then id_map bindings classified by the shared D16 rule, then the TOMBSTONES - a
                // retired id is reserved forever WITHIN its root. A cross-root duplicate is legal, so ownerOf never
                // returns an owner in another root.
                ownerOf = { id ->
                    claimed[RootedPageId(draft.page.root, id)]
                        ?: idMap.bindingInRoot(draft.page.root, id)
                            ?.takeIf { BindingVisibility.isLive(it, witnessed, scannedRoots, registeredRoots, supersession) }
                            ?.path
                        ?: idMap.retiredAt(draft.page.root, id)?.path
                },
            )
            claimed[RootedPageId(draft.page.root, assignment.id)] = draft.page
            planPage(mode, draft, assignment, patched)
        }

        logger.info {
            "adoption plan ($mode) over ${sources.size} root(s): ${pages.size} page(s), " +
                "${patched.size} to materialize, ${pages.count { it.issues.isNotEmpty() }} with issues"
        }
        return Plan(mode, pages, patched, supersession)
    }

    /**
     * PHASE 2: materializes [plan] — id_map bindings for every page, and the planned bytes for the
     * pages that carry a patch. Never called for [Mode.PREVIEW], whose contract is zero writes.
     *
     * **RE-PROBES every root before the first write.** The plan was resolved against all of them, so a
     * root that has gone away since must abort the whole run rather than half-apply it: a corpus whose
     * identity is partly adopted is the state an operator would never think to re-check. Adopt deletes
     * nothing and is idempotent, so aborting costs only a re-run.
     *
     * [logIntent] is invoked (page + id) immediately BEFORE each ContentStore write — the pre-write
     * intent log the durability policy requires. THROWS [RootUnavailable] if a root is gone, [PlanStale]
     * if a PAGE moved under the plan, and [AdoptWriteFailed] if a write faulted. All three ABORT the run
     * rather than improvise, and all three leave only state a re-run converges on.
     */
    fun apply(plan: Plan, logIntent: (RootedPath, PageId) -> Unit = { _, _ -> }) {
        check(plan.mode != Mode.PREVIEW) { "PREVIEW's contract is zero writes; it is rendered from the plan, never applied" }
        // One id, one page - checked before the first bind, never after the last (see [requireDistinctIds]). PREVIEW
        // renders the same plan and writes nothing, so the check belongs on the arm that makes it durable.
        requireDistinctIds(plan.pages.associate { RootedPath(it.root, it.path) to it.id })
        sources.forEach { source ->
            if (rootLoss.markIfGone(source.root, source.store)) throw RootUnavailable(source.root, UnavailableCause.VANISHED)
        }

        plan.pages.forEach { page ->
            val target = RootedPath(page.root, page.path)
            val idInFile = page.source == PageIdentityService.Source.FRONTMATTER
            val outcome = idMap.bind(target, page.id, materialized = idInFile, supersession = plan.supersession)
            check(outcome is BindOutcome.Bound) {
                "adoption resolved ${page.id.value} for ${page.path.value} in '${page.root}', and the bind REFUSED it " +
                    "(${(outcome as BindOutcome.Refused).heldBy} still holds it). The resolver and the bind gate disagree " +
                    "about who owns that id; adopt aborts rather than improvise a supersession neither of them authorized."
            }
            plan.writeFor(target)?.let { planned ->
                // Intent BEFORE write (durability policy), write, THEN mark materialized.
                logIntent(target, page.id)
                materialize(target, planned)
                idMap.markMaterialized(target)
            }
            page.issues.forEach(idMap::record)
        }
        val materialized = plan.pages.count { it.disposition == Disposition.MATERIALIZED }
        logger.info { "adoption pass (${plan.mode}): ${plan.pages.size} page(s) bound, $materialized materialized" }
    }

    /**
     * ONE page's file write: the non-resurrecting, non-clobbering CAS (class header). Every arm but
     * `Written` ABORTS the run - there is no arm on which improvising is the safe move:
     *  - `Deleted`: the page is gone. [ContentStore.write] would have RECREATED it (parents and all), which
     *    is how a lost root came back as a partial skeleton tree. Refuse, and say which page.
     *  - `Mismatch`: the file is no longer the one the patch was computed from. The planned bytes are stale;
     *    writing them would silently revert whoever edited it.
     *  - `Unreadable`: the write faulted. Its [CasResult.Unreadable.targetMutated] is carried out verbatim
     *    rather than flattened, because "nothing landed" and "the AUTHORITY holds the bytes but the local
     *    mirror does not" are different things to tell an operator - and the second one SELF-HEALS, since
     *    every non-PREVIEW run hydrates the mirror before it plans.
     * A root that went away raises [RootUnavailable] from inside the store (already marked); [rootLoss]
     * carries it out unchanged, and re-classifies a raw IO fault that turns out to BE a lost root.
     */
    private fun materialize(target: RootedPath, planned: PlannedWrite) {
        val store = storeOf.getValue(target.root)
        val result = rootLoss.guarding(target.root, store) {
            store.compareAndSwapWrite(target.path, planned.baseHash, planned.bytes, citations::contentHash)
        }
        when (result) {
            is CasResult.Written -> Unit
            CasResult.Deleted -> throw PlanStale(target, "the page was deleted after the plan read it")
            is CasResult.Mismatch -> throw PlanStale(target, "the page changed on disk after the plan read it")
            is CasResult.Unreadable -> throw AdoptWriteFailed(target, result.cause, result.targetMutated)
        }
    }

    /** One page in hand: its rooted identity-to-be and the bytes both the resolve and the patch read. */
    private class Draft(val page: RootedPath, val bytes: ByteArray)

    /** ONE root's tree, in path order, under the shared root-loss exit boundary. */
    private fun scan(source: Source): List<Draft> = rootLoss.guarding(source.root, source.store) {
        source.store.scan().files
            .map { it.path }
            .filter { it.name.endsWith(".md") }
            .sortedBy { it.value }
            .mapNotNull { path ->
                // CLASSIFIED, not `checkNotNull` (the `IndexBuilder.scan` rule, which this had escaped): a bare
                // null read cannot tell a page deleted mid-scan from a root that went away UNDER the scan, and
                // the resulting IllegalStateException would walk straight past the `guarding` boundary above -
                // bypassing the classifier, leaving the root unmarked, and handing the CLI a stack trace where
                // it had an actionable "restore the path and re-run" to print.
                //
                // **Adopt ABORTS on an unverified absence (C1), where the indexer merely carries on.** It is the
                // asymmetry between reading and WRITING: this pass materializes ids into the operator's own files
                // and binds permalinks off a resolve that ran over every root at once, so a page it could not read -
                // whose row the index still holds - means the corpus it is resolving against is not the corpus. Adopt
                // never runs against a view it cannot verify; it says so, and the operator re-runs.
                val target = RootedPath(source.root, path)
                val bytes = when (val read = absence.read(source.store, target)) {
                    is ContentRead.Bytes -> read.bytes
                    ContentRead.RootDown -> throw RootUnavailable(source.root, UnavailableCause.VANISHED)
                    ContentRead.AbsenceUnknown -> throw AbsenceUnverified(target)
                    // Never indexed and no longer on disk: it lost a race with an ordinary `rm`, it has no binding to
                    // protect, and there is nothing here to adopt. Skipping it costs the pass nothing.
                    ContentRead.ConfirmedAbsent -> return@mapNotNull null
                }
                Draft(target, bytes)
            }
    }

    /**
     * One page's planned outcome. The patch is computed HERE, in the read-only phase, and its bytes are
     * handed to the plan: the preview and the write are then the same bytes by construction, and the
     * write phase never re-derives anything it could get wrong.
     */
    private fun planPage(
        mode: Mode,
        draft: Draft,
        assignment: PageIdentityService.Assignment,
        patched: MutableMap<RootedPath, PlannedWrite>,
    ): PageReport {
        val issues = mutableListOf<IdentityIssue>()
        assignment.issue?.let(issues::add)
        val page = draft.page

        val disposition = when {
            assignment.source == PageIdentityService.Source.FRONTMATTER -> Disposition.ALREADY_MATERIALIZED
            mode == Mode.RECORD -> Disposition.MAPPED
            else -> when (val result = patcher.patch(draft.bytes, assignment.id)) {
                is FrontmatterPatcher.PatchResult.Patched -> {
                    // The base hash is captured HERE, off the very bytes the patch was derived from, so the
                    // CAS in [apply] is asserting exactly the read this plan was built on - not a re-read.
                    patched[page] = PlannedWrite(baseHash = citations.contentHash(draft.bytes), bytes = result.bytes)
                    if (mode == Mode.PREVIEW) Disposition.WOULD_MATERIALIZE else Disposition.MATERIALIZED
                }
                // A column-0 `id` key whose value is not this page's assigned id — never overwritten.
                FrontmatterPatcher.PatchResult.AlreadyPresent -> Disposition.MAPPED
                is FrontmatterPatcher.PatchResult.Refused -> {
                    issues += IdentityIssue.PatchRefused(page.root, page.path, result.message)
                    Disposition.REFUSED
                }
            }
        }
        return PageReport(page.root, page.path, assignment.id, assignment.source, disposition, issues.toList())
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * The corpus moved under a plan that had already been resolved: [page] was deleted, changed, or could not be
 * written. Distinct from [RootUnavailable] on purpose - the DISK is fine, one PAGE is not what it was - and the
 * two want different words from the operator ("restore the root" vs "something is writing to your tree").
 *
 * It aborts rather than skipping because the plan IS the report: a page reported MATERIALIZED that was quietly
 * stepped over would make the printed run a lie, and the two things adopt could do instead - recreate a deleted
 * page, or overwrite an edited one with bytes derived from a stale read - are both worse than stopping. Adopt
 * deletes nothing and is idempotent, so the cost of aborting is one re-run against whatever is there now.
 */
class PlanStale(val page: RootedPath, val reason: String) :
    RuntimeException("adoption plan is stale at ${page.root}:${page.path.value}: $reason")

/**
 * A planned write FAULTED at [page] (permission, locked file, transport, a failed mirror apply) - as distinct from
 * [PlanStale], where the write was refused because the target was no longer the page the plan had read.
 *
 * [targetMutated] is the one thing an operator has to know here, and it is the port's own
 * [CasResult.Unreadable.targetMutated]: false means NOTHING landed; true means the bytes may already be DURABLE at
 * the authority even though the operation failed (an object backend whose conditional PUT succeeded and whose local
 * mirror apply then did not). Neither is a corruption, and neither needs a hand-repair: the binding was made, the
 * file either has its id or does not, and a re-run - which hydrates the mirror first - re-plans against whichever
 * of those is true and converges. Adopt is idempotent; this is why that matters.
 */
class AdoptWriteFailed(val page: RootedPath, val reason: String, val targetMutated: Boolean) : RuntimeException(
    "adoption write failed at ${page.root}:${page.path.value}: $reason" +
        if (targetMutated) " (the bytes may already be durable at the authority)" else "",
)
