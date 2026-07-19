package com.plainbase.domain.service

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.CreateGrant
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.render.HeadingSlugger
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The serialized write pipeline (PB-WRITE-1): the single funnel for a content-mutating
 * save. Every [write] runs under ONE `@Synchronized` monitor (the house [IndexBuilder] idiom, never
 * `@Volatile`); the disk write is a single atomic, disk-authoritative compare-and-swap, and the index
 * update is a targeted O(changed-page) reindex. Pure domain — no framework imports.
 *
 * The critical section, in order:
 *  0. **Edit-classification guard**: a buffer that changes `id`/`slug`/
 *     `redirect_from` is a RENAME, not an edit — REJECTED with [WriteOutcome.UnsupportedEdit], never
 *     silently fixed and never rebuild()-fallen-back. This is what makes the targeted reindex's
 *     skip-the-checkpoint sound by construction.
 *  1. **Write-ahead dirty mark**: the page is marked dirty with the about-to-be
 *     written bytes' hash BEFORE the disk write, so a crash between the write and the post-steps is
 *     recoverable.
 *  2. **Atomic, disk-authoritative CAS** ([ContentStore.compareAndSwapWrite]):
 *     one operation resolves + rechecks identity + renames. No read-then-write TOCTOU window.
 *  3. **Targeted reindex** ([IndexBuilder.reindex]): one page re-rendered and
 *     single-page search upsert; THROWS on a vanished save-path page (caught → [WriteOutcome
 *     .WrittenButUnindexed]).
 *  4. **Clear the mark** only after every post-step succeeds.
 *
 * Lock-ordering topology: the pipeline monitor calls into the [IndexBuilder] monitor
 * (via [IndexBuilder.reindex]) — strictly one-directional, no back-edge, so no deadlock with a
 * concurrent watcher `rebuild()` (which takes only the IndexBuilder monitor).
 */
