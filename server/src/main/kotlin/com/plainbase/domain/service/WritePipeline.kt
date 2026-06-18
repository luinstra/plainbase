package com.plainbase.domain.service

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.PageId
import com.plainbase.domain.render.HeadingSlugger
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Stage
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The serialized write pipeline (PB-WRITE-1, chunk W1): the single funnel for a content-mutating
 * save. Every [write] runs under ONE `@Synchronized` monitor (the house [IndexBuilder] idiom, never
 * `@Volatile`); the disk write is a single atomic, disk-authoritative compare-and-swap, and the index
 * update is a targeted O(changed-page) reindex. Pure domain — no framework imports.
 *
 * The critical section, in order:
 *  0. **Edit-classification guard** (debate MUST-FIX 1): a buffer that changes `id`/`slug`/
 *     `redirect_from` is a RENAME, not an edit — REJECTED with [WriteOutcome.UnsupportedEdit], never
 *     silently fixed and never rebuild()-fallen-back. This is what makes the targeted reindex's
 *     skip-the-checkpoint sound by construction.
 *  1. **Write-ahead dirty mark** (debate MUST-FIX 5): the page is marked dirty with the about-to-be
 *     written bytes' hash BEFORE the disk write, so a crash between the write and the post-steps is
 *     recoverable.
 *  2. **Atomic, disk-authoritative CAS** ([ContentStore.compareAndSwapWrite], debate MUST-FIX 2):
 *     one operation resolves + rechecks identity + renames. No read-then-write TOCTOU window.
 *  3. **Targeted reindex** ([IndexBuilder.reindex], debate MUST-FIX 3/4): one page re-rendered and
 *     single-page search upsert; THROWS on a vanished save-path page (caught → [WriteOutcome
 *     .WrittenButUnindexed]).
 *  4. **Clear the mark** only after every post-step succeeds.
 *
 * Lock-ordering topology (Resolution 5): the pipeline monitor calls into the [IndexBuilder] monitor
 * (via [IndexBuilder.reindex]) — strictly one-directional, no back-edge, so no deadlock with a
 * concurrent watcher `rebuild()` (which takes only the IndexBuilder monitor).
 */
