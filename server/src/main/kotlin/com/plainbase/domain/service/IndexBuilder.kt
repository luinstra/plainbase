@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
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
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Chunk 5's index pass (caching decision §C4; N root sources since multi-root C2, ADR-0011):
 * scan → frontmatter → identity → URLs → render metadata → one immutable [PageIndex] of per-root
 * sections, published atomically. The full scan runs at startup and on rescan (the chunk-6 admin
 * route calls [rebuild]); watcher-driven incremental updates are Phase 2. The C2 runtime wiring
 * passes exactly one source (main); the N-source machinery is exercised by tests until C4 widens
 * the wiring.
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
 * rank order, under the [PageIdentityService] cross-root winner policy; a binding's liveness is
 * classified by the shared [BindingVisibility] rule over the scanned/registered root sets (D16),
 * so a partial-visibility pass never supersedes a configured-but-unscanned root's binding except
 * as the deterministic rank-contest outcome. The two D17 execution invariants live as comments at
 * their enforcement points in [rebuild]/[resolveIdentities].
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
    private val listeners: List<PublicationListener> = emptyList(),
    private val searchIndexer: SearchIndexer? = null,
) {

    /** One root's inputs: its topology entry, its content tree, and its history. */
    data class Source(
        val root: Root,
        val store: ContentStore,
        val history: HistoryProvider,
    )

    /** Notified with each newly published snapshot — synchronously, inside the serialized rebuild (§B4). */
    fun interface PublicationListener {
        fun published(snapshot: PageIndex)
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

    private val scannedRoots: Set<RootName> = sourcesByRoot.keys

    private val holder = AtomicReference(PageIndex.EMPTY)

    /** The published snapshot — always complete and consistent ([PageIndex.EMPTY] before the first build). */
    val current: PageIndex get() = holder.load()

    /** Runs the full pass and atomically publishes (and returns) the new snapshot (serialized — see class doc). */
    @Synchronized
    fun rebuild(): PageIndex {
        val previous = holder.load()
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
        val scans = sources.map { scan(it) }

        val identities = resolveIdentities(scans)

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
        val sections = scans.zip(provisionalSections) { scan, section ->
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

        val snapshot = PageIndex(sections)
        recordAliases(previousUrlPaths, snapshot)
        holder.store(snapshot)
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
        notifyPublished(snapshot)
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
     */
    @Synchronized
    fun rebuildSearchIndex(): Int {
        val indexer = requireNotNull(searchIndexer) { "rebuildSearchIndex() needs a SearchIndexer; none was wired into this IndexBuilder" }
        val snapshot = holder.load()
        indexer.rebuild(snapshot)
        return snapshot.pages.size
    }

    /**
     * The MANAGE-gated reindex entry (A3): the admin `reindex` route + the `plainbase reindex` CLI reach the
     * engine generation-swap ONLY through this thin wrapper, which requires a [ManageGrant]. The no-arg
     * [rebuildSearchIndex] stays the internal surface (same gain-a-param-keep-the-logic shape as [rebuild]).
     */
    fun rebuildSearchIndex(@Suppress("UNUSED_PARAMETER") grant: ManageGrant): Int = rebuildSearchIndex()

    /**
     * Targeted single-page reindex (PB-WRITE-1 §B1 fix C): re-reads + re-renders ONLY [pageId]'s page,
     * publishes a snapshot identical to the current one except for that page (its own root's section
     * rebuilt, every other section riding through untouched), and upserts that ONE page into search via
     * [SearchIndexer.syncPage]. O(changed-page) END-TO-END — render O(1), search O(1) (single-page
     * upsert, NOT the corpus-wide [SearchIndexer.sync] diff), checkpoint O(0) (skipped). Full [rebuild]
     * stays the startup/admin/watcher path. Shares the rebuild monitor, so a watcher rebuild never
     * interleaves. Bytes and history come from the page's OWN root's source.
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
     * THROWS [IllegalStateException] if [pageId] is absent or its file is unreadable on the SAVE path:
     * the CAS just wrote those bytes, so a missing page is a real invariant violation, never a silent
     * success. `WritePipeline.reconcileDirtyPages` tolerates a vanished page at its
     * OWN call site, never here.
     */
    @Synchronized
    fun reindex(pageId: PageId): PageIndex {
        val previous = holder.load()
        val target = previous.byId[pageId]
            ?: error("reindex($pageId): page not in the published snapshot — a save-path invariant violation")
        val source = sourcesByRoot[target.root]
            ?: error("reindex($pageId): no source for root '${target.root}' - the snapshot outran this builder's wiring")
        val bytes = source.store.read(target.path)
            ?: error("reindex($pageId): ${target.path.value} unreadable just after a CAS write")
        val parsed = frontmatterParser.parse(bytes)
        val rendered = rendererFactory(previous.view(target.root)).render(target.path, bytes)
        // One genuinely O(1) last-commit lookup for just this page (D-3, reversed by re-review P2-1): a
        // BOUNDED `git log --max-count=1 -- path`, NEVER `lastCommits` — which has no cap and buffers the
        // page's FULL history before parsing, so for a heavily-edited page every save/reconcile would read
        // the whole history (unbounded; can time out / null the commit). `log(path, 1)` shares `rebuild`'s
        // first-parent attribution, so the citation SHA stays consistent between the two paths.
        val commit = source.history.log(target.path, limit = 1).firstOrNull()?.sha
        val reindexed = target.copy(
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
        val snapshot = PageIndex(
            previous.sections.map { section ->
                if (section.root == target.root) {
                    section.copy(pages = section.pages.map { if (it.id == pageId) reindexed else it })
                } else {
                    section
                }
            },
        )
        holder.store(snapshot)
        logger.info { "reindexed page ${pageId.value} (${target.path.value}); ${snapshot.pages.size} page(s) published" }
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

    /** §B4 listener exception policy: contain and log — the publish stands, the remaining listeners still run. */
    private fun notifyPublished(snapshot: PageIndex) {
        listeners.forEach { listener ->
            try {
                listener.published(snapshot)
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

    /** One source's completed scan: drafts in path order, URLs assigned, last-commits batched. */
    private class SourceScan(
        val root: RootName,
        val drafts: List<Draft>,
        val folders: List<ContentFolder>,
        val assets: Set<TreePath>,
        val urls: CanonicalUrlBuilder.Result,
        val commits: Map<TreePath, Commit>,
    )

    private class Identity(
        val id: PageId,
        val materialized: Boolean,
    )

    /** Scans ONE source end-to-end (files, frontmatter, per-root URLs, batched last-commits). */
    private fun scan(source: Source): SourceScan {
        val root = source.root.name
        val scan = source.store.scan()
        scan.issues.forEach { idMap.record(it.toIdentityIssue(root)) }

        val drafts = scan.files
            .filter { it.path.name.endsWith(".md") }
            .sortedBy { it.path.value }
            .map { file ->
                val bytes = checkNotNull(source.store.read(file.path)) { "scanned page vanished before read: ${file.path.value}" }
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
        urls.issues.forEach(idMap::record)

        // ONE batched last-commit read per source (fix-C corollary): never one query per page.
        // NoOp → empty map → every commit null off Git (the frozen-golden invariant). The map is
        // keyed by the same TreePath the draft carries; an uncommitted page is simply absent (→ null).
        val commits = source.history.lastCommits(drafts.map { it.file.path })
        return SourceScan(root, drafts, scan.folders, assets, urls, commits)
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
     * winner is always claimed (and bound) first.
     */
    private fun resolveIdentities(scans: List<SourceScan>): Map<RootedPath, Identity> {
        val scannedLive = scans.flatMap { scan -> scan.drafts.map { RootedPath(scan.root, it.file.path) } }.toSet()
        val claimed = HashMap<PageId, RootedPath>()
        val identities = HashMap<RootedPath, Identity>()
        for (scan in scans) {
            for (draft in scan.drafts) {
                val path = RootedPath(scan.root, draft.file.path)
                val assignment = identity.resolve(
                    path = path,
                    // D17 execution invariant (a), read side: mappedId is read lazily at THIS
                    // draft's resolve, AFTER any earlier winner's key-complete bind has already
                    // superseded a stale row - so a beaten prior owner sees null, never the
                    // contested id.
                    rawFrontmatterId = patcher.readIdValue(draft.bytes),
                    mappedId = idMap.find(path)?.id,
                    // Within-run claims first, then id_map bindings classified by the shared D16
                    // rule: scanned roots live-iff-on-disk, configured-but-unscanned untouchable,
                    // detached supersedable.
                    ownerOf = { id ->
                        claimed[id] ?: idMap.pathOf(id)?.takeIf { BindingVisibility.isLive(it, scannedLive, scannedRoots, registeredRoots) }
                    },
                )
                claimed[assignment.id] = path
                val materialized = assignment.source == PageIdentityService.Source.FRONTMATTER
                // D17 execution invariant (a), write side: binds land INLINE per draft during
                // resolution, never batched afterward - batching would let a loser read its own
                // stale binding back as the contested id. The loser-behalf issue rides the bind's
                // OWN transaction (the port's D16 atomicity contract).
                idMap.bind(path, assignment.id, materialized = materialized, supersededOwnerIssue = supersededOwnerIssue(path, assignment))
                assignment.issue?.let(idMap::record)
                identities[path] = Identity(assignment.id, materialized)
            }
        }
        return identities
    }

    /**
     * D16 outcome two: when the pass's page WINS the rank contest against an owner in a
     * registered-but-UNSCANNED root, the key-complete bind necessarily deletes the foreign row -
     * so the PASS records the loser-behalf issue at supersession time, in the bind's own
     * transaction, with the same natural key the loser itself would record at its next full
     * rebuild (the UNIQUE upsert dedups). A detached (unregistered) owner's supersession stays
     * issue-free by design: the boot WARN is its visibility (D2).
     */
    private fun supersededOwnerIssue(path: RootedPath, assignment: PageIdentityService.Assignment): IdentityIssue? {
        val owner = assignment.supersededOwner ?: return null
        if (owner.root !in registeredRoots || owner.root in scannedRoots) return null
        return IdentityIssue.CrossRootDuplicateId(id = assignment.id, kept = path, reassigned = owner)
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