class WritePipeline(
    /**
     * The per-root content trees. ONE pipeline, N roots: cross-root writes serialize on the single monitor -
     * the simple, correct default, matching the one rebuild monitor. The root is never guessed and never taken
     * from a client path: it rides on the intent, having come from the snapshot page the gate authorized (an
     * edit) or the validated request field (a create).
     */
    private val stores: (RootName) -> ContentStore,
    private val indexBuilder: IndexBuilder,
    private val citations: CitationFactory,
    private val frontmatterParser: FrontmatterParser,
    private val dirtyPages: DirtyPageRepository,
    private val idMap: IdMapRepository,
    private val aliasRegistry: UrlAliasRegistry,
    /** The availability HOLDER, both directions: the reconcile arms READ it, the post-write absorbers MARK through it. */
    private val availability: RootAvailability,
    private val historyHook: WriteHistoryHook = WriteHistoryHook { _, _, _, _, _ -> null },
) {

    /** The shared probe-and-mark rule, over the SAME holder (see [markIfRootGone]). */
    private val rootLoss = RootLossClassifier(availability)

    /** The ONE 404-vs-503 rule (C1), over the SAME durable index this pipeline binds into. Never re-derived here. */
    private val absence = AbsenceClassifier(idMap)

    @Synchronized
    fun write(@Suppress("UNUSED_PARAMETER") grant: EditGrant, intent: WriteIntent): WriteOutcome {
        // [grant] is an unused compile-time witness that PolicyService.checkEdit() ran (A3): the gated mutator
        // CANNOT be reached without a minted grant. The body is unchanged.
        // (0) Edit-classification guard — a rename is rejected, never half-applied.
        classifyEdit(intent)?.let { return it }

        // Capture any prior dirty row (a real WrittenButUnindexed recovery record from an earlier
        // attempt) BEFORE the write-ahead mark overwrites it. A NO-WRITE outcome restores it rather
        // than clobbering/clearing it, so a not-actually-written attempt never destroys a prior record.
        val prior = dirtyPages.get(RootedPageId(intent.root, intent.pageId))

        // (1) Write-ahead: mark dirty with the new bytes' hash BEFORE the disk write.
        dirtyPages.mark(
            intent.pageId,
            RootedPath(intent.root, intent.path),
            expectedHash = citations.contentHash(intent.bytes),
            stage = Stage.WRITING,
        )

        // (2) Atomic, disk-authoritative CAS (fix A), against the GATED root's tree.
        val store = stores(intent.root)
        val cas = try {
            store.compareAndSwapWrite(intent.path, intent.baseHash, intent.bytes, citations::contentHash)
        } catch (e: RootUnavailable) {
            // A root-loss THROW is a nothing-written outcome that never reaches the `when` below, so it must undo the
            // write-ahead mark itself: leaving it would journal a WRITING row whose expectedHash names bytes that
            // never touched disk - a permanently misleading recovery record minted by a request that wrote nothing,
            // and it would have CLOBBERED any real prior record on the way. Restore, then rethrow: the 503 still lands.
            restoreOrClear(RootedPageId(intent.root, intent.pageId), prior)
            throw e
        }
        return when (cas) {
            // Nothing was written for Deleted/Mismatch and a non-mutated Unreadable — restore the prior
            // recovery record, or clear.
            is CasResult.Deleted -> {
                restoreOrClear(RootedPageId(intent.root, intent.pageId), prior)
                // **A4's live instance** (C1). `CasResult.Deleted` means the CAS resolved NO FILE to swap - a
                // no-bytes observation, arriving by a different road than a classified read, and it gets the same
                // answer: only the durable INDEX may turn it into "this page was deleted". Reporting `page_deleted`
                // for a page whose binding is still live is the write path's version of the 404 lie - it tells the
                // editor its content has no home and offers to save it as a NEW page, minting a second permalink for
                // a page that is sitting safe on an unmounted disk. So an unverified absence 503s and the buffer
                // survives to be saved again.
                val target = RootedPath(intent.root, intent.path)
                if (absence.absenceAt(target) == ContentRead.AbsenceUnknown) throw AbsenceUnverified(target)
                conflict(intent, reason = "page_deleted", current = null)
            }
            is CasResult.Mismatch -> {
                restoreOrClear(RootedPageId(intent.root, intent.pageId), prior)
                conflict(intent, reason = "content_changed", current = cas.currentBytes)
            }
            is CasResult.Unreadable -> {
                // A mutated target (a non-atomic copy-fallback that may have truncated/partially replaced
                // the file) KEEPS the write-ahead mark set at (1) — expectedHash = the INTENDED bytes'
                // hash — so reconcile commits a fully-landed copy or drift-skips a partial. Only a
                // NON-mutated Unreadable (nothing landed) restores-or-clears.
                if (!cas.targetMutated) restoreOrClear(RootedPageId(intent.root, intent.pageId), prior)
                WriteOutcome.Unreadable(cas.cause)
            }
            is CasResult.Written -> commitAndIndex(intent, newHash = cas.newHash)
        }
    }

    /**
     * One new-page creation (PB-WRITE-1), on the SAME monitor as [write] so a create
     * serializes with every edit and every watcher rebuild. No CAS / edit-classification: a create has
     * no prior content to classify and no `base_hash` — the collision check is the filesystem's own
     * exclusive create ([ContentStore.createExclusive]), a pipeline outcome, never a route pre-check.
     *
     * The critical section mirrors [write]'s write-ahead-then-post-steps shape:
     *  0. **Canonical-URL collision guard**: the prospective page/folder canonical URL,
     *     read against the published snapshot, must not be owned by a DIFFERENT page/folder/live-alias —
     *     a hit → [WriteOutcome.SlugConflict], NOTHING written. The race safety is the `@Synchronized`
     *     monitor (shared with [write] and the watcher rebuild): it serializes WHOLE create sequences, so
     *     two concurrent colliding creates can't interleave their check-then-create — the second sees the
     *     first's published rebuild and loses cleanly. (The snapshot read itself is lock-free — an
     *     `AtomicReference.get` of a deeply-immutable index — so this is NOT "read under the rebuild's
     *     lock"; correctness comes from serializing the create, not from the read.) The residual window
     *     is a create racing an EXTERNAL watcher-driven rebuild (a file appearing on disk between our
     *     read and create) — accepted best-effort like the asset route's write-time TOCTOU (see
     *     `PageWriteRoutes.kt`): never corruption, because the create's own rebuild + [CanonicalUrlBuilder]
     *     deterministically resolve the collision afterward; only the HTTP status can be 201-instead-of-409.
     *     This is the right home for the check (not a route pre-check): slugs/URLs are snapshot-authoritative,
     *     so the verdict belongs inside the serialized create, exactly like the CAS for content.
     *  1. write-ahead dirty mark with the about-to-be-written bytes' hash (a fresh pageId has no prior
     *     journal row, so the no-write branches just clear);
     *  2. exclusive create — [CreateResult.Exists] → [WriteOutcome.AlreadyExists]; [CreateResult
     *     .Rejected] → [WriteOutcome.InvalidLocation] (containment); [CreateResult.Unreadable] →
     *     [WriteOutcome.Unreadable]; [CreateResult.Created] → the post-steps;
     *  3. bind identity, run the history hook (no-op until Git history), index via a full [IndexBuilder.rebuild]
     *     (its own scan picks up the just-created file, sidestepping the indexed-only read gate and
     *     reusing every collision/alias/checkpoint rule), then a targeted [IndexBuilder.reindex] whose
     *     PROPAGATING single-page search upsert surfaces an FTS-sync failure. A post-step throw is
     *     caught → [WriteOutcome.WrittenButUnindexed] (the bytes ARE on disk, the page IS dirty).
     */
    @Synchronized
    fun create(@Suppress("UNUSED_PARAMETER") grant: CreateGrant, intent: CreateIntent): WriteOutcome {
        // [grant] is an unused compile-time witness that PolicyService.checkCreate() ran (A3). Body unchanged.
        // (0) Canonical-URL collision guard, under the monitor against the fresh snapshot — BEFORE any
        // write or dirty mark, so a no-write conflict never touches the journal at all.
        canonicalUrlCollision(intent)?.let { return WriteOutcome.SlugConflict(it) }

        // (1) Write-ahead: mark dirty with the new bytes' hash BEFORE the disk create. A fresh pageId
        // has no prior recovery row, so a no-write outcome simply clears the mark.
        dirtyPages.mark(
            intent.pageId,
            RootedPath(intent.root, intent.path),
            expectedHash = citations.contentHash(intent.bytes),
            stage = Stage.WRITING,
        )

        // (2) Exclusive create (write-if-absent) — collision is a race-safe pipeline outcome, not a pre-check.
        val create = try {
            stores(intent.root).createExclusive(intent.path, intent.bytes, citations::contentHash)
        } catch (e: RootUnavailable) {
            // The write()-arm rule, for the create: a root-loss THROW wrote nothing, so it clears the write-ahead mark
            // (a fresh pageId has no prior row to restore) rather than journaling a WRITING row for a create that
            // never happened. Then rethrow - the 503 still reaches the wire.
            dirtyPages.clear(RootedPageId(intent.root, intent.pageId))
            throw e
        }
        return when (create) {
            is CreateResult.Exists -> {
                dirtyPages.clear(RootedPageId(intent.root, intent.pageId))
                WriteOutcome.AlreadyExists(create.path)
            }
            is CreateResult.Rejected -> {
                dirtyPages.clear(RootedPageId(intent.root, intent.pageId)) // an uncreatable location — NOTHING written
                WriteOutcome.InvalidLocation(create.reason)
            }
            is CreateResult.Unreadable -> {
                // A mutated target (the authority may already hold the created bytes, Q8b's create twin)
                // KEEPS the write-ahead mark set at (1), mirroring write()'s CAS arm, so reconcile commits
                // a fully-landed create or drift-skips. Only a nothing-landed Unreadable clears.
                if (!create.targetMutated) dirtyPages.clear(RootedPageId(intent.root, intent.pageId))
                WriteOutcome.Unreadable(create.cause)
            }
            is CreateResult.Created -> createAndIndex(intent, newHash = create.newHash)
            // ParentMissing is produced ONLY by ContentStore.writeAssetExclusive (asset writes); the page
            // create path uses createExclusive, which creates parents and never returns it. Unreachable here.
            CreateResult.ParentMissing -> error("createExclusive never returns ParentMissing; it is asset-write-only (W3b)")
        }
    }

    /** (3) Post-create steps; the bytes are already durably on disk and the page already marked dirty. */
    private fun createAndIndex(intent: CreateIntent, newHash: String): WriteOutcome =
        try {
            // the create composed the id INTO frontmatter
            idMap.bind(RootedPath(intent.root, intent.path), intent.pageId, materialized = true)
            // The create's commit SHA (null off Git). A plain POST /pages leaves author/committer null (server
            // identity); create-apply threads the proposer->author + approver->committer (an in-glob agent: both = agent).
            val commit = historyHook.commit(intent.root, intent.path, intent.bytes, intent.author, intent.committer)
            indexBuilder.rebuild() // re-scans disk; picks up the new file, reuses every collision/alias/URL rule
            // A rebuild no longer FAILS on a lost root - it probes, marks, skips, carries and returns normally -
            // so `it returned, so it worked` is no longer true and a try/catch around it is not a guard. Re-read
            // the mark the rebuild itself just made: a create into a root that died mid-request must never answer
            // success. Today this is a BELT (the reindex below always throws first, because the just-created page
            // cannot be in a carried-forward section that predates it), but that safety is emergent, and a
            // plausible "why do we index twice?" refactor would silently remove it.
            if (!availability.current().isAvailable(intent.root)) throw RootUnavailable(intent.root, UnavailableCause.VANISHED)
            // rebuild()'s publication-listener search sync is best-effort (its listener exceptions are
            // swallowed+logged), so a failed FTS sync would otherwise yield a clean 201 with the page
            // missing from search and no retry. A targeted reindex() of the now-published page upserts it
            // via the PROPAGATING SearchIndexer.syncPage — a search-sync failure throws here, so it lands
            // in the catch below as WrittenButUnindexed (the SAME guarantee PUT gives) and the dirty mark
            // is RETAINED for reconcile. Idempotent on success (a second single-page upsert).
            //
            // Pinned to the LOCATION the bytes went to, never to the page id: the `rebuild()` just above is
            // itself the window in which the D17 contest can hand this id to another root, and an id-addressed
            // reindex would then index a different root's file than the one this create wrote.
            indexBuilder.reindex(RootedPath(intent.root, intent.path))
            dirtyPages.clear(RootedPageId(intent.root, intent.pageId)) // every post-step succeeded — clear the write-ahead mark
            WriteOutcome.Written(newHash = newHash, commit = commit)
        } catch (e: Exception) {
            // The bytes ARE on disk and the page is ALREADY marked dirty (write-ahead). Leave the mark;
            // the next startup reconciles. The cause is mapped to a structured code at the create route.
            markIfRootGone(intent.root)
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
     *  - each NEW (not-yet-indexed) FOLDER segment's URL vs existing FOLDER URLs;
     *  - the new PAGE's URL vs existing PAGE URLs and LIVE aliases (a dangling alias whose
     *    target id is absent from the snapshot is ignored — the next rebuild's shadow-sweep drops it,
     *    so it must not permanently block the create).
     *
     * The new page's frontmatter `slug` and the on-disk path come straight from [intent] (the route
     * composed the bytes), so the computed URL is byte-identical to what the page would publish at.
     */
    private fun canonicalUrlCollision(intent: CreateIntent): String? {
        // Root-scoped end to end: URL space is per root (§A4 holds per tree), so the walk, the page-URL probe
        // and the alias probe all read the INTENT's root's section - the root the gate authorized, carried on
        // the intent. The snapshot read is a DELIBERATE fresh one (that is what makes two concurrent
        // URL-colliding creates serialize under the monitor); freshness is the feature, and the ROOT is pinned,
        // so a rebuild landing under it can change WHICH pages it sees but never WHICH root it consults.
        val snapshot = indexBuilder.current
        val section = snapshot.section(intent.root)
        val folderPath = intent.path.parent
        val slugOverride = frontmatterParser.parse(intent.bytes).scalar("slug")
        val existingFolderUrls = CanonicalUrlBuilder.folderUrlPaths(section.folders)
        val indexedFolderPaths = section.folders.map { it.path }.toSet()
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
        val pageOwner = snapshot.byUrlPath[RootedPath(intent.root, pageUrl)]
        if (pageOwner != null && pageOwner.path != intent.path) return pageUrl.value // page-page
        // Only a LIVE alias blocks: a row pointing at a page id no longer in THIS root is dangling (the
        // shadow-sweep hasn't dropped it yet) and must not permanently wedge the URL. Liveness is judged in
        // the alias's OWN root - an alias is a within-root redirect, so a page that now holds the id under a
        // DIFFERENT root (the D17 contest re-awarding it) leaves this row just as dangling as an absent id
        // would, and a bare-pageId probe would read it as live and 409 a create that should have succeeded.
        val aliasTarget = aliasRegistry.find(RootedPath(intent.root, pageUrl))
        if (aliasTarget != null && snapshot.byId[aliasTarget.id]?.root == intent.root) return pageUrl.value
        return null
    }

    /**
     * A no-write outcome restores any prior dirty row rather than clearing it (the
     * dirty-row-clobber fix): the write-ahead [mark][DirtyPageRepository.mark] overwrote it with THIS
     * attempt's hash, but nothing was actually written, so the earlier on-disk-but-unindexed recovery
     * record must survive. With no prior row, the page simply clears (nothing to reconcile).
     */
    private fun restoreOrClear(pageId: RootedPageId, prior: DirtyPage?) {
        if (prior != null) {
            dirtyPages.mark(prior.pageId, prior.path, expectedHash = prior.expectedHash, stage = prior.stage)
        } else {
            dirtyPages.clear(pageId)
        }
    }

    /**
     * Deterministic startup reconciliation of a prior interrupted save (fix H), once,
     * under the pipeline monitor. It cannot race an in-flight save (the engine is not serving yet); a
     * watcher `rebuild()` may race but is safe by the one-directional lock order.
     *
     * Drift-skip: a page whose on-disk hash no longer matches the recorded [com.plainbase.domain
     * .repository.DirtyPage.expectedHash] had an external edit land after the crash — do NOT re-commit
     * or re-index the stale intent; leave the mark for an operator. A file gone since the crash is
     * cleared (the startup rebuild already dropped it). A re-thrown step leaves the page dirty for the
     * next boot (idempotent) — never crashing serve().
     *
     * **A row is never CLEARED on evidence a missing root could not supply (ADR-0011 D5).** The journal row is
     * an interrupted save's ONLY recovery record, so it is skipped-and-WARNed rather than cleared whenever the
     * root cannot answer for its page: when the root is DETACHED (there is no store to read at all), when it is
     * already marked UNAVAILABLE, and - the arm the two status checks CANNOT cover - when the classified read
     * comes back `RootDown`, i.e. the root went away and nothing has marked it yet. Only a genuine deletion
     * under a LIVE root clears.
     */
    @Synchronized
    fun reconcileDirtyPages() {
        val dirty = dirtyPages.all()
        if (dirty.isEmpty()) return
        logger.info { "reconciling ${dirty.size} dirty page(s) from a prior interrupted save" }
        val available = availability.current()
        for (page in dirty) {
            val root = page.path.root
            try {
                // The cheap status arms, first: DETACHED (a root whose rows outlive its name in `roots {}` - it
                // has no store, so there is nothing to read) and already-marked UNAVAILABLE. Neither may clear.
                if (!knownRoot(root)) {
                    logger.warn {
                        "dirty page ${page.path.path.value} belongs to root '$root', which is not configured; " +
                            "leaving it journaled for a boot where that root is back"
                    }
                    continue
                }
                if (!available.isAvailable(root)) {
                    logger.warn {
                        "dirty page ${page.path.path.value} belongs to root '$root', which is not serving; " +
                            "leaving it journaled for a boot where that root is back"
                    }
                    continue
                }
                val onDisk = when (val read = absence.read(stores(root), page.path)) {
                    is ContentRead.Bytes -> read.bytes
                    ContentRead.RootDown -> {
                        // The status arms passed and the root STILL turned out to be gone - the unmarked window.
                        // A bare null here would have taken the clear arm below and silently destroyed an
                        // interrupted save's recovery record because the whole disk was missing.
                        logger.warn {
                            "dirty page ${page.path.path.value}: root '$root' went away under the reconcile; " +
                                "leaving it journaled (nothing is cleared for a root that cannot answer for its pages)"
                        }
                        continue
                    }
                    // **NEITHER absence clears (C0/C1), and they are one arm because they have one answer.** "The
                    // file is not there" is not "the page was deleted" - it is equally what a failed submount, a
                    // partial restore and a decoy tree look like - and this row is an interrupted save's ONLY
                    // recovery record. It is USER CONTENT. Even `ConfirmedAbsent` does not clear it: "the index does
                    // not have this page" is not the same fact as "we are authorized to destroy its recovery
                    // record", and for a page whose save was interrupted BEFORE its bind the index never had it in
                    // the first place - so a clear-on-confirmed rule would destroy exactly the rows most in need of
                    // recovery. It is cleared in ONE place, the proof-apply transaction, alongside the retirement of
                    // the binding it belongs to (`RetirementRepository.applyProofs`) - never on a bare read.
                    ContentRead.AbsenceUnknown, ContentRead.ConfirmedAbsent -> {
                        logger.warn {
                            "dirty page ${page.path.path.value} is not on disk under a live root '$root'; leaving it journaled. " +
                                "Nothing but an absence PROOF may destroy an interrupted save's recovery record, and in C0 there " +
                                "is no proof source - if the page really was deleted, the row is cleared when one arrives."
                        }
                        continue
                    }
                }
                if (citations.contentHash(onDisk) != page.expectedHash) {
                    logger.warn {
                        "dirty page ${page.path.path.value} drifted on disk since the interrupted save; skipping reconcile (left marked)"
                    }
                    continue
                }
                historyHook.commit(root, page.path.path, onDisk) // idempotent commit recovery
                // The journal row's OWN RootedPath - the location the interrupted save wrote to. This path is
                // ungated and runs at boot against a snapshot the startup rebuild has already published, so an
                // id-addressed reindex here would be the same cross-root re-derivation the write path forbids.
                indexBuilder.reindex(page.path) // tolerated to throw only if the page truly vanished — caught below
                dirtyPages.clear(RootedPageId(page.path.root, page.pageId))
            } catch (e: Exception) {
                markIfRootGone(root)
                logger.error(e) { "reconciliation of ${page.path.path.value} failed; leaving it dirty for the next startup" }
            }
        }
    }

    /**
     * Whether [root] is one this pipeline has a store for. A root name read back off a DURABLE ROW is UNTRUSTED:
     * `plainbase.db` outlives `roots {}`, so a row can name a root the registry has never heard of, and the
     * per-root store map is registry-built.
     */
    private fun knownRoot(root: RootName): Boolean = runCatching { stores(root) }.isSuccess

    /**
     * Probe [root] and MARK it if it is gone - called at the TOP of each post-write `catch`, before the outcome
     * is built. The OUTCOMES those catches produce are correct and settled (bytes on disk, dirty mark retained,
     * a 503-class answer); what was missing is the MARK, so reads kept serving a root that was no longer there.
     * Marking and answering are separate obligations, which is why this MARKS but does not throw - the shared
     * [RootLossClassifier] owns the probe-and-mark itself, so the rule has one implementation.
     *
     * It is what covers the COMMIT-HOOK arm, which the index builder's own classifiers cannot reach - git can
     * fail on a vanished root before `reindex` is ever called.
     */
    private fun markIfRootGone(root: RootName) {
        // A root name off a DURABLE ROW may name a root this pipeline has no store for (see [knownRoot]) - there
        // is nothing to probe there, and nothing to mark.
        val store = runCatching { stores(root) }.getOrNull() ?: return
        rootLoss.markIfGone(root, store)
    }

    /** (3)+(4) Post-write steps; the bytes are already durably on disk and the page already marked dirty. */
    private fun commitAndIndex(intent: WriteIntent, newHash: String): WriteOutcome =
        try {
            // The save's commit SHA (null off Git). Threads the proposer->author + approver->committer
            // attribution from the apply call site through [WriteIntent]; a plain PUT leaves them null (server identity).
            val commit = historyHook.commit(intent.root, intent.path, intent.bytes, intent.author, intent.committer)
            // Targeted O(1); THROWS on a vanished save-path page. Addressed by the LOCATION the CAS just wrote
            // to - never by the page id, which a concurrent rebuild's D17 contest can re-award to another root
            // between the CAS and this call, sending the reindex at a file these bytes never touched.
            indexBuilder.reindex(RootedPath(intent.root, intent.path))
            dirtyPages.clear(RootedPageId(intent.root, intent.pageId)) // every post-step succeeded — clear the write-ahead mark
            WriteOutcome.Written(newHash = newHash, commit = commit)
        } catch (e: Exception) {
            // The bytes ARE on disk and the page is ALREADY marked dirty (write-ahead). Leave the mark;
            // the next startup reconciles. The cause is mapped to a structured code at the wire route.
            markIfRootGone(intent.root)
            logger.error(e) { "save wrote ${intent.path.value} but a post-write step failed; left dirty for reconcile" }
            WriteOutcome.WrittenButUnindexed(newHash = newHash, cause = e.message ?: e::class.simpleName ?: "unknown")
        }

    /**
     * The id/slug/redirect_from rename guard: any change to a URL/identity-deriving field
     * is a rename, returned as [WriteOutcome.UnsupportedEdit]; a pure content edit returns null. An
     * unknown page returns null too — the CAS then reports `page_deleted`.
     *
     * id/slug/redirect_from are all compared like-for-like against the CURRENT published frontmatter,
     * so REMOVING a materialized `id:` line (null vs present) is a change and rejected, and a body-only
     * edit to a page whose on-disk id legitimately differs from its assigned pageId (a duplicate/adopted
     * page) is ALLOWED — the comparison is against the file's own current id, never the pageId.
     *
     * The comparison TARGET is the page the bytes are about to land on - [RootedPath], the same pinned
     * target the CAS and the reindex take - never the bare pageId. A guard is only as good as what it
     * compares against: a D17 rank contest can re-award the id to another root between the gate and this
     * call, and an id-addressed lookup would then diff the submitted frontmatter against a DIFFERENT
     * page's, letting a rename through whenever the foreign page happens to carry the new value (and
     * rejecting a legitimate body edit whenever it does not). It never decides WHERE the bytes go - which
     * is exactly why this looked benign - but a rename that gets PERMITTED is then written and preserved
     * by the (root-pinned) reindex, so the identity/URL fields drift from disk with no error anywhere.
     */
    private fun classifyEdit(intent: WriteIntent): WriteOutcome? {
        val current = indexBuilder.current.byPath[RootedPath(intent.root, intent.path)] ?: return null
        val submitted = frontmatterParser.parse(intent.bytes)
        if (submitted.scalar("id") != current.frontmatter.scalar("id")) return WriteOutcome.UnsupportedEdit(field = "id")
        if (submitted.scalar("slug") != current.frontmatter.scalar("slug")) return WriteOutcome.UnsupportedEdit(field = "slug")
        if (submitted.strings("redirect_from") != current.frontmatter.strings("redirect_from")) {
            return WriteOutcome.UnsupportedEdit(field = "redirect_from")
        }
        return null
    }

    /**
     * Shapes a CAS conflict into the wire-neutral outcome — lenient UTF-8 to match `IndexedPage.markdown`.
     *
     * [currentPath] is the location the CAS actually read [current] from, i.e. the intent's own pinned target -
     * not a snapshot lookup. A bare-pageId lookup here was wrong for the [classifyEdit] reason AND had nothing
     * to look up: the conflicting bytes came from THIS root's THIS path, so after a cross-root id reassignment
     * the snapshot would have named a foreign root's file as the thing the client should rebase onto. A
     * `page_deleted` (no current bytes) carries no path at all, which is also the shape the wire freezes.
     */
    private fun conflict(intent: WriteIntent, reason: String, current: ByteArray?): WriteOutcome.Conflict =
        WriteOutcome.Conflict(
            reason = reason,
            currentContent = current?.let { String(it, Charsets.UTF_8) },
            currentHash = current?.let(citations::contentHash),
            currentPath = current?.let { intent.path },
        )

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * One content-mutating save. [root]+[path] is the on-disk location; [pageId] the immutable identity at that
 * path; [baseHash] the frozen `CitationFactory.contentHash` the client computed over the bytes it last
 * saw; [bytes] the EXACT full document buffer to write VERBATIM — never reserialized, never patched.
 * [author]/[committer] are the optional git attribution P1b threads from the apply call site (the
 * proposer->author, approver->committer); a plain PUT leaves them null → the server default identity.
 *
 * [root] comes from the SERVER side, always: it is the root of the snapshot page the write gate authorized,
 * threaded here off the SAME snapshot object the gate read. Gate-root and write-root are therefore one
 * object's answer by construction, so a rebuild that re-awards the id to another root mid-request cannot make
 * them disagree - the race is unreachable rather than detected.
 */
