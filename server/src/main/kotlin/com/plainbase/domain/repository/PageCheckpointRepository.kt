package com.plainbase.domain.repository

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName

/**
 * Persistence port for the §B3 page checkpoint: the previously PUBLISHED snapshot's
 * `id → canonical URL path` (root-qualified since multi-root C2) — the one fact down-time
 * move-aliasing needs. It lives in the app DB because it feeds the alias registry (app-state by
 * Phase-1 ruling), never in disposable search.db.
 *
 * **Advisory, never load-bearing:** a missing/stale/corrupt checkpoint degrades to exactly the
 * pre-Phase-2 behavior — a missed alias, a recorded conflict at worst. Index correctness, URLs,
 * and permalinks never depend on it, which is why [load] is total: unreadable state IS the empty
 * checkpoint. A `urlPath` is null for a slug-collision loser (no canonical path to alias from).
 */
interface PageCheckpointRepository {

    /** The checkpointed previous snapshot, or the empty map when none is readable (advisory — never throws). */
    fun load(): Map<PageId, PreviousUrl>

    /** Replaces the whole checkpoint with [urlPaths], atomically (one transaction per publish). */
    fun replace(urlPaths: Map<PageId, PreviousUrl>)
}

/** One checkpointed page: the root it lived under and its canonical URL path (null for a collision loser). */
data class PreviousUrl(val root: RootName, val urlPath: TreePath?)

/**
 * The §B4 checkpoint publication listener body: persists the just-published [snapshot] as the next
 * startup's previous-snapshot fact. One definition, shared by the Koin wiring and the test harness.
 */
fun PageCheckpointRepository.replaceFrom(snapshot: PageIndex) =
    replace(snapshot.pages.associate { it.id to PreviousUrl(it.root, it.urlPath) })
