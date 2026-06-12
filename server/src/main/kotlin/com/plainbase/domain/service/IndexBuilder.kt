package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.repository.IdMapRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicReference

/**
 * Chunk 5's index pass (caching decision §C4): scan → frontmatter → identity → URLs → render
 * metadata → one immutable [PageIndex], published atomically. The full scan runs at startup and on
 * rescan (the chunk-6 admin route calls [rebuild]); watcher-driven incremental updates are Phase 2.
 *
 * **One pass:** each file's bytes are read exactly once ([ContentStore.read]), each page's
 * frontmatter values are parsed exactly once ([FrontmatterParser], over the already-read bytes —
 * render only re-detects the block boundary, never the values), and each page is rendered exactly
 * once ([MarkdownRenderer.render]). The same in-hand bytes also yield the page's verbatim
 * `markdown` and its content hash ([CitationFactory]), so the read path never touches disk for
 * pages. The parse runs up front because URL construction needs every page's `slug` BEFORE any
 * page renders — rendered links embed other pages' canonical URLs — so render happens against a
 * URL-complete skeleton snapshot built first.
 *
 * **Safe publication, no `@Volatile`:** the new snapshot is built entirely off to the side and
 * published with a single [AtomicReference.set]; [current] readers always observe a complete,
 * internally consistent, deeply immutable [PageIndex] — old or new, never torn — and stay
 * lock-free. [rebuild] itself is `@Synchronized` (rescans are rare): two concurrent rebuilds
 * could otherwise publish out of order — the earlier-scanned one finishing later would regress
 * [current] to a stale world (a classic lost update).
 *
 * **Move aliases (§A4, deferred from 4b):** a known id whose canonical URL path changed since the
 * previously published snapshot leaves its old path behind as a `url_alias` row; the registry maps
 * paths straight to page ids, so chains collapse on write (one hop after any number of moves).
 * `redirect_from` frontmatter registers through the same construction; a live canonical path
 * always shadows an alias (dropped, with a recorded `redirect_conflict` issue).
 *
 * Identity binding mirrors `AdoptionPass` RECORD semantics over the in-hand bytes (zero content
 * writes): id_map rows plus issues, pages in path order so duplicate resolution is deterministic.
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
    private val contentStore: ContentStore,
    private val frontmatterParser: FrontmatterParser,
    private val rendererFactory: (PageIndexView) -> MarkdownRenderer,
    private val identity: PageIdentityService,
    private val patcher: FrontmatterPatcher,
    private val idMap: IdMapRepository,
    private val aliasRegistry: UrlAliasRegistry,
    private val citations: CitationFactory,
    private val listeners: List<PublicationListener> = emptyList(),
) {

    /** Notified with each newly published snapshot — synchronously, inside the serialized rebuild (§B4). */
    fun interface PublicationListener {
        fun published(snapshot: PageIndex)
    }

    private val holder = AtomicReference(PageIndex.EMPTY)

    /** The published snapshot — always complete and consistent ([PageIndex.EMPTY] before the first build). */
    val current: PageIndex get() = holder.get()

    /** Runs the full pass and atomically publishes (and returns) the new snapshot (serialized — see class doc). */
    @Synchronized
    fun rebuild(): PageIndex {
        val previous = holder.get()
        val scan = contentStore.scan()
        scan.issues.forEach { idMap.record(it.toIdentityIssue()) }

        val drafts = scan.files
            .filter { it.path.name.endsWith(".md") }
            .sortedBy { it.path.value }
            .map { file ->
                val bytes = checkNotNull(contentStore.read(file.path)) { "scanned page vanished before read: ${file.path.value}" }
                Draft(file, bytes, frontmatterParser.parse(bytes))
            }
        val assets = scan.files.filterNot { it.path.name.endsWith(".md") }.map { it.path }.toSet()

        val identities = resolveIdentities(drafts)
        val urls = CanonicalUrlBuilder.build(
            pages = drafts.map { CanonicalUrlBuilder.PageInput(it.file.path, it.file.rawName, it.frontmatter.scalar("slug")) },
            folders = scan.folders,
        )
        urls.issues.forEach(idMap::record)

        // Render against a URL-complete skeleton of the final snapshot type: identity and URLs are
        // already final, render fields are filled by the single render below.
        val provisional = drafts.map { draft ->
            val identityOf = identities.getValue(draft.file.path)
            val assignment = urls.byPage.getValue(draft.file.path)
            IndexedPage(
                id = identityOf.id,
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
                html = "",
                headings = emptyList(),
                links = emptyList(),
                sections = emptyList(),
            )
        }
        val renderer = rendererFactory(PageIndex(provisional, scan.folders, assets))
        val pages = provisional.zip(drafts) { page, draft ->
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
        }

        val snapshot = PageIndex(pages, scan.folders, assets)
        recordAliases(previous, snapshot)
        holder.set(snapshot)
        logger.info {
            "indexed ${pages.size} page(s), ${assets.size} asset(s), ${scan.folders.size} folder(s); " +
                "${pages.count { it.urlPath == null }} excluded from path space"
        }
        notifyPublished(snapshot)
        return snapshot
    }

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

    private class Identity(
        val id: PageId,
        val materialized: Boolean,
    )

    /** §5.2 identity over the in-hand bytes — the same precedence/duplicate seam as `AdoptionPass` RECORD. */
    private fun resolveIdentities(drafts: List<Draft>): Map<TreePath, Identity> {
        val livePaths = drafts.map { it.file.path }.toSet()
        val claimed = HashMap<PageId, TreePath>()
        return drafts.associate { draft ->
            val path = draft.file.path
            val assignment = identity.resolve(
                path = path,
                rawFrontmatterId = patcher.readIdValue(draft.bytes),
                mappedId = idMap.find(path)?.id,
                // Within-run claims first, then live id_map bindings: a binding whose path is gone
                // from the tree is a moved file, not a duplicate owner (the AdoptionPass rule).
                ownerOf = { id -> claimed[id] ?: idMap.pathOf(id)?.takeIf(livePaths::contains) },
            )
            claimed[assignment.id] = path
            val materialized = assignment.source == PageIdentityService.Source.FRONTMATTER
            idMap.bind(path, assignment.id, materialized = materialized)
            assignment.issue?.let(idMap::record)
            path to Identity(assignment.id, materialized)
        }
    }

    /** §A4 alias semantics for one rebuild: move detection, `redirect_from`, then the shadow sweep. */
    private fun recordAliases(previous: PageIndex, snapshot: PageIndex) {
        val liveCanonicals = snapshot.byUrlPath.keys

        // Move/rename/slug-change detection: a known id whose canonical URL path changed since the
        // previous snapshot leaves the old path behind as an alias — unless a live canonical now
        // claims it (live always wins; nothing to register, the conflict is recorded instead).
        //
        // Deliberate Phase-1 limitation (§A4, owner-ratified): only IN-PROCESS rescans see a move.
        // A move performed while the server is down compares against PageIndex.EMPTY at the next
        // startup — no previous urlPath, and bind() supersedes the old id_map row — so the old URL
        // 404s with no alias. Deferred to the Phase 2 watcher, which sees move events directly.
        for (page in snapshot.pages) {
            val oldUrlPath = previous.byId[page.id]?.urlPath ?: continue
            if (oldUrlPath == page.urlPath) continue
            if (oldUrlPath in liveCanonicals) {
                idMap.record(
                    IdentityIssue.RedirectConflict(
                        path = oldUrlPath,
                        message = "move alias for page ${page.id} dropped: shadowed by a live canonical path",
                    ),
                )
            } else {
                aliasRegistry.register(oldUrlPath, page.id)
            }
        }

        // redirect_from registration: file-path values converted through the same URL construction.
        for (page in snapshot.pages) {
            for (raw in page.frontmatter.strings("redirect_from")) {
                val target = CanonicalUrlBuilder.redirectUrlPath(raw)
                if (target == null) {
                    logger.warn { "ignoring unusable redirect_from '$raw' on ${page.path.value}" }
                    continue
                }
                registerRedirect(target, page, liveCanonicals)
            }
        }

        // Shadow sweep: an alias persisted earlier that a live canonical path claims now is dropped.
        for (canonical in liveCanonicals) {
            aliasRegistry.dropShadowed(canonical)?.let { dropped ->
                idMap.record(
                    IdentityIssue.RedirectConflict(
                        path = canonical,
                        message = "alias to page ${dropped.id} dropped: shadowed by a live canonical path",
                    ),
                )
            }
        }
    }

    /** Registers one `redirect_from` alias unless a live canonical or another page's alias claims it. */
    private fun registerRedirect(target: TreePath, page: IndexedPage, liveCanonicals: Set<TreePath>) {
        val existing = aliasRegistry.find(target)
        when {
            target in liveCanonicals -> idMap.record(
                IdentityIssue.RedirectConflict(
                    path = target,
                    message = "redirect_from of ${page.path.value} ignored: a live canonical path claims it",
                ),
            )
            existing != null && existing != page.id -> idMap.record(
                IdentityIssue.RedirectConflict(
                    path = target,
                    message = "redirect_from of ${page.path.value} ignored: already an alias of page $existing",
                ),
            )
            existing == null -> aliasRegistry.register(target, page.id)
            // existing == page.id: already registered — nothing to do.
        }
    }

    private fun ScanIssue.toIdentityIssue(): IdentityIssue = when (this) {
        // The loser's raw name passes through verbatim — building a TreePath from it would
        // NFC-normalize it back into keptPath, erasing the one value that distinguishes the loser.
        is ScanIssue.PathCollision -> IdentityIssue.PathCollision(keptPath = path, loserRawName = loserRawName)
    }

    private val TreePath.stem: String get() = name.removeSuffix(".md")

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
