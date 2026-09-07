package com.plainbase.domain.service

import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.SearchProvider
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Reconciles a published [PageIndex] against the engine's own state.
 *
 * Each full [sync] and [rebuild] performs one whole read of current durable retired-unbound identities, including a
 * no-op sync, then filters stale engine rows and snapshot inputs. Targeted [syncPage] reads only the rooted point
 * predicate before splitting or indexing. Pass-local retirement output cannot repair lost delivery; current durable
 * state supplies recovery authority. Engine truth is the self-heal input: empty, replaced, or partially updated
 * search state is reconciled from accepted snapshot pages on the next operation, while durable retirement prevents
 * stale pages from re-entering. Production callers run these operations under the builder's serialized monitor.
 */
class SearchIndexer(
    private val provider: SearchProvider,
    private val splitter: SectionSplitter,
    private val retiredUnboundIds: () -> Set<RootedPageId>,
    private val isRetiredUnbound: (RootedPageId) -> Boolean,
) {

    /** Reconciles changed accepted pages and deletes only current retired engine rows. */
    fun sync(snapshot: PageIndex) {
        val retired = retiredUnboundIds()
        val engineState = provider.indexedState()
        val accepted = snapshot.pages.filter { it.rooted !in retired }
        val stale = engineState.keys.intersect(retired)
        val changed = accepted.filter { page ->
            val state = engineState[page.rooted]
            state == null || state.contentHash != page.contentHash || state.path != page.path
        }
        if (stale.isEmpty() && changed.isEmpty()) {
            logger.debug {
                "search sync: scanned ${retired.size} retired-unbound identity(s), accepted ${accepted.size}, " +
                    "excluded ${snapshot.pages.size - accepted.size}; no mutations"
            }
            return
        }
        if (stale.isNotEmpty()) provider.delete(stale)
        // Cold start (empty engine) makes `changed` the WHOLE corpus, so stream the split documents
        // through the provider in bounded batches rather than materialize every page's at once — the
        // same working-set guard `rebuild` gets from its Sequence param. Each page stays its own
        // transaction inside the provider (§B4 per-page atomicity is untouched). One behavior shift vs
        // the old eager `.map` (which threw before any write): a page that throws mid-run — in `split`
        // or the provider index write — now leaves the earlier chunks already committed (partial, not
        // zero, progress), benign because the next sync's engine-truth diff re-detects and repairs the
        // rest, exactly as the §B4 self-heal already promises.
        if (changed.isNotEmpty()) {
            changed.asSequence().map(splitter::split).chunked(INDEX_BATCH).forEach(provider::index)
        }
        logger.debug {
            "search sync: scanned ${retired.size} retired-unbound identity(s), accepted ${accepted.size}, " +
                "excluded ${snapshot.pages.size - accepted.size}"
        }
        logger.info { "search sync: ${changed.size} page(s) upserted, ${stale.size} deleted" }
    }

    /** Rebuilds one generation from accepted pages and returns the accepted input count. */
    fun rebuild(snapshot: PageIndex): Int {
        val retired = retiredUnboundIds()
        val accepted = snapshot.pages.filter { it.rooted !in retired }
        provider.rebuild(accepted.asSequence().map(splitter::split), retired = retired)
        logger.debug {
            "search reindex: scanned ${retired.size} retired-unbound identity(s), accepted ${accepted.size}, " +
                "excluded ${snapshot.pages.size - accepted.size}"
        }
        logger.info { "search reindex: rebuilt the engine for ${accepted.size} page(s)" }
        return accepted.size
    }

    /**
     * Single-page upsert (the PB-WRITE-1 targeted-reindex path): one
     * [com.plainbase.domain.search.PageDocuments] through the provider's already-per-page-transactional
     * [SearchProvider.index] — a point authority check followed by one-page split/index work, NOT the corpus-wide
     * [indexedState] diff [sync] makes. The provider controls its own transaction and index costs.
     */
    fun syncPage(page: IndexedPage) {
        if (isRetiredUnbound(page.rooted)) {
            error("cannot sync retired/unbound page root=${page.root.value} id=${page.id.value}")
        }
        provider.index(listOf(splitter.split(page)))
        logger.debug { "search syncPage: upserted ${page.id} (root ${page.root})" }
    }

    companion object {
        /** Caps the split-document working set of a full-corpus [sync]; a cold start upserts every page. */
        internal const val INDEX_BATCH = 256

        private val logger = KotlinLogging.logger {}
    }
}
