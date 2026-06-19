package com.plainbase.domain.repository

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId

/**
 * The WRITE-AHEAD dirty-page journal (PB-WRITE-1 fix H / debate MUST-FIX 5): durable recovery state
 * for a save that wrote bytes to disk but did not finish its post-write steps. It lives in the APP
 * database (`plainbase.db`) — recovery truth is app state, exactly like the §B3 checkpoint — never in
 * disposable `search.db` (ADR-0004), and never a flat file (which would re-implement the durable
 * atomic storage SQLDelight gives for free).
 *
 * The journal is marked BEFORE the disk write and cleared only after every post-step succeeds, so a
 * crash between the write and the post-steps leaves a recoverable record. Its real payoff is W4 commit
 * recovery (a write committed-to-disk but crashed before `git commit`); in W1 the startup `rebuild()`
 * already self-heals the index, but the write-ahead record + [DirtyPage.expectedHash] drift-skip is
 * what the reconciliation correctness rests on.
 */
interface DirtyPageRepository {

    /**
     * Write-ahead: record [pageId] at [path] as in-flight with [expectedHash] (the hash of the
     * about-to-be-written bytes) and [stage]. Durable and idempotent (upsert) — marked BEFORE the disk
     * write so a crash between the write and the post-steps is still recoverable.
     */
    fun mark(pageId: PageId, path: TreePath, expectedHash: String, stage: Stage)

    /** Every page still dirty, for startup reconciliation. */
    fun all(): List<DirtyPage>

    /**
     * The current dirty row for [pageId], or null if none. Captured BEFORE a write-ahead [mark]
     * overwrites it, so a no-write attempt can restore a prior recovery record rather than clobber it
     * (MUST-FIX 2 — the dirty-row-clobber fix).
     */
    fun get(pageId: PageId): DirtyPage?

    /** Clear [pageId] once ALL post-steps (the reindex, and in W4 the commit) have succeeded. */
    fun clear(pageId: PageId)
}

/**
 * The per-stage status of an in-flight save. W1 uses [WRITING] then clears on success; W4 adds
 * [COMMITTING] for commit recovery. [INDEXING] marks the search/reindex step. Stored as the enum
 * name in TEXT.
 */
enum class Stage { WRITING, COMMITTING, INDEXING }

/** One dirty-journal row: the page, its on-disk path, the hash being written, and the reached [stage]. */
data class DirtyPage(val pageId: PageId, val path: TreePath, val expectedHash: String, val stage: Stage)