// Array field on a one-shot param (never a map key) — no generated equals/hashCode (house style).
data class WriteIntent(
    val pageId: PageId,
    val root: RootName,
    val path: TreePath,
    val baseHash: String,
    val bytes: ByteArray,
    val author: CommitIdentity? = null,
    val committer: CommitIdentity? = null,
)

/**
 * One new-page creation (PB-WRITE-1). [root]+[path] is the server-derived on-disk location,
 * [pageId] the freshly minted identity (materialized into [bytes]' frontmatter from birth), [bytes]
 * the EXACT composed document buffer to write VERBATIM (frontmatter + body) — never reserialized.
 * [author]/[committer] are the optional git attribution threaded from the create-apply call site (the
 * proposer->author, approver->committer); a plain POST /pages leaves them null → the server default identity (and an
 * in-glob COMMIT agent direct create stamps the agent identity as BOTH, the `save()` b1 idiom).
 *
 * A create is the ONE write whose root comes from the REQUEST rather than from a resolved page - explicitly, in
 * its own `root` field, never inferred from the first path segment. Silently retargeting a WRITE because a
 * folder name happens to match a root name is not acceptable for mutations: explicit beats inference where
 * bytes land. The route validates the declared name against the registry (400 `invalid_root`) BEFORE the gate,
 * so this field always names a registered root.
 */
