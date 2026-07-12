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
 * REPLACE-EXCEPT, not replace (ADR-0011 D5/D15): a row whose root this pass did NOT scan SURVIVES. Checkpoints
 * are DURABLE state - they are what closes the down-time-move alias gap - so a routine rebuild must not delete
 * them for a root it has no authority over, and there are three such classes, not one: a root skipped this pass,
 * a root never scanned since boot, and a DETACHED root (whose rows the pre-C4 wholesale replace silently purged
 * on the first publish after its name left `roots {}` - the very act `detachedRootsRefusal` treats as a
 * deliberate, backup-first operator decision). Taking the POSITIVE authority set is what makes all three fall
 * out for free.
 *
 * The merge is KEYED, snapshot-wins - never a concat: a mid-run vanished root double-covers (its carried-forward
 * section supplies the same ids the retained rows do), so it must be idempotent by key. When every registered
 * root scanned and no detached rows exist, the retained set is empty and the result is byte-identical to the
 * wholesale replace - the ordinary case is unchanged.
 */
fun PageCheckpointRepository.replaceFrom(snapshot: PageIndex, scannedRoots: Set<RootName>) {
    val retained = load().filterValues { it.root !in scannedRoots }
    replace(retained + snapshot.pages.associate { it.id to PreviousUrl(it.root, it.urlPath) })
}
