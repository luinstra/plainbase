@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.HistoryCommandException
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.principal.ManageGrant
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.PreviousUrl
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Chunk 5's index pass (caching decision §C4; N root sources since multi-root C2, ADR-0011):
 * scan → frontmatter → identity → URLs → render metadata → one immutable [PageIndex] of per-root
 * sections, published atomically. The full scan runs at startup and on rescan (the chunk-6 admin
 * route calls [rebuild]); watcher-driven incremental updates are Phase 2. Since C4 the runtime wires
 * EVERY registered root as a source, in registry (D7) order.
 *
 * **A root that is not there is SKIPPED, never treated as empty (ADR-0011 D5).** Each pass probes each
 * source's store: an already-Unavailable root is skipped outright (the status is sticky until restart), and
 * a root whose probe fails now is MARKED Unavailable and skipped. A skipped root's LAST-GOOD section is
 * carried into the new snapshot verbatim, because the publication listeners ARE the deletion pipelines - a
 * dropped section would purge that root's search rows AND its `page_checkpoint` rows (durable state) in one
 * publish, i.e. a mass delete caused by an unplugged disk. A never-scanned root simply contributes no
 * section, and the listeners' authority set ([PublicationListener.published]'s `scannedRoots`) is what keeps
 * its rows safe there.
 *
 * **What is classified is the COMPLETED SCAN, never the precondition** ([scanIfAvailable]). The entry probe
 * says the root was there when the walk STARTED; the artifact that gets DELETE AUTHORITY is the scan that came
 * back, and a root can vanish in between - a directory iteration whose tree disappears mid-walk can return
 * SHORT (or empty) without throwing anything, and a short scan admitted to `scannedRoots` authorizes exactly
 * the checkpoint/search deletions and binding supersessions D5 exists to prevent. So the root is re-probed at
 * HANDOFF, and a scan whose root is gone by then is skipped and carried like any other loss. A scan that
 * THROWS is classified the same way; what a LIVE-root failure costs is THAT root's pass, never the whole
 * rebuild - one unreadable subdirectory in one extra root must not take the other roots (or, at boot, the
 * server) down with it. It is not marked unavailable either: a chmod is fixed in place, and sticky
 * unavailability would prescribe a restart nobody needs. It skips, carries, WARNs loudly, and the next pass
 * retries it.
 *
 * **The corpus-loss tripwire** ([admitToAuthority]) - the last check, and the only one anchored in state that
 * SURVIVES A RESTART. Every probe above is a proxy for "is the corpus there", and each proxy has a hole: three
 * `stat`s cannot see an unmount, a tree's identity cannot see a container runtime creating the bind-mount
 * directory it could not find, and NOTHING captured at construction can see a boot that starts inside the
 * outage. What all of them come out as is a root that scans to ZERO pages - and zero is indistinguishable from
 * a full-corpus delete, which is the one instruction that destroys everything. So the pass asks the only oracle
 * that outlives the process: a scan that collapses a root to zero pages, for a root whose DURABLE rows
 * (`id_map`, `page_checkpoint`) say it holds content and whose corpus THIS PROCESS HAS NEVER SEEN ON DISK, is
 * not a delete - it is a broken view. It is refused delete authority, carried forward, marked
 * [UnavailableCause.CORPUS_MISSING], and logged with both counts. A root whose corpus this process DID scan and
 * then watched drain is a genuine wipe and still deletes (the pass saw it happen); an operator whose corpus was
 * really wiped WHILE DOWN names the root in `acceptEmptyRoots` (`PLAINBASE_ACCEPT_EMPTY_ROOTS`) and the wipe
 * lands. That makes the tree-identity probe a HINT and this the authority, which is the right way round: the
 * hint is what tells a REPLACED tree (a rename-in deploy) from a lost one, and it is allowed to be wrong.
 *
 * The rule is at the ONE place delete authority is granted, so every consumer inherits it - the checkpoint
 * replace, the search sync, the search generation swap, the id_map supersessions, and (through the availability
 * mark) the dirty-page reconcile. None of them re-derives it, and none of them may.
 *
 * **One pass:** each file's bytes are read exactly once ([ContentStore.read]), each page's
 * frontmatter values are parsed exactly once ([FrontmatterParser], over the already-read bytes —
 * render only re-detects the block boundary, never the values), and each page is rendered exactly
 * once ([MarkdownRenderer.render]). The same in-hand bytes also yield the page's verbatim
 * `markdown` and its content hash ([CitationFactory]), so the read path never touches disk for
 * pages. The parse runs up front because URL construction needs every page's `slug` BEFORE any
 * page renders — rendered links embed other pages' canonical URLs — so render happens against a
 * URL-complete skeleton snapshot built first, each root's pages against that root's [PageIndex.view].
 *
 * **Cross-root identity (C2):** identities resolve GLOBALLY across all sources in registry (D7)
 * rank order, under the [PageIdentityService] cross-root winner policy; a binding's liveness AND its
 * supersedability are classified by the shared [BindingVisibility] rule over the scanned/registered
 * root sets (D16). A pass therefore NEVER supersedes a binding under a root it did not scan - not
 * even on rank. That is the same no-delete rule the carried-forward section implements, applied to
 * identity: a skipped root's page is still IN the snapshot, so taking its id would both destroy the
 * durable binding an outage gave us no authority to touch (D-C4-10) and put a DUPLICATE id in the
 * snapshot (a rebuild crash, and a wedged rescan/scheduler with it). The scanned claimant reassigns
 * instead, and the rank contest waits for a pass that can see both roots. The two D17 execution
 * invariants live as comments at their enforcement points in [rebuild]/[resolveIdentities].
 *
 * **Safe publication, no `@Volatile`:** the new snapshot is built entirely off to the side and
 * published with a single [AtomicReference.store]; [current] readers always observe a complete,
 * internally consistent, deeply immutable [PageIndex] — old or new, never torn — and stay
 * lock-free. [rebuild] itself is `@Synchronized` (rescans are rare): two concurrent rebuilds
 * could otherwise publish out of order — the earlier-scanned one finishing later would regress
 * [current] to a stale world (a classic lost update).
 *
 * **Move aliases (§A4; down-time moves closed by the Phase-2 §B3 checkpoint):** a known id whose
 * canonical URL path changed since the previously published snapshot leaves its old (root, path)
 * behind as a `url_alias` row; the registry maps rooted paths straight to page ids, so chains
 * collapse on write (one hop after any number of moves). On the FIRST rebuild after startup the
 * previous paths come from the persisted [PageCheckpointRepository] instead of the (empty) holder,
 * so a materialized page moved while the server was down still records its alias. `redirect_from`
 * frontmatter registers through the same construction, in the declaring page's root namespace; a
 * live canonical path always shadows an alias (dropped, with a recorded `redirect_conflict` issue).
 *
 * Identity binding mirrors `AdoptionPass` RECORD semantics over the in-hand bytes (zero content
 * writes): id_map rows plus issues, sources in rank order and pages in path order so duplicate
 * resolution is deterministic.
 *
 * **Publication listeners (§B4, the Phase-2/3 seam):** after the snapshot publishes, [rebuild] —
 * still inside its serialized section — synchronously invokes every registered
 * [PublicationListener], so listeners (checkpoint replace, search sync) can never interleave or
 * run against a superseded snapshot. A throwing listener is caught and logged here: the publish
 * has already happened and stands, the remaining listeners still run, and nothing propagates to
 * any [rebuild] caller (a failed search sync is repaired for free by the next sync's engine-truth
 * diff). Phase 3: the save path calls [rebuild], so a saved page is searchable before the save
 * returns — this listener chain IS that hook; nothing else to build.
 */