// Array field on a one-shot param (never a map key) — no generated equals/hashCode (house style).
data class CreateIntent(
    val pageId: PageId,
    val root: RootName,
    val path: TreePath,
    val bytes: ByteArray,
    val author: CommitIdentity? = null,
    val committer: CommitIdentity? = null,
)

/**
 * The Git seam (PB-WRITE-1): a no-op default in the base write layer, keeping the `WrittenButUnindexed`/commit-recovery
 * paths real and testable WITHOUT importing anything from `domain/history/`. The Git/history integration rewires the Koin
 * `single` to its real `HistoryProvider.commit` adapter — a wiring change, not a signature change.
 *
 * Returns the recorded commit SHA: the save's new commit in Git mode, or null when history is
 * off / the no-op default. The SHA threads into [WriteOutcome.Written.commit] so the wire response carries
 * the save's commit. A plain `String?` (not a domain `Commit`) keeps this seam framework-free.
 *
 * [author]/[committer] carry the optional git attribution → the proposer (author) + approver (committer) on
 * the apply path; null elsewhere → the server default identity. A `fun interface` SAM method cannot carry default
 * values, so the no-attribution callers pass `null, null` explicitly (the no-arg [commit] extension below keeps the
 * non-apply call sites terse).
 *
 * `root` selects WHICH root's history provider records the commit: history is per-root topology (a root may have
 * none at all, and Plainbase never commits into a repo it does not own), so the Koin single dispatches over the
 * per-root provider map rather than closing over one.
 */
fun interface WriteHistoryHook {
    fun commit(root: RootName, path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): String?
}

/** A no-attribution commit (the PUT/create/reconcile paths) → the server default identity. */
fun WriteHistoryHook.commit(root: RootName, path: TreePath, bytes: ByteArray): String? =
    commit(root, path, bytes, author = null, committer = null)
