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
 *
 * **A checkpoint row is deleted ONLY when an `AbsenceProof` retired the binding it belongs to (C0).** [retired]
 * is that applied set, and it is the whole delete authority - never a root-granular "we scanned this root, so
 * anything missing from it must be gone". That inference is what let ONE decoy file in a broken mount purge a
 * thousand rows, and it is deleted rather than tightened, because there is no honest version of it: a row whose
 * page a pass did not witness is in LIMBO, and limbo is not a deletion.
 *
 * So: every row this pass did not just retire SURVIVES - the skipped root's, the never-scanned root's, the
 * detached root's, the failed submount's, and the decoy tree's alike. In C0 [retired] is always EMPTY, so
 * nothing is ever removed here at all; the pass only ever ADDS what it saw.
 *
 * The merge is KEYED, snapshot-wins - never a concat: a mid-run vanished root double-covers (its carried-forward
 * section supplies the same ids the retained rows do), so it must be idempotent by key.
 */
fun PageCheckpointRepository.replaceFrom(snapshot: PageIndex, retired: Set<PageId>) {
    val retained = load().filterKeys { it !in retired }
    replace(retained + snapshot.pages.associate { it.id to PreviousUrl(it.root, it.urlPath) })
}