class IndexBuilder(
    sources: List<Source>,
    private val frontmatterParser: FrontmatterParser,
    private val rendererFactory: (PageIndexView) -> MarkdownRenderer,
    private val identity: PageIdentityService,
    private val patcher: FrontmatterPatcher,
    private val idMap: IdMapRepository,
    private val aliasRegistry: UrlAliasRegistry,
    private val checkpoint: PageCheckpointRepository,
    private val citations: CitationFactory,
    rootRank: (RootName) -> Int,
    private val registeredRoots: Set<RootName>,
    /**
     * The roots whose emptiness the OPERATOR has declared (`PLAINBASE_ACCEPT_EMPTY_ROOTS`) - the one way a
     * genuine full-corpus wipe performed while the server was DOWN gets its delete authority back, since the
     * corpus-loss tripwire cannot tell it from an unmounted volume and must not guess (see the class doc).
     */
    private val acceptEmptyRoots: Set<RootName> = emptySet(),
    private val listeners: List<PublicationListener> = emptyList(),
    private val searchIndexer: SearchIndexer? = null,
    /** The availability HOLDER, not a captured map: this builder both READS it (skip a sticky-Unavailable root)
     *  and WRITES it (mark a root whose probe just failed). Defaulted so single-root constructions stay terse. */
    private val availability: RootAvailability = RootAvailability(kotlin.time.Clock.System),
) {

    /** One root's inputs: its topology entry, its content tree, and its history. */
    data class Source(
        val root: Root,
        val store: ContentStore,
        val history: HistoryProvider,
    )

    /**
     * Notified with each newly published snapshot — synchronously, inside the serialized rebuild (§B4).
     *
     * [scannedRoots] is the AUTHORITY SET: the roots this pass walked IN FULL, and therefore the ONLY roots a
     * listener may DELETE rows for. Every other class falls out of the complement for free - a root skipped this
     * pass, a root never scanned since boot, a root whose scan came back as a partial VIEW rather than a corpus
     * (an object mirror with unhydrated objects in it), a root the corpus-loss tripwire refused, and a DETACHED
     * root whose rows outlive its name in `roots {}` - which is why the parameter is the POSITIVE set. A
     * listener holds rows keyed by roots it may no longer have any authority over; handing it the authority set
     * means the compiler, not a convention, is what stops the next listener from forgetting the rule. The
     * emptied-but-present control case stays correct too: a root this process SCANNED and then watched drain is
     * a genuine full-corpus delete, it stays in the set, and its rows still delete.
     */
    fun interface PublicationListener {
        fun published(snapshot: PageIndex, scannedRoots: Set<RootName>)
    }

    init {
        val names = sources.map { it.root.name }
        require(names.size == names.toSet().size) {
            "duplicate source root(s): ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.joinToString(", ")}"
        }
        // A root the rank source does not know comes back -1, which would otherwise silently sort
        // it FIRST - i.e. silently seat an unknown root as the top-rank winner.
        sources.forEach { source ->
            require(rootRank(source.root.name) >= 0) { "source root '${source.root.name}' is unknown to the registry rank" }
        }
    }

    // Sorted by the shared D7 rank (the SAME lambda PageIdentityService receives, wired once):
    // scan-and-resolve order is correctness-critical - the registry-order winner must always be
    // claimed and bound first - so it is enforced by construction, never trusted from the caller.
    private val sources: List<Source> = sources.sortedBy { rootRank(it.root.name) }

    private val sourcesByRoot: Map<RootName, Source> = this.sources.associateBy { it.root.name }

    /** The shared root-loss rule (probe → mark), over the SAME holder this builder reads and writes. */
    private val rootLoss = RootLossClassifier(availability)

    /**
     * The roots whose CORPUS this process has actually seen on disk - a completed scan that came back with
     * pages in it. It is the corpus-loss tripwire's exoneration ([admitToAuthority]): a root that goes to zero
     * pages AFTER we watched it hold them was emptied under our eyes and deletes normally, while a root that
     * has only ever read as empty is a claim we have nothing to check against, and durable rows that say
     * otherwise outrank an empty directory.
     *
     * Touched only from inside the `@Synchronized` [rebuild], so it needs no atomics - a plain set, published
     * by the same monitor everything else in a pass is.
     */
    private val corpusSeen = mutableSetOf<RootName>()

    /**
     * What a pass PUBLISHES: the snapshot, and the authority set of the pass that produced it - swapped as ONE
     * value, because they are one fact. A listener is handed both at publish time; [rebuildSearchIndex] reads
     * them back LATER, and reading the snapshot from one field and the authority from another could pair a fresh
     * snapshot with a stale authority - i.e. hand the engine permission to delete rows for a root the pass that
     * produced that snapshot had skipped.
     */
    private data class Published(val snapshot: PageIndex, val scannedRoots: Set<RootName>)

    private val holder = AtomicReference(Published(PageIndex.EMPTY, emptySet()))

    /** The published snapshot — always complete and consistent ([PageIndex.EMPTY] before the first build). */
    val current: PageIndex get() = holder.load().snapshot

    /** Runs the full pass and atomically publishes (and returns) the new snapshot (serialized — see class doc). */
    @Synchronized
    fun rebuild(): PageIndex {
        val previous = holder.load().snapshot
        // §B3 checkpoint-as-previous: the first rebuild after startup (holder still the EMPTY
        // sentinel) compares against the persisted checkpoint of the last published snapshot, so a
        // move performed while the server was down still records its alias. Every later rebuild
        // compares against the previous published snapshot, exactly as before.
        val previousUrlPaths =
            if (previous === PageIndex.EMPTY) {
                checkpoint.load()
            } else {
                previous.pages.associate { it.id to PreviousUrl(it.root, it.urlPath) }
            }

        // D17 execution invariant (b): scan ALL sources before the FIRST resolve. Only then is
        // scannedLive complete - under interleaved scan+resolve a binding in a not-yet-scanned
        // later root would be misclassified by the D16 visibility rule.
        //
        // D5: probe first, and skip what is not there. `scans` therefore holds only the roots this pass actually
        // walked - and the AUTHORITY set is narrower still: only the walks that came back COMPLETE. A partial
        // view (an object mirror with unhydrated objects) still publishes the pages it found, because a
        // transient GET failure must not blank a site; what it must never do is authorize a deletion, and the
        // two are separate powers. The set is computed from the rebuild PARTITION, never read back from the
        // availability holder at publish time: the partition is what this pass DID, whereas the holder can gain
        // a watcher-failure flip mid-pass for a root that WAS scanned - and that root's rows must still take
        // this publish's diff.
        //
        // The corpus-loss tripwire ([admitToAuthority]) then runs over ALL of them at once, because its exonerating
        // evidence - a row's page, found in another root - is in the OTHER scans.
        val scans = admitToAuthority(sources.mapNotNull { scanIfAvailable(it) })
        val scannedRoots: Set<RootName> = scans.filter { it.complete }.map { it.root }.toSet()

        // The scan's own issue rows are persisted only once the scan that produced them COMPLETED. Recording
        // them as they were found would leave rows behind from a pass that never happened: a scan that dies
        // half-way is SKIPPED and its last-good section carried, so its half-walked path/URL collisions describe
        // a tree nobody indexed. The identity issues below ride the resolve, which only ever sees full scans.
        scans.forEach { scan -> scan.issues.forEach(idMap::record) }

        val identities = resolveIdentities(scans, scannedRoots)

        // Build ALL provisional sections, then render each root's pages against ITS view of the
        // ONE URL-complete skeleton (identity and URLs final; render fields filled below).
        val provisionalSections = scans.map { scan ->
            RootSection(
                root = scan.root,
                pages = scan.drafts.map { draft ->
                    provisionalPage(scan, draft, identities.getValue(RootedPath(scan.root, draft.file.path)))
                },
                folders = scan.folders,
                assets = scan.assets,
            )
        }
        val provisional = PageIndex(provisionalSections)
        val scanned = scans.zip(provisionalSections) { scan, section ->
            val renderer = rendererFactory(provisional.view(scan.root))
            section.copy(
                pages = section.pages.zip(scan.drafts) { page, draft ->
                    val rendered = renderer.render(page.path, draft.bytes)
                    page.copy(
                        title = draft.frontmatter.scalar("title")
                            ?: rendered.headings.firstOrNull { it.level == 1 }?.text
                            ?: page.path.stem,
                        html = rendered.html,
                        headings = rendered.headings.toList(),
                        links = rendered.links.toList(),
                        // The §B4 search sections, captured from the SAME single render — no extra read,
                        // no second parse (see the IndexedPage.sections doc for the accepted memory cost).
                        sections = rendered.sections.toList(),
                    )
                },
            )
        }

        // Carry each SKIPPED root's last-good section forward, so no listener sees a deletion (a never-scanned
        // root has no previous section and simply contributes none - `section` is total). In registry rank
        // order, like the sources themselves, so the snapshot is deterministic either way.
        val scannedIds = scanned.flatMap { section -> section.pages.map { it.id } }.toSet()
        val sections = sources.mapNotNull { source ->
            val root = source.root.name
            scanned.firstOrNull { it.root == root }
                ?: previous.sections.firstOrNull { it.root == root }?.let { carryForward(it, scannedIds) }
        }

        val snapshot = PageIndex(sections)
        recordAliases(previousUrlPaths, snapshot)
        holder.store(Published(snapshot, scannedRoots))
        logger.info {
            val breakdown = if (snapshot.sections.size > 1) {
                snapshot.sections.joinToString(prefix = " [", postfix = "]") { "${it.root}: ${it.pages.size} page(s)" }
            } else {
                ""
            }
            "indexed ${snapshot.pages.size} page(s), ${snapshot.sections.sumOf { it.assets.size }} asset(s), " +
                "${snapshot.sections.sumOf { it.folders.size }} folder(s); " +
                "${snapshot.pages.count { it.urlPath == null }} excluded from path space" + breakdown
        }
        notifyPublished(snapshot, scannedRoots)
        return snapshot
    }

    /**
     * The MANAGE-gated rescan entry (A3): the admin `rescan` route reaches the full pass ONLY through this thin
     * wrapper, which requires a [ManageGrant] minted by `PolicyService.checkManage()`. The no-arg [rebuild] stays
     * for the MANY internal callers (the watcher loop, startup reconcile, the create route's post-write index, the
     * asset facade's post-write rebuild) — they are not a manage admin action, so they keep the ungated path.
     * "Gain a grant param, keep the logic": the gated overload is the new surface, the body is the shared no-arg.
     */
    fun rebuild(@Suppress("UNUSED_PARAMETER") grant: ManageGrant): PageIndex = rebuild()

    /**
     * Search-only full rebuild (the S8 reindex path): reads the CURRENT published snapshot AND
     * rebuilds the search engine from it, both inside the SAME monitor [rebuild]/[notifyPublished]
     * use. So a concurrent watcher rebuild either fully precedes this (the reindex sees its
     * snapshot) or fully follows it (its own [SearchIndexer.sync] runs afterward) — it can never
     * interleave to roll the engine back to a stale generation (the debate-caught regression a
     * naive read-`current`-then-`rebuild` would reopen). This is NOT a page rescan: no scan, no
     * checkpoint listener re-fire — just a clean generation swap of the engine over the snapshot
     * already published. Both the reindex endpoint and the `plainbase reindex` CLI route through
     * here. Returns the page count rebuilt into the engine (the §C4 reindex-response figure).
     *
     * It swaps the engine under the SAME delete authority the pass that published this snapshot ran under, which
     * is why the two travel together in [Published]. Without it the swap is a mass delete for any root the pass
     * skipped: an unavailable root has no section in the snapshot, so the engine would re-derive the corpus
     * WITHOUT it and drop its rows - the D5 lie, performed by a reindex nobody meant as a deletion.
     */
    @Synchronized
    fun rebuildSearchIndex(): Int {
        val indexer = requireNotNull(searchIndexer) { "rebuildSearchIndex() needs a SearchIndexer; none was wired into this IndexBuilder" }
        val published = holder.load()
        indexer.rebuild(published.snapshot, published.scannedRoots)
        return published.snapshot.pages.size
    }

    /**
     * The MANAGE-gated reindex entry (A3): the admin `reindex` route + the `plainbase reindex` CLI reach the
     * engine generation-swap ONLY through this thin wrapper, which requires a [ManageGrant]. The no-arg
     * [rebuildSearchIndex] stays the internal surface (same gain-a-param-keep-the-logic shape as [rebuild]).
     */
    fun rebuildSearchIndex(@Suppress("UNUSED_PARAMETER") grant: ManageGrant): Int = rebuildSearchIndex()

    /**
     * Targeted single-page reindex (PB-WRITE-1 §B1 fix C): re-reads + re-renders ONLY the page at [target],
     * publishes a snapshot identical to the current one except for that page (its own root's section
     * rebuilt, every other section riding through untouched), and upserts that ONE page into search via
     * [SearchIndexer.syncPage]. O(changed-page) END-TO-END — render O(1), search O(1) (single-page
     * upsert, NOT the corpus-wide [SearchIndexer.sync] diff), checkpoint O(0) (skipped). Full [rebuild]
     * stays the startup/admin/watcher path. Shares the rebuild monitor, so a watcher rebuild never
     * interleaves. Bytes and history come from the target root's source.
     *
     * **The target is a [RootedPath], NOT a page id, and that is the whole point (ADR-0011 D17).** A page id
     * does not durably name a location: a rebuild can re-award it to another root the moment that root claims
     * the same frontmatter id (the cross-root duplicate-id rank contest). This method runs DOWNSTREAM of a CAS
     * that has already put bytes on ONE root's disk, and it takes a FRESH snapshot — so resolving the id here
     * would let a rebuild landing in that window send the reindex at a DIFFERENT root's file, splitting disk
     * truth from index truth. Taking the location the bytes actually went to makes that unreachable rather than
     * unlikely: the caller pins the write target, and this can never re-derive another owner from it.
     *
     * The caller (`WritePipeline.write`) has ALREADY rejected any id/slug/redirect_from change (the
     * edit-classification guard), so this page's identity, urlPath, and aliases are unchanged — only
     * its bytes-derived fields (markdown, contentHash, html, headings, links, sections, title) are
     * recomputed. So this does NOT call [notifyPublished] (which would fire the O(corpus) checkpoint
     * replace) and does NOT call [recordAliases]: there is nothing checkpoint- or alias-relevant to
     * change. A genuine rename never reaches here — it is a deferred §H operation through full [rebuild].
     *
     * Rendered against the CURRENT published snapshot's per-root view (URL-complete: every OTHER page's
     * canonical URL is final), so this page's outbound links resolve exactly as in a full rebuild.
     *
     * **Cross-page render coherence — a documented invariant, not a tracked feature.** Re-rendering
     * one page is correct iff a page's HTML/headings/links/sections are a pure function of its OWN
     * content (plus the unchanged URL-complete view). That holds today: the renderer embeds other
     * pages' URLs but never their content (no backlinks, no transclusion, no server-rendered
     * child-lists), and folder landing pages are client-rendered (ADR-0003). TRIPWIRE for whoever
     * later adds backlinks / transclusion / "pages that mention this one": that feature breaks the
     * pure-function assumption and must either re-render dependents or route through full [rebuild].
     *
     * THROWS [IllegalStateException] if [target] is absent from the snapshot or its file is unreadable on the
     * SAVE path: the CAS just wrote those bytes, so a missing page is a real invariant violation, never a silent
     * success. `WritePipeline.reconcileDirtyPages` tolerates a vanished page at its
     * OWN call site, never here.
     */
    @Synchronized
    fun reindex(target: RootedPath): PageIndex {
        val published = holder.load()
        val previous = published.snapshot
        val page = previous.byPath[target]
            ?: error("reindex($target): page not in the published snapshot — a save-path invariant violation")
        val source = sourcesByRoot[target.root]
            ?: error("reindex($target): no source for root '${target.root}' - the snapshot outran this builder's wiring")
        // The cheap belt for the ALREADY-MARKED case - a save into a vanished root must not half-run. The
        // facade's gate normally fires first, so this is an internal-path guard, not the 503 surface: when it
        // does fire mid-save, WritePipeline's post-write catch absorbs it into the honest WrittenButUnindexed
        // (the bytes ARE on disk, the dirty mark IS retained). A STATUS cannot answer for the UNMARKED window,
        // though, which is what the two classified calls below are for.
        if (!availability.current().isAvailable(target.root)) throw RootUnavailable(target.root, UnavailableCause.VANISHED)
        val bytes = when (val read = source.store.readClassified(target.path)) {
            is ContentRead.Bytes -> read.bytes
            // The store has already MARKED on its way out; this throw is only the carrier. Without it the read's
            // null became an error() the pipeline's blanket catch absorbed - the right wire answer, but the root
            // was never marked, so every subsequent READ kept serving its carried content.
            ContentRead.RootDown -> throw RootUnavailable(target.root, UnavailableCause.VANISHED)
            ContentRead.Absent -> error("reindex($target): ${target.path.value} unreadable just after a CAS write")
        }
        val parsed = frontmatterParser.parse(bytes)
        val rendered = rendererFactory(previous.view(target.root)).render(target.path, bytes)
        // One genuinely O(1) last-commit lookup for just this page (D-3, reversed by re-review P2-1): a
        // BOUNDED `git log --max-count=1 -- path`, NEVER `lastCommits` — which has no cap and buffers the
        // page's FULL history before parsing, so for a heavily-edited page every save/reconcile would read
        // the whole history (unbounded; can time out / null the commit). `log(path, 1)` shares `rebuild`'s
        // first-parent attribution, so the citation SHA stays consistent between the two paths.
        //
        // The git read takes the SAME exit boundary every other rooted backend call does ([RootLossClassifier]):
        // `git -C <workTree>` on a gone work tree raises a HistoryCommandException, which - unclassified - would
        // be absorbed by the pipeline with the root left AVAILABLE. A live-root git fault still rethrows as the
        // honest 500.
        val commit = rootLoss.guarding(target.root, source.store) { source.history.log(target.path, limit = 1).firstOrNull()?.sha }
        val reindexed = page.copy(
            frontmatter = parsed,
            markdown = String(bytes, Charsets.UTF_8),
            contentHash = citations.contentHash(bytes),
            commit = commit,
            title = parsed.scalar("title") ?: rendered.headings.firstOrNull { it.level == 1 }?.text ?: target.path.stem,
            html = rendered.html,
            headings = rendered.headings.toList(),
            links = rendered.links.toList(),
            sections = rendered.sections.toList(),
        )
        // Matched by PATH, like the lookup: the page occupying the written location is the one to replace, and
        // an id match here would re-introduce the very re-derivation the RootedPath target exists to prevent.
        val snapshot = PageIndex(
            previous.sections.map { section ->
                if (section.root == target.root) {
                    section.copy(pages = section.pages.map { if (it.path == target.path) reindexed else it })
                } else {
                    section
                }
            },
        )
        // The authority set rides through unchanged: this republishes ONE page of an already-scanned root, so it
        // says nothing new about which roots a pass has walked - and a search reindex racing it must still be
        // told what the last full pass knew.
        holder.store(published.copy(snapshot = snapshot))
        logger.info {
            "reindexed page ${reindexed.id.value} (${target.path.value} in '${target.root}'); ${snapshot.pages.size} page(s) published"
        }
        searchIndexer?.syncPage(reindexed) // genuine O(1) single-page upsert — NOT sync(snapshot), NOT notifyPublished
        return snapshot
    }

    /**
     * Renders a SUBMITTED Markdown buffer for the (private, non-contractual W3b) preview pane: PB-SLUG-1
     * heading ids + PB-LINK-1 link rewriting via the SAME [rendererFactory] every index render uses (§3
     * single-renderer rule — preview NEVER constructs its own renderer). Link resolution is against
     * [root]'s view of the CURRENT published snapshot [current] (so `[[other page]]` / relative links
     * resolve as a reader would see them); [sourcePath] is the buffer's notional location for
     * relative-href resolution (the editor's page path, or a synthetic root path when previewing a
     * not-yet-saved buffer). READ-ONLY: nothing is read from disk, nothing is published, no snapshot
     * swap — a pure function of [bytes] + the live view.
     */
    fun renderPreview(root: RootName, sourcePath: TreePath, bytes: ByteArray): RenderedPage =
        rendererFactory(current.view(root)).render(sourcePath, bytes)

    /**
     * A skipped root's last-good section, minus any page whose id a SCANNED root now holds — the last thing
     * standing between a duplicate id and [PageIndex]'s `byId` uniqueness check, which would otherwise throw
     * and take every future rebuild (rescan, watcher, scheduler) down with it until a restart.
     *
     * The D16 non-supersedable rule upstream is what makes this near-unreachable: a scanned page can no longer
     * TAKE an id from an unscanned root, so it cannot end up sharing one. What is left is durable state that
     * says otherwise - an `id_map` missing the binding that the carried page's id needs (a row an older build
     * superseded before the rule existed). Identity state we cannot trust must not be able to wedge the index,
     * so the SCANNED side wins (it is live disk truth) and the carried page steps out of the SNAPSHOT only.
     * Nothing durable is touched: the root is not in `scannedRoots`, so no listener may delete its rows, its
     * `id_map` binding stands, and the page returns with the root - by which point a full pass can settle the
     * contest properly. Every unaffected page of the downed root rides through untouched.
     */
    private fun carryForward(section: RootSection, scannedIds: Set<PageId>): RootSection {
        val kept = section.pages.filterNot { it.id in scannedIds }
        if (kept.size == section.pages.size) return section
        logger.warn {
            "carrying unavailable root '${section.root}' forward WITHOUT ${section.pages.size - kept.size} page(s) whose id a " +
                "scanned root now holds - its durable rows are untouched, and the pages return when the root does"
        }
        return section.copy(pages = kept)
    }

    /** §B4 listener exception policy: contain and log — the publish stands, the remaining listeners still run. */
    private fun notifyPublished(snapshot: PageIndex, scannedRoots: Set<RootName>) {
        listeners.forEach { listener ->
            try {
                listener.published(snapshot, scannedRoots)
            } catch (e: Exception) {
                // Exception, not Throwable — narrower than §B4's literal "nothing propagates" so a JVM Error (OOM/SOE) still fails loudly.
                logger.error(e) { "publication listener failed; the published snapshot stands" }
            }
        }
    }

    /** One page's in-flight state: read once, frontmatter parsed once, bytes kept for the single render. */
    private class Draft(
        val file: ContentFile,
        val bytes: ByteArray,
        val frontmatter: Frontmatter,
    )

    /**
     * One source's COMPLETED scan: drafts in path order, URLs assigned, last-commits batched — and the
     * path/URL-collision [issues] it raised, BUFFERED rather than persisted as they were found, so an
     * abandoned (root-loss) scan leaves no rows describing a tree it never finished walking. The caller
     * records them once the scan has come back whole.
     */
    private class SourceScan(
        val root: RootName,
        val drafts: List<Draft>,
        val folders: List<ContentFolder>,
        val assets: Set<TreePath>,
        val urls: CanonicalUrlBuilder.Result,
        val commits: Map<TreePath, Commit>,
        val issues: List<IdentityIssue>,
        /** Did the backend see the WHOLE tree ([ScanResult.complete])? Only a complete walk gets delete authority. */
        val complete: Boolean,
    )

    private class Identity(
        val id: PageId,
        val materialized: Boolean,
    )

    /**
     * [scan]s ONE source unless its root is not there - in which case it is MARKED (if the probe is what
     * discovered it), SKIPPED, and its last-good section carried forward by the caller. Null means skipped.
     *
     * **The scan is classified where it is HANDED OVER, not where it is started.** The entry probe below is a
     * cheap fail-fast, and it is all it is: what the pass grants delete authority to is the SourceScan that
     * came back, so that is what gets re-probed. A tree that vanishes DURING the walk does not have to throw -
     * a directory stream can simply run out of entries - and a short scan that reached `scannedRoots` would
     * take a live root's checkpoint rows, its search rows and its id_map bindings with it. Probing the
     * artifact instead of the precondition makes that structural rather than lucky.
     *
     * The classifier's carrier set is DERIVED from what `scan(source)` actually COLLABORATES with, not from
     * the NIO ladder - because this is a COMPOSITE rooted operation, not a store call:
     *  - `store.scan()` / `store.readClassified()` -> `IOException` (total over the store's NIO surface, once
     *    the directory-stream normalization has run);
     *  - `store.readClassified()`'s `RootDown` arm -> a `RootUnavailable` we throw ourselves;
     *  - `history.lastCommits()` -> every git call is `git -C <workTree>`, so a gone work tree exits non-zero
     *    and raises a [HistoryCommandException];
     *  - `idMap.record`/`bind` (a DB in DATA_DIR, a different tree) and the pure parser/URL builder -> nothing
     *    a vanished root can do. A throw from those is a GENUINE fault and takes the live-root arm.
     *
     * So it is those three and NOT `catch (Exception)`: a widened catch would swallow a programming error into
     * a skipped root, and the whole point of [skipOnLiveFailure]'s WARN is that a live root's failure stays
     * visible instead of being laundered into "the disk is gone".
     */
    private fun scanIfAvailable(source: Source): SourceScan? {
        val root = source.root.name
        if (!availability.current().isAvailable(root)) {
            logger.warn { "root '$root' is unavailable; skipping its scan and carrying its last-good section forward" }
            return null
        }
        if (rootLoss.markIfGone(root, source.store)) return skipAndCarry(root, "its backing tree is not traversable")
        val scan = try {
            scan(source)
        } catch (_: RootUnavailable) {
            // Only ever raised by the mark-then-throw rule, so the root is ALREADY marked and there is nothing
            // left to decide: skip and carry, unconditionally. Re-probing here would be worse than redundant -
            // a root that vanished and whose path has since REAPPEARED would probe PASS, and the pass would
            // then trust a scan the store already refused to answer for.
            logger.warn { "root '$root' vanished mid-scan; skipping it and carrying its last-good section forward" }
            return null
        } catch (e: IOException) {
            return classifyScanFailure(source, e)
        } catch (e: HistoryCommandException) {
            return classifyScanFailure(source, e)
        }
        // The handoff probe: the tree that handed this scan back must still be the tree we started on.
        if (rootLoss.markIfGone(root, source.store)) {
            return skipAndCarry(root, "it vanished while being scanned, so the tree it handed back is not a corpus")
        }
        return scan
    }

    /**
     * The CORPUS-LOSS TRIPWIRE: which of these scans are CORPORA, and which are broken views of one?
     *
     * A scan that collapses a root to ZERO pages is the shape EVERY undetected root loss arrives in - an
     * unmounted volume leaves its mount point behind, a container runtime CREATES the bind-mount directory it
     * could not find, a restore has not run yet - and it is also the shape of the one instruction that destroys
     * everything a root has: delete the whole corpus. The probes upstream cannot tell those apart at BOOT,
     * where the tree they would compare against is the broken one, and the remediation they prescribe (restart)
     * is the trigger. Durable state can: `id_map` and `page_checkpoint` rows outlive the process, ignore
     * inodes, and cannot be faked by an empty directory.
     *
     * So a zero-page scan of a root that HOLDS durable rows is refused - carried forward verbatim, marked
     * [UnavailableCause.CORPUS_MISSING] (an empty view must not be SERVED as a live corpus either: the mark is
     * what turns its pages into an honest 503 instead of the 404 that tells an agent to drop its citations, and
     * what stops `WritePipeline.reconcileDirtyPages` from clearing an interrupted save's only recovery record) -
     * unless one of three things says the emptiness is REAL:
     *  - THIS PROCESS scanned that root's corpus and then watched it drain ([corpusSeen]). An `rm -rf` under a
     *    running server is a genuine full-corpus delete and still deletes, which is the control case the whole
     *    D5 apparatus is balanced against;
     *  - the row's PAGE HAS BEEN FOUND, in a root this pass DID scan, carrying that id in its own file
     *    ([located]). A page that moved roots while the server was down leaves exactly the row a lost corpus
     *    leaves - and it is not evidence of a corpus HERE, because we are holding the page. Counting it would
     *    fire the tripwire on an ordinary cross-root move, and firing it would then keep the moved page's own
     *    binding UNSUPERSEDABLE (D16) - so the page that moved would LOSE the permalink it carried with it, on a
     *    pass that could see perfectly well where it went; or
     *  - the OPERATOR declared it ([acceptEmptyRoots]) - the way a wipe performed while the server was DOWN gets
     *    its deletion, since no probe and no row can tell that from an outage, and guessing is what got us here.
     *
     * A root with no durable rows (a fresh install, a newly added empty root) trips nothing: there is no corpus
     * to lose, and an empty root must be able to be empty.
     *
     * **Over the WHOLE pass, never per source.** The located-elsewhere evidence lives in the OTHER roots' scans,
     * so a per-source check would answer differently depending on rank order - it would exonerate a move into a
     * higher-ranked root and fire on the identical move into a lower-ranked one. Same discipline as the D17
     * scan-everything-before-you-resolve invariant, and for the same reason.
     */
    private fun admitToAuthority(scans: List<SourceScan>): List<SourceScan> {
        val located: Set<PageId> = scans.flatMapTo(mutableSetOf()) { scan ->
            scan.drafts.mapNotNull { draft -> patcher.readIdValue(draft.bytes)?.let(PageId::of) }
        }
        return scans.filter { scan -> admit(scan, located) }
    }

    /** One scan's admission (see [admitToAuthority]); false skips-and-carries the root. */
    private fun admit(scan: SourceScan, located: Set<PageId>): Boolean {
        val root = scan.root
        if (scan.drafts.isNotEmpty()) {
            if (scan.complete) corpusSeen += root // seen whole, with pages in it - the exoneration a later zero scan needs
            return true
        }
        if (root in corpusSeen || root in acceptEmptyRoots) return true
        val bindings = idMap.bindings().filter { it.path.root == root && it.id !in located }
        val checkpoints = checkpoint.load().filterValues { it.root == root }.keys.filterNot { it in located }
        if (bindings.isEmpty() && checkpoints.isEmpty()) return true
        availability.markUnavailable(root, UnavailableCause.CORPUS_MISSING)
        val where = sourcesByRoot[root]?.root?.localPath ?: "its backing store"
        logger.error {
            "root '$root' scanned to ZERO pages but holds ${bindings.size} id_map binding(s) and ${checkpoints.size} " +
                "checkpoint row(s) for pages found NOWHERE else, and this server has never seen its corpus on disk: " +
                "treating it as a BROKEN VIEW, not a delete - nothing is deleted for it, its last-good pages are carried " +
                "forward, and it serves 503 until it is restored and the server restarted. Check the mount at $where. If " +
                "its content really was deleted, restart with PLAINBASE_ACCEPT_EMPTY_ROOTS=${root.value} to accept the wipe."
        }
        return false
    }

    /**
     * The rebuild's arm of the shared [RootLossClassifier] rule: a failure whose re-probe FAILS means the root
     * vanished mid-operation - the same hazard class, since a half-scanned section is a partial mass-delete - so
     * mark, skip and carry. A failure whose re-probe still PASSES is NOT a disappearance (a parser bug, a corrupt
     * repo, an unknown git flag, a `chmod 000` subdirectory) and takes [skipOnLiveFailure]. A request-serving
     * surface wants the classifier's `guarding` (mark and 503); a rebuild wants to keep going over the roots
     * that ARE there, which is this.
     */
    private fun classifyScanFailure(source: Source, failure: Exception): SourceScan? {
        val root = source.root.name
        if (rootLoss.markIfGone(root, source.store)) {
            return skipAndCarry(root, "it vanished while being scanned (${failure.message})")
        }
        return skipOnLiveFailure(source, failure)
    }

    /**
     * A LIVE root whose scan failed: fail THAT root's pass, not the whole rebuild. The old rethrow escaped the
     * per-root loop, so one unreadable subdirectory in one extra root failed every root's pass - and at boot,
     * where `serve()` calls [rebuild] uncaught, it killed the server outright with a stack trace instead of
     * serving the roots that were perfectly fine. The root is deliberately NOT marked unavailable: it is THERE,
     * a permission or a corrupt repo is fixed in place, and sticky-until-restart would prescribe a restart
     * nobody needs. So it keeps its last-good section (nothing is deleted for it - it is not in `scannedRoots`),
     * it keeps serving, and the next pass retries it. The WARN carries the DIRECTORY, because that is the datum
     * an operator acts on.
     */
    private fun skipOnLiveFailure(source: Source, failure: Exception): SourceScan? {
        val where = source.root.localPath?.let { " at $it" }.orEmpty()
        logger.warn(failure) {
            "root '${source.root.name}'$where is still there but its scan FAILED (${failure.message}); skipping it and " +
                "carrying its last-good section forward - NOTHING is deleted for it, the other roots still index, and the " +
                "next pass retries it"
        }
        return null
    }

    /** The loss is already published; this is the skip. Marking is what stops the carried section from being SERVED as live. */
    private fun skipAndCarry(root: RootName, detail: String): SourceScan? {
        logger.warn {
            "root '$root' is no longer available ($detail); skipping its scan and carrying its last-good section " +
                "forward - NOTHING is deleted for it, and it will serve 503 until it is restored and the server restarted"
        }
        return null
    }

    /** Scans ONE source end-to-end (files, frontmatter, per-root URLs, batched last-commits). */
    private fun scan(source: Source): SourceScan {
        val root = source.root.name
        val scan = source.store.scan()

        val drafts = scan.files
            .filter { it.path.name.endsWith(".md") }
            .sortedBy { it.path.value }
            .map { file ->
                // CLASSIFIED, not `checkNotNull`: a plain null read cannot tell a page deleted mid-scan (which
                // must still fail the rebuild loudly - the watcher event for that same deletion drives the
                // converging pass) from the whole ROOT going away (which must mark + skip + carry). The old
                // checkNotNull raised an IllegalStateException that walked straight past the classifier above,
                // leaving the root AVAILABLE and its carried section being served - the D5 lie.
                val bytes = when (val read = source.store.readClassified(file.path)) {
                    is ContentRead.Bytes -> read.bytes
                    ContentRead.RootDown -> throw RootUnavailable(root, UnavailableCause.VANISHED)
                    ContentRead.Absent -> error("scanned page vanished before read: ${file.path.value}")
                }
                Draft(file, bytes, frontmatterParser.parse(bytes))
            }
        val assets = scan.files.filterNot { it.path.name.endsWith(".md") }.map { it.path }.toSet()

        // Per-root URL construction: the builder is pure and per-tree, so per-root URL uniqueness
        // falls out of calling it once per source (§A4 holds per root, not across roots).
        val urls = CanonicalUrlBuilder.build(
            root = root,
            pages = drafts.map { CanonicalUrlBuilder.PageInput(it.file.path, it.file.rawName, it.frontmatter.scalar("slug")) },
            folders = scan.folders,
        )

        // ONE batched last-commit read per source (fix-C corollary): never one query per page.
        // NoOp → empty map → every commit null off Git (the frozen-golden invariant). The map is
        // keyed by the same TreePath the draft carries; an uncommitted page is simply absent (→ null).
        // It is also the LAST thing that can raise root loss here, which is why the issues below are
        // handed BACK rather than recorded: nothing this scan found is persisted until all of it is in hand.
        val commits = source.history.lastCommits(drafts.map { it.file.path })
        return SourceScan(
            root = root,
            drafts = drafts,
            folders = scan.folders,
            assets = assets,
            urls = urls,
            commits = commits,
            issues = scan.issues.map { it.toIdentityIssue(root) } + urls.issues,
            complete = scan.complete,
        )
    }

    /** The URL-complete, render-empty skeleton page for one draft. */
    private fun provisionalPage(scan: SourceScan, draft: Draft, identityOf: Identity): IndexedPage {
        val assignment = scan.urls.byPage.getValue(draft.file.path)
        return IndexedPage(
            id = identityOf.id,
            root = scan.root,
            path = draft.file.path,
            slug = assignment.slug,
            urlPath = assignment.urlPath,
            title = draft.file.path.stem,
            frontmatter = draft.frontmatter,
            materialized = identityOf.materialized,
            // Captured from the one read, alongside everything else the page serves: the
            // payload a request answers with is coherent BY CONSTRUCTION (see IndexedPage doc).
            markdown = String(draft.bytes, Charsets.UTF_8),
            contentHash = citations.contentHash(draft.bytes),
            commit = scan.commits[draft.file.path]?.sha,
            html = "",
            headings = emptyList(),
            links = emptyList(),
            sections = emptyList(),
        )
    }

    /**
     * §5.2 identity over the in-hand bytes — the same precedence/duplicate seam as `AdoptionPass`
     * RECORD, run ONCE globally across all sources in rank-then-path order so the registry-order
     * winner is always claimed first.
     *
     * **RESOLVE THE WHOLE CORPUS, THEN BIND IT** - the `AdoptionPass` two-phase split (D19), for the same
     * reason and now literally the same seam. Binding INLINE, as this used to, made the D16/D17 loser issue
     * UNRECORDABLE for one specific loser: a page whose identity lives in `id_map` ONLY (no frontmatter id of
     * its own). The winner's key-complete bind DELETES that row on its way through, so when the loser's own
     * draft came up for resolution its `mappedId` read back null - and a page with no frontmatter id and no
     * mapping is not a duplicate, it is a VIRGIN PAGE. It minted a fresh id, silently, and its `/p/{id}`
     * permalink moved to another page in another root with no `CrossRootDuplicateId` issue recorded anywhere.
     * A durable permalink reassignment with no record is precisely the outcome D16/D17's loser-behalf issue
     * recording exists to make impossible.
     *
     * Resolving first fixes it at the root: every draft's `mappedId` is read against the id_map as it stood
     * BEFORE this pass touched it, so the beaten owner still sees the contested id, `PageIdentityService`
     * reaches its owner check on the id_map arm (the arm its doc says an inline-binding pass can never reach),
     * and the loser reassigns WITH its issue. The binds then replay the resolved plan in the same rank-then-path
     * order, so the winner's key-complete bind still lands before the loser's row is rewritten.
     */
    private fun resolveIdentities(scans: List<SourceScan>, scannedRoots: Set<RootName>): Map<RootedPath, Identity> {
        val scannedLive = scans.flatMap { scan -> scan.drafts.map { RootedPath(scan.root, it.file.path) } }.toSet()
        val claimed = HashMap<PageId, RootedPath>()
        val resolved = LinkedHashMap<RootedPath, PageIdentityService.Assignment>() // rank-then-path = the bind order
        for (scan in scans) {
            for (draft in scan.drafts) {
                val path = RootedPath(scan.root, draft.file.path)
                val assignment = identity.resolve(
                    path = path,
                    rawFrontmatterId = patcher.readIdValue(draft.bytes),
                    // Read against the PRE-PASS id_map (nothing has been bound yet), which is what lets a beaten
                    // id_map-only owner still see the contested id and lose it with an issue rather than silently.
                    mappedId = idMap.find(path)?.id,
                    // Within-run claims first, then id_map bindings classified by the shared D16
                    // rule: scanned roots live-iff-on-disk, configured-but-unscanned untouchable,
                    // detached not an owner at all.
                    ownerOf = { id ->
                        claimed[id] ?: idMap.pathOf(id)?.takeIf { BindingVisibility.isLive(it, scannedLive, scannedRoots, registeredRoots) }
                    },
                    // ...and the OTHER half of D16: only an owner in a root THIS pass scanned can lose the rank
                    // contest. [scannedRoots] is this pass's set, never the wired source list - a root that was
                    // SKIPPED (unavailable) classifies exactly like one that was never wired, because the pass
                    // knows precisely as much about either: nothing. Winning here deletes the owner's binding via
                    // the key-complete bind, and that is only ever safe against a root we just looked at, whose
                    // loser page re-resolves later in THIS pass (rank order) and reassigns itself, recording its
                    // own issue as it goes.
                    supersedable = { owner -> BindingVisibility.isSupersedable(owner, scannedRoots) },
                )
                claimed[assignment.id] = path
                resolved[path] = assignment
            }
        }

        // The plan is checked BEFORE it is made durable, because a durable duplicate cannot be walked back: the
        // winner's key-complete bind sweeps the loser's row, the loser walks off with the permalink, and
        // `PageIndex`'s own `byId` uniqueness check throws only AFTER all of that has landed - so every
        // subsequent boot dies in the same place, on rows nothing will now rewrite. A failed pass changes
        // nothing and carries the last-good snapshot; that is strictly the better failure.
        requireDistinctIds(resolved.mapValues { (_, assignment) -> assignment.id })

        val identities = HashMap<RootedPath, Identity>()
        for ((path, assignment) in resolved) {
            val materialized = assignment.source == PageIdentityService.Source.FRONTMATTER
            // Rank-then-path order (the map's insertion order): the winner's key-complete bind sweeps the loser's
            // stale row BEFORE the loser rebinds itself, so no page ever reads back an identity this pass has
            // already re-awarded. The ISSUE lands with the bind that supersedes it, never after it or not at all.
            idMap.bind(path, assignment.id, materialized = materialized)
            assignment.issue?.let(idMap::record)
            identities[path] = Identity(assignment.id, materialized)
        }
        return identities
    }

    /** §A4 alias semantics for one rebuild: move detection, `redirect_from`, then the shadow sweep. */
    private fun recordAliases(previousUrlPaths: Map<PageId, PreviousUrl>, snapshot: PageIndex) {
        val liveCanonicals = snapshot.byUrlPath.keys

        // Move/rename/slug-change detection: a known id whose canonical (root, URL path) changed
        // since the previous snapshot leaves the old rooted path behind as an alias — unless a live
        // canonical now claims it (live always wins; nothing to register, the conflict is recorded
        // instead). The alias lands in the OLD root's namespace.
        //
        // The previous paths come from the previous published snapshot — or, on the first rebuild
        // after startup, from the persisted §B3 checkpoint, which closes the Phase-1 down-time-move
        // gap for MATERIALIZED pages (the id travels in the file). An unmaterialized page moved
        // while down still gets a fresh id and no alias: the accepted §5.2 path-keyed-identity
        // trade-off, restated, not fixed here.
        for (page in snapshot.pages) {
            val previous = previousUrlPaths[page.id] ?: continue
            val oldUrlPath = previous.urlPath ?: continue
            val old = RootedPath(previous.root, oldUrlPath)
            if (old == page.urlPath?.let { RootedPath(page.root, it) }) continue
            if (old in liveCanonicals) {
                idMap.record(
                    IdentityIssue.RedirectConflict(
                        root = old.root,
                        path = old.path,
                        message = "move alias for page ${page.id} dropped: shadowed by a live canonical path",
                    ),
                )
            } else {
                aliasRegistry.register(old, page.id)
            }
        }

        // redirect_from registration: file-path values converted through the same URL construction,
        // in the declaring page's root namespace.
        for (page in snapshot.pages) {
            for (raw in page.frontmatter.strings("redirect_from")) {
                val target = CanonicalUrlBuilder.redirectUrlPath(raw)
                if (target == null) {
                    logger.warn { "ignoring unusable redirect_from '$raw' on ${page.path.value}" }
                    continue
                }
                registerRedirect(RootedPath(page.root, target), page, liveCanonicals)
            }
        }

        // Shadow sweep: an alias persisted earlier that a live canonical path claims now is dropped.
        for (canonical in liveCanonicals) {
            aliasRegistry.dropShadowed(canonical)?.let { dropped ->
                idMap.record(
                    IdentityIssue.RedirectConflict(
                        root = canonical.root,
                        path = canonical.path,
                        message = "alias to page ${dropped.id} dropped: shadowed by a live canonical path",
                    ),
                )
            }
        }
    }

    /** Registers one `redirect_from` alias unless a live canonical or another page's alias claims it. */
    private fun registerRedirect(target: RootedPath, page: IndexedPage, liveCanonicals: Set<RootedPath>) {
        val existing = aliasRegistry.find(target)
        when {
            target in liveCanonicals -> idMap.record(
                IdentityIssue.RedirectConflict(
                    root = target.root,
                    path = target.path,
                    message = "redirect_from of ${page.path.value} ignored: a live canonical path claims it",
                ),
            )
            existing != null && existing != page.id -> idMap.record(
                IdentityIssue.RedirectConflict(
                    root = target.root,
                    path = target.path,
                    message = "redirect_from of ${page.path.value} ignored: already an alias of page $existing",
                ),
            )
            existing == null -> aliasRegistry.register(target, page.id)
            // existing == page.id: already registered — nothing to do.
        }
    }

    private fun ScanIssue.toIdentityIssue(root: RootName): IdentityIssue = when (this) {
        // The loser's raw name passes through verbatim — building a TreePath from it would
        // NFC-normalize it back into keptPath, erasing the one value that distinguishes the loser.
        is ScanIssue.PathCollision -> IdentityIssue.PathCollision(root = root, keptPath = path, loserRawName = loserRawName)
    }

    private val TreePath.stem: String get() = name.removeSuffix(".md")

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