class WritePipeline(
    private val contentStore: ContentStore,
    private val indexBuilder: IndexBuilder,
    private val citations: CitationFactory,
    private val frontmatterParser: FrontmatterParser,
    private val dirtyPages: DirtyPageRepository,
    private val idMap: IdMapRepository,
    private val aliasRegistry: UrlAliasRegistry,
    private val historyHook: WriteHistoryHook = WriteHistoryHook { _, _ -> },
) {

    @Synchronized
    fun write(intent: WriteIntent): WriteOutcome {
        // (0) Edit-classification guard — a rename is rejected, never half-applied (MUST-FIX 1).
        classifyEdit(intent)?.let { return it }

        // Capture any prior dirty row (a real WrittenButUnindexed recovery record from an earlier
        // attempt) BEFORE the write-ahead mark overwrites it. A NO-WRITE outcome restores it rather
        // than clobbering/clearing it, so a not-actually-written attempt never destroys a prior record.
        val prior = dirtyPages.get(intent.pageId)

        // (1) Write-ahead: mark dirty with the new bytes' hash BEFORE the disk write (MUST-FIX 5).
        dirtyPages.mark(intent.pageId, intent.path, expectedHash = citations.contentHash(intent.bytes), stage = Stage.WRITING)

        // (2) Atomic, disk-authoritative CAS (fix A + MUST-FIX 2).
        return when (val cas = contentStore.compareAndSwapWrite(intent.path, intent.baseHash, intent.bytes, citations::contentHash)) {
            // Nothing was written for these three — restore the prior recovery record, or clear.
            is CasResult.Deleted -> {
                restoreOrClear(intent.pageId, prior)
                conflict(intent, reason = "page_deleted", current = null)
            }
            is CasResult.Mismatch -> {
                restoreOrClear(intent.pageId, prior)
                conflict(intent, reason = "content_changed", current = cas.currentBytes)
            }
            is CasResult.Unreadable -> {
                restoreOrClear(intent.pageId, prior)
                WriteOutcome.Unreadable(cas.cause)
            }
            is CasResult.Written -> commitAndIndex(intent, newHash = cas.newHash)
        }
    }

    /**
     * One new-page creation (PB-WRITE-1, chunk W2), on the SAME monitor as [write] so a create
     * serializes with every edit and every watcher rebuild. No CAS / edit-classification: a create has
     * no prior content to classify and no `base_hash` — the collision check is the filesystem's own
     * exclusive create ([ContentStore.createExclusive]), a pipeline outcome, never a route pre-check.
     *
     * The critical section mirrors [write]'s write-ahead-then-post-steps shape:
     *  0. **Canonical-URL collision guard** (round-8/10/11): the prospective page/folder canonical URL,
     *     evaluated UNDER this monitor against the FRESH published snapshot, must not be owned by a
     *     DIFFERENT page/folder/live-alias — else a second concurrent URL-colliding create (serialized
     *     after the first's rebuild) would displace it. A hit → [WriteOutcome.SlugConflict], NOTHING
     *     written. This is the race-safe home for the check (not a route pre-check): slugs/URLs are
     *     snapshot-authoritative, so the verdict must be taken under the same lock the rebuild publishes
     *     under, exactly like the CAS for content.
     *  1. write-ahead dirty mark with the about-to-be-written bytes' hash (a fresh pageId has no prior
     *     journal row, so the no-write branches just clear);
     *  2. exclusive create — [CreateResult.Exists] → [WriteOutcome.AlreadyExists]; [CreateResult
     *     .Rejected] → [WriteOutcome.InvalidLocation] (P1 containment); [CreateResult.Unreadable] →
     *     [WriteOutcome.Unreadable]; [CreateResult.Created] → the post-steps;
     *  3. bind identity, run the history hook (no-op until W4), index via a full [IndexBuilder.rebuild]
     *     (its own scan picks up the just-created file, sidestepping the indexed-only read gate and
     *     reusing every collision/alias/checkpoint rule), then a targeted [IndexBuilder.reindex] whose
     *     PROPAGATING single-page search upsert surfaces an FTS-sync failure (P2). A post-step throw is
     *     caught → [WriteOutcome.WrittenButUnindexed] (the bytes ARE on disk, the page IS dirty).
     */
    @Synchronized
    fun create(intent: CreateIntent): WriteOutcome {
        // (0) Canonical-URL collision guard, under the monitor against the fresh snapshot — BEFORE any
        // write or dirty mark, so a no-write conflict never touches the journal at all.
        canonicalUrlCollision(intent)?.let { return WriteOutcome.SlugConflict(it) }

        // (1) Write-ahead: mark dirty with the new bytes' hash BEFORE the disk create. A fresh pageId
        // has no prior recovery row, so a no-write outcome simply clears the mark.
        dirtyPages.mark(intent.pageId, intent.path, expectedHash = citations.contentHash(intent.bytes), stage = Stage.WRITING)

        // (2) Exclusive create (write-if-absent) — collision is a race-safe pipeline outcome, not a pre-check.
        return when (val create = contentStore.createExclusive(intent.path, intent.bytes, citations::contentHash)) {
            is CreateResult.Exists -> {
                dirtyPages.clear(intent.pageId)
                WriteOutcome.AlreadyExists(create.path)
            }
            is CreateResult.Rejected -> {
                dirtyPages.clear(intent.pageId) // P1: an uncreatable location — NOTHING written
                WriteOutcome.InvalidLocation(create.reason)
            }
            is CreateResult.Unreadable -> {
                dirtyPages.clear(intent.pageId)
                WriteOutcome.Unreadable(create.cause)
            }
            is CreateResult.Created -> createAndIndex(intent, newHash = create.newHash)
            // ParentMissing is produced ONLY by ContentStore.writeAssetExclusive (W3b assets); the page
            // create path uses createExclusive, which creates parents and never returns it. Unreachable here.
            CreateResult.ParentMissing -> error("createExclusive never returns ParentMissing; it is asset-write-only (W3b)")
        }
    }

    /** (3) Post-create steps; the bytes are already durably on disk and the page already marked dirty. */
    private fun createAndIndex(intent: CreateIntent, newHash: String): WriteOutcome =
        try {
            idMap.bind(intent.path, intent.pageId, materialized = true) // the create composed the id INTO frontmatter
            historyHook.commit(intent.path, intent.bytes) // no-op until W4 — the same seam an edit uses
            indexBuilder.rebuild() // re-scans disk; picks up the new file, reuses every collision/alias/URL rule
            // P2: rebuild()'s publication-listener search sync is best-effort (its listener exceptions are
            // swallowed+logged), so a failed FTS sync would otherwise yield a clean 201 with the page
            // missing from search and no retry. A targeted reindex() of the now-published page upserts it
            // via the PROPAGATING SearchIndexer.syncPage — a search-sync failure throws here, so it lands
            // in the catch below as WrittenButUnindexed (the SAME guarantee PUT gives) and the dirty mark
            // is RETAINED for reconcile. Idempotent on success (a second single-page upsert).
            indexBuilder.reindex(intent.pageId)
            dirtyPages.clear(intent.pageId) // every post-step succeeded — clear the write-ahead mark
            WriteOutcome.Written(newHash = newHash, commit = null)
        } catch (e: Exception) {
            // The bytes ARE on disk and the page is ALREADY marked dirty (write-ahead). Leave the mark;
            // the next startup reconciles. The cause is mapped to a structured code at the create route.
            logger.error(e) { "create wrote ${intent.path.value} but a post-write step failed; left dirty for reconcile" }
            WriteOutcome.WrittenButUnindexed(newHash = newHash, cause = e.message ?: e::class.simpleName ?: "unknown")
        }

    /**
     * The first prospective canonical URL the create would claim that a DIFFERENT existing entry already
     * owns (so publishing would displace it or leave the newcomer URL-less), as a `/`-joined string, or
     * null when the whole footprint is free. SNAPSHOT-authoritative and grounded in the same §A4
     * machinery rebuild uses ([CanonicalUrlBuilder]/[HeadingSlugger]); evaluated under the create monitor
     * against the fresh [IndexBuilder.current], so two concurrent URL-colliding creates serialize — the
     * second sees the first's published claim. Same-role per ADR-0002:
     *  - each NEW (not-yet-indexed) FOLDER segment's URL vs existing FOLDER URLs (round 10);
     *  - the new PAGE's URL vs existing PAGE URLs (round 8) and LIVE aliases (a dangling alias whose
     *    target id is absent from the snapshot is ignored — the next rebuild's shadow-sweep drops it,
     *    so it must not permanently block the create).
     *
     * The new page's frontmatter `slug` and the on-disk path come straight from [intent] (the route
     * composed the bytes), so the computed URL is byte-identical to what the page would publish at.
     */
    private fun canonicalUrlCollision(intent: CreateIntent): String? {
        val snapshot = indexBuilder.current
        val folderPath = intent.path.parent
        val slugOverride = frontmatterParser.parse(intent.bytes).scalar("slug")
        val existingFolderUrls = CanonicalUrlBuilder.folderUrlPaths(snapshot.folders)
        val indexedFolderPaths = snapshot.folders.map { it.path }.toSet()
        val folderUrlOwner = existingFolderUrls.entries.mapNotNull { (p, u) -> u?.let { it to p } }.toMap()

        // Walk the ancestor folders root-first, building the URL prefix the way the index does: an indexed
        // ancestor contributes its OWN canonical URL whole (null ⇒ lost-collision subtree → no collision
        // possible, stop); a new ancestor contributes slugify(dir name) and must not displace an existing
        // folder's URL.
        var prefix: TreePath? = null
        for (i in 1..(folderPath?.segments?.size ?: 0)) {
            val ancestor = TreePath.require(folderPath!!.segments.take(i).joinToString("/"))
            if (ancestor in indexedFolderPaths) {
                prefix = existingFolderUrls[ancestor] ?: return null
            } else {
                val segment = HeadingSlugger.slugify(ancestor.name, HeadingSlugger.FOLDER_FALLBACK)
                prefix = prefix?.resolveChild(segment) ?: TreePath.require(segment)
                val owner = folderUrlOwner[prefix]
                if (owner != null && owner != ancestor) return prefix.value
            }
        }

        // The new page's full canonical URL: the (possibly null = root) prefix + the page slug.
        val pageSlug = HeadingSlugger.slugify(slugOverride ?: intent.path.name.removeSuffix(".md"), HeadingSlugger.PAGE_FALLBACK)
        val pageUrl = prefix?.resolveChild(pageSlug) ?: TreePath.require(pageSlug)
        val pageOwner = snapshot.byUrlPath[pageUrl]
        if (pageOwner != null && pageOwner.path != intent.path) return pageUrl.value // page-page
        // Only a LIVE alias blocks: a row pointing at a page id no longer in the snapshot is dangling
        // (the shadow-sweep hasn't dropped it yet) and must not permanently wedge the URL.
        val aliasTarget = aliasRegistry.find(pageUrl)
        if (aliasTarget != null && snapshot.byId[aliasTarget] != null) return pageUrl.value
        return null
    }

    /**
     * A no-write outcome restores any prior dirty row rather than clearing it (MUST-FIX 2 / the
     * dirty-row-clobber fix): the write-ahead [mark][DirtyPageRepository.mark] overwrote it with THIS
     * attempt's hash, but nothing was actually written, so the earlier on-disk-but-unindexed recovery
     * record must survive. With no prior row, the page simply clears (nothing to reconcile).
     */
    private fun restoreOrClear(pageId: PageId, prior: DirtyPage?) {
        if (prior != null) {
            dirtyPages.mark(prior.pageId, prior.path, expectedHash = prior.expectedHash, stage = prior.stage)
        } else {
            dirtyPages.clear(pageId)
        }
    }

    /**
     * Deterministic startup reconciliation of a prior interrupted save (fix H / MUST-FIX 5), once,
     * under the pipeline monitor. It cannot race an in-flight save (the engine is not serving yet); a
     * watcher `rebuild()` may race but is safe by the one-directional lock order (Resolution 5).
     *
     * Drift-skip: a page whose on-disk hash no longer matches the recorded [com.plainbase.domain
     * .repository.DirtyPage.expectedHash] had an external edit land after the crash — do NOT re-commit
     * or re-index the stale intent; leave the mark for an operator. A file gone since the crash is
     * cleared (the startup rebuild already dropped it). A re-thrown step leaves the page dirty for the
     * next boot (idempotent) — never crashing serve().
     */
    @Synchronized
    fun reconcileDirtyPages() {
        val dirty = dirtyPages.all()
        if (dirty.isEmpty()) return
        logger.info { "reconciling ${dirty.size} dirty page(s) from a prior interrupted save" }
        for (page in dirty) {
            try {
                val onDisk = contentStore.read(page.path)
                if (onDisk == null) {
                    dirtyPages.clear(page.pageId) // file gone — nothing to reconcile
                    continue
                }
                if (citations.contentHash(onDisk) != page.expectedHash) {
                    logger.warn {
                        "dirty page ${page.path.value} drifted on disk since the interrupted save; skipping reconcile (left marked)"
                    }
                    continue
                }
                historyHook.commit(page.path, onDisk) // W4: idempotent commit recovery
                indexBuilder.reindex(page.pageId) // reindex tolerated to throw here only if the page truly vanished — caught below
                dirtyPages.clear(page.pageId)
            } catch (e: Exception) {
                logger.error(e) { "reconciliation of ${page.path.value} failed; leaving it dirty for the next startup" }
            }
        }
    }

    /** (3)+(4) Post-write steps; the bytes are already durably on disk and the page already marked dirty. */
    private fun commitAndIndex(intent: WriteIntent, newHash: String): WriteOutcome =
        try {
            historyHook.commit(intent.path, intent.bytes) // no-op until W4
            indexBuilder.reindex(intent.pageId) // targeted O(1); THROWS on a vanished save-path page (MUST-FIX 4)
            dirtyPages.clear(intent.pageId) // every post-step succeeded — clear the write-ahead mark
            WriteOutcome.Written(newHash = newHash, commit = null)
        } catch (e: Exception) {
            // The bytes ARE on disk and the page is ALREADY marked dirty (write-ahead). Leave the mark;
            // the next startup reconciles. The cause is mapped to a structured code at the W3 route.
            logger.error(e) { "save wrote ${intent.path.value} but a post-write step failed; left dirty for reconcile" }
            WriteOutcome.WrittenButUnindexed(newHash = newHash, cause = e.message ?: e::class.simpleName ?: "unknown")
        }

    /**
     * The id/slug/redirect_from rename guard (MUST-FIX 1): any change to a URL/identity-deriving field
     * is a rename, returned as [WriteOutcome.UnsupportedEdit]; a pure content edit returns null. An
     * unknown page returns null too — the CAS then reports `page_deleted`.
     *
     * id/slug/redirect_from are all compared like-for-like against the CURRENT published frontmatter,
     * so REMOVING a materialized `id:` line (null vs present) is a change and rejected, and a body-only
     * edit to a page whose on-disk id legitimately differs from its assigned pageId (a duplicate/adopted
     * page) is ALLOWED — the comparison is against the file's own current id, never the pageId.
     */
    private fun classifyEdit(intent: WriteIntent): WriteOutcome? {
        val current = indexBuilder.current.byId[intent.pageId] ?: return null
        val submitted = frontmatterParser.parse(intent.bytes)
        if (submitted.scalar("id") != current.frontmatter.scalar("id")) return WriteOutcome.UnsupportedEdit(field = "id")
        if (submitted.scalar("slug") != current.frontmatter.scalar("slug")) return WriteOutcome.UnsupportedEdit(field = "slug")
        if (submitted.strings("redirect_from") != current.frontmatter.strings("redirect_from")) {
            return WriteOutcome.UnsupportedEdit(field = "redirect_from")
        }
        return null
    }

    /** Shapes a CAS conflict into the wire-neutral outcome — lenient UTF-8 to match `IndexedPage.markdown`. */
    private fun conflict(intent: WriteIntent, reason: String, current: ByteArray?): WriteOutcome.Conflict =
        WriteOutcome.Conflict(
            reason = reason,
            currentContent = current?.let { String(it, Charsets.UTF_8) },
            currentHash = current?.let(citations::contentHash),
            currentPath = indexBuilder.current.byId[intent.pageId]?.path,
        )

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * One content-mutating save. [path] is the on-disk location; [pageId] the immutable identity at that
 * path; [baseHash] the frozen `CitationFactory.contentHash` the client computed over the bytes it last
 * saw; [bytes] the EXACT full document buffer to write VERBATIM — never reserialized, never patched.
 */
// Array field on a one-shot param (never a map key) — no generated equals/hashCode (house style).
data class WriteIntent(val pageId: PageId, val path: TreePath, val baseHash: String, val bytes: ByteArray)

/**
 * One new-page creation (PB-WRITE-1, chunk W2). [path] is the server-derived on-disk location,
 * [pageId] the freshly minted identity (materialized into [bytes]' frontmatter from birth), [bytes]
 * the EXACT composed document buffer to write VERBATIM (frontmatter + body) — never reserialized.
 */
// Array field on a one-shot param (never a map key) — no generated equals/hashCode (house style).
data class CreateIntent(val pageId: PageId, val path: TreePath, val bytes: ByteArray)

/**
 * The Git seam (PB-WRITE-1): a no-op default in W1, keeping the `WrittenButUnindexed`/commit-recovery
 * paths real and testable WITHOUT importing anything from `domain/history/` (W4). W4 rewires the Koin
 * `single` to its real `HistoryProvider.commit` adapter — a wiring change, not a signature change.
 */
fun interface WriteHistoryHook {
    fun commit(path: TreePath, bytes: ByteArray)
}
