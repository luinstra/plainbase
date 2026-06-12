package com.plainbase.domain.service

import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.search.SearchProvider
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Engine-truth diff sync (§B4): [sync] reconciles a published [PageIndex] snapshot against
 * [SearchProvider.indexedState] — the engine's OWN record of what it holds — upserting pages the
 * engine lacks or holds stale (`contentHash` covers every in-file change, `path` covers moves)
 * and deleting pages the snapshot no longer has. An unchanged corpus makes ZERO engine calls
 * beyond the state read (the no-op fast path).
 *
 * Diffing against engine truth instead of a previous in-memory snapshot is what makes the FIRST
 * sync after startup reconcile everything that changed while down, and a deleted engine database
 * self-healing (empty state ⇒ full upsert). It is also why a sync that fails mid-way needs no
 * cleanup: the next sync re-diffs and repairs for free (§B4 listener exception policy).
 *
 * Registered as an `IndexBuilder.PublicationListener` (Koin wiring, chunk S2), so it runs inside
 * the serialized rebuild against exactly the snapshot that was just published.
 */
class SearchIndexer(
    private val provider: SearchProvider,
    private val splitter: SectionSplitter,
) {

    fun sync(snapshot: PageIndex) {
        val engineState = provider.indexedState()
        val stale = engineState.keys - snapshot.byId.keys
        val changed = snapshot.pages.filter { page ->
            val state = engineState[page.id]
            state == null || state.contentHash != page.contentHash || state.path != page.path
        }
        if (stale.isEmpty() && changed.isEmpty()) {
            logger.debug { "search sync: engine matches the snapshot, nothing to do" }
            return
        }
        if (stale.isNotEmpty()) provider.delete(stale)
        if (changed.isNotEmpty()) provider.index(changed.map(splitter::split))
        logger.info { "search sync: ${changed.size} page(s) upserted, ${stale.size} deleted" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
