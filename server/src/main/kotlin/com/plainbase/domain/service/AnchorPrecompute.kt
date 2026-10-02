package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.discussion.DiscussionAssembly
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPersistenceFailure
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionRow
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.PageAttachment
import com.plainbase.domain.discussion.PageBytes
import com.plainbase.domain.discussion.Reanchor
import com.plainbase.domain.discussion.UnreadableReason
import com.plainbase.domain.discussion.effectiveAnchor
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import io.github.oshai.kotlinlogging.KotlinLogging

fun interface PageReindexListener {
    fun reindexed(root: RootName, page: IndexedPage)
}

/**
 * Precomputes every missing quote match after an index publication, with page reindexes taking priority.
 * Page processing stays synchronous and measures about 5.7 seconds for 200 discussions on an 8 MiB page.
 * A page remains the work unit so its freshness checks can bracket each bounded row batch.
 */
@Suppress("TooGenericExceptionCaught")
class AnchorPrecompute(
    private val rows: DiscussionRows,
    private val discussions: DiscussionStore,
    private val contents: (RootName) -> ContentStore,
    private val fullReads: DiscussionFullReads,
    private val absence: AbsenceClassifier,
    private val sync: DiscussionSyncState,
    private val availability: RootAvailability,
    private val current: () -> PageIndex,
    private val alarm: RebuildScheduler.Alarm,
) : AutoCloseable {
    private val lock = Any()
    private val targeted = LinkedHashMap<RootedPageId, IndexedPage>()
    private val retries = mutableMapOf<RootedPageId, Triple<IndexedPage, Int, Long>>()
    private var fullPass: Iterator<IndexedPage>? = null
    private var retryToken = 0L
    private var wakeToken = 0L
    private var wakeDelay: Long? = null
    private var running = false
    private var closed = false

    fun published(snapshot: PageIndex, retired: Set<RootedPageId>) {
        synchronized(lock) {
            dropUnavailableLocked()
            retired.forEach { key ->
                targeted.remove(key)
                retries.remove(key)
            }
            fullPass = snapshot.sections.asSequence().flatMap { it.pages.asSequence() }.iterator()
            armLocked(0)
        }
    }

    fun reindexed(root: RootName, page: IndexedPage) {
        synchronized(lock) {
            dropUnavailableLocked()
            if (closed || root !in sync.scopeRoots || !availability.current().isAvailable(root)) return
            val key = RootedPageId(root, page.id)
            retries.remove(key)
            targeted[key] = page
            armLocked(0)
        }
    }

    fun rootSynced(root: RootName) {
        synchronized(lock) {
            dropUnavailableLocked()
            if (closed || root !in sync.scopeRoots || !availability.current().isAvailable(root)) return
            targeted.keys.removeAll { it.root == root }
            retries.keys.removeAll { it.root == root }
            fullPass = current().sections.asSequence().flatMap { it.pages.asSequence() }.iterator()
            armLocked(0)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            targeted.clear()
            retries.clear()
            fullPass = null
            wakeDelay = null
            wakeToken++
        }
        (alarm as? AutoCloseable)?.close()
    }

    private fun armLocked(delay: Long) {
        if (closed || running || !hasImmediateWorkLocked()) return
        val safeDelay = delay.coerceAtLeast(0)
        val scheduled = wakeDelay
        if (scheduled != null && scheduled <= safeDelay) return
        val token = ++wakeToken
        wakeDelay = safeDelay
        try {
            alarm.after(safeDelay) { runNext(token) }
        } catch (failure: InterruptedException) {
            if (wakeToken == token) wakeDelay = null
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            if (wakeToken == token) wakeDelay = null
            logger.error(failure) { "could not schedule anchor pre-compute work" }
        }
    }

    private fun hasImmediateWorkLocked(): Boolean =
        targeted.keys.any { canPrecompute(it.root) } ||
            retries.any { (key, retry) -> retry.third == READY_RETRY_TOKEN && canPrecompute(key.root) } ||
            fullPass?.hasNext() == true

    private fun runNext(token: Long) {
        val work = synchronized(lock) {
            if (closed || token != wakeToken) return
            wakeDelay = null
            val next = takeNextLocked() ?: return
            running = true
            next
        }
        val (key, page, failures) = work
        try {
            if (!canPrecompute(key.root)) {
                return
            }
            val latest = current().pageAt(key)
            if (latest == null) return
            if (latest.contentHash != page.contentHash || latest.path != page.path) {
                reindexed(key.root, latest)
                return
            }
            precomputePage(key.root, latest)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: RootUnavailable) {
            availability.markUnavailable(key.root, failure.reason)
            synchronized(lock) {
                targeted.keys.removeAll { it.root == key.root }
                retries.keys.removeAll { it.root == key.root }
            }
        } catch (failure: DiscussionPersistenceFailure) {
            sync.enter(key.root, failure.message ?: "discussion anchor cache write failed")
        } catch (failure: Exception) {
            retry(key, page, failures, failure)
        } finally {
            synchronized(lock) {
                running = false
                if (!Thread.currentThread().isInterrupted) armLocked(0)
            }
        }
    }

    private fun takeNextLocked(): Triple<RootedPageId, IndexedPage, Int>? {
        dropUnavailableLocked()
        return takeTargetedLocked() ?: takeReadyRetryLocked() ?: takeFullPassPageLocked()
    }

    private fun dropUnavailableLocked() {
        val roots = availability.current()
        targeted.keys.removeAll { !roots.isAvailable(it.root) }
        retries.keys.removeAll { !roots.isAvailable(it.root) }
    }

    private fun takeTargetedLocked(): Triple<RootedPageId, IndexedPage, Int>? {
        val entry = targeted.entries.firstOrNull { canPrecompute(it.key.root) } ?: return null
        targeted.remove(entry.key)
        return Triple(entry.key, entry.value, 0)
    }

    private fun takeReadyRetryLocked(): Triple<RootedPageId, IndexedPage, Int>? {
        val entry = retries.entries.firstOrNull {
            it.value.third == READY_RETRY_TOKEN && canPrecompute(it.key.root)
        } ?: return null
        retries.remove(entry.key)
        return Triple(entry.key, entry.value.first, entry.value.second)
    }

    private fun takeFullPassPageLocked(): Triple<RootedPageId, IndexedPage, Int>? {
        val pages = fullPass
        while (pages?.hasNext() == true) {
            val page = pages.next()
            val work = fullPassWork(page)
            if (work != null) return work
        }
        fullPass = null
        return null
    }

    private fun fullPassWork(page: IndexedPage): Triple<RootedPageId, IndexedPage, Int>? {
        val key = RootedPageId(page.root, page.id)
        if (key in targeted || key in retries || key.root !in sync.scopeRoots) return null
        if (!canPrecompute(key.root)) return null
        return Triple(key, page, 0)
    }

    private fun canPrecompute(root: RootName): Boolean =
        root in sync.scopeRoots && availability.current().isAvailable(root) && !sync.isUnsynced(root)

    private fun precomputePage(root: RootName, page: IndexedPage) {
        val content = contents(root)
        var pageBytes: PageBytes? = null
        var after: DiscussionId? = null
        while (true) {
            val (snapshot, livePage) = currentPage(root, page) ?: return
            val batch = PageAttachment.rows(rows, root, livePage, snapshot, after, PAGE_BATCH_SIZE)
            val missing = missingMatches(root, page, batch.rows)
            if (missing.isNotEmpty()) {
                val bytes = pageBytes ?: readPageBytes(content, root, livePage) ?: return
                pageBytes = bytes
                precomputeRows(root, livePage, batch.rows, missing, bytes)
            }
            after = batch.next ?: return
        }
    }

    private fun currentPage(root: RootName, page: IndexedPage): Pair<PageIndex, IndexedPage>? {
        if (sync.isUnsynced(root)) return null
        val snapshot = current()
        val latest = snapshot.pageAt(RootedPageId(root, page.id)) ?: return null
        if (latest.contentHash != page.contentHash || latest.path != page.path) return null
        return snapshot to latest
    }

    private fun missingMatches(root: RootName, page: IndexedPage, batch: List<DiscussionRow>): List<DiscussionRow> =
        batch.filter { row ->
            row.anchorKind == "quote" && row.state == "ok" &&
                !hasCurrentMatch(root, row.id, row.anchorHash, page.contentHash)
        }

    private fun readPageBytes(content: ContentStore, root: RootName, page: IndexedPage): PageBytes? {
        val raw = when (val read = absence.read(content, RootedPath(root, page.path))) {
            is ContentRead.Bytes -> read.bytes
            ContentRead.ConfirmedAbsent, ContentRead.AbsenceUnknown, ContentRead.RootDown -> return null
        }
        val bytes = PageBytes.of(raw, page.headings)
        return bytes.takeIf { it.hash == page.contentHash }
    }

    private fun precomputeRows(
        root: RootName,
        page: IndexedPage,
        rowsForPage: List<DiscussionRow>,
        missing: List<DiscussionRow>,
        bytes: PageBytes,
    ) {
        for (row in rowsForPage) {
            if (sync.isUnsynced(root)) return
            if (row in missing) precomputeRow(root, page, row, bytes)
        }
    }

    private fun precomputeRow(root: RootName, page: IndexedPage, row: DiscussionRow, bytes: PageBytes) {
        val assembled = fullReads.withNarrowed {
            DiscussionAssembly.assemble(row.id, discussions.read(root, row.id, setOf(EntryName.Marker)), retain = { false })
        }
        val marker = when (assembled) {
            is DiscussionRead.Ok -> assembled.files.marker
            is DiscussionRead.Failed -> throw DiscussionReadFailed(root, assembled.cause)
            is DiscussionRead.Unreadable -> {
                if (assembled.reason == UnreadableReason.SYMLINK && assembled.entry == EntryName.Marker.fileName) {
                    throw DiscussionReadFailed(root, "anchor marker unavailable for ${row.id.value}")
                }
                return
            }
            DiscussionRead.Absent, is DiscussionRead.Incomplete -> return
        }
        val match = Reanchor.match(effectiveAnchor(marker.value.anchor, marker.value.reattachment), bytes.page)
        if (!stillCurrent(root, page)) return
        rows.writing { storeMatch(root, row.id, marker.version.token, bytes.hash, match) }
    }

    private fun hasCurrentMatch(root: RootName, id: DiscussionId, anchorHash: String?, pageHash: String): Boolean =
        rows.cached(root, id)?.let { it.anchorHash == anchorHash && it.pageHash == pageHash } == true

    private fun stillCurrent(root: RootName, page: IndexedPage): Boolean =
        current().pageAt(RootedPageId(root, page.id))?.let { it.path == page.path && it.contentHash == page.contentHash } == true

    private fun retry(key: RootedPageId, page: IndexedPage, priorFailures: Int, failure: Exception) {
        logger.warn(failure) { "anchor pre-compute failed for root ${key.root.value}, page ${page.id.value}" }
        val failures = priorFailures + 1
        val token = synchronized(lock) {
            val token = ++retryToken
            retries[key] = Triple(page, failures, token)
            token
        }
        val delay = retryDelay(failures)
        try {
            alarm.after(delay) { retryReady(key, token) }
        } catch (scheduleFailure: InterruptedException) {
            synchronized(lock) {
                if (retries[key]?.third == token) retries.remove(key)
            }
            Thread.currentThread().interrupt()
            throw scheduleFailure
        } catch (scheduleFailure: Exception) {
            synchronized(lock) {
                if (!closed && retries[key]?.third == token) {
                    retries[key] = Triple(page, failures, READY_RETRY_TOKEN)
                }
            }
            logger.error(scheduleFailure) { "could not schedule anchor pre-compute retry for page ${page.id.value}" }
        }
    }

    private fun retryReady(key: RootedPageId, token: Long) {
        synchronized(lock) {
            dropUnavailableLocked()
            val retry = retries[key] ?: return
            if (closed || retry.third != token) return
            // Zero marks a retry whose own delay elapsed; positive tokens reject stale callbacks.
            retries[key] = Triple(retry.first, retry.second, READY_RETRY_TOKEN)
            armLocked(0)
        }
    }

    private fun retryDelay(failures: Int): Long {
        var delay = RETRY_BASE_MILLIS
        repeat((failures - 1).coerceAtMost(30)) { delay = (delay * 2).coerceAtMost(RETRY_CAP_MILLIS) }
        return delay.coerceAtMost(RETRY_CAP_MILLIS)
    }

    private companion object {
        const val PAGE_BATCH_SIZE = 200
        const val RETRY_BASE_MILLIS = 1_000L
        const val RETRY_CAP_MILLIS = 300_000L
        const val READY_RETRY_TOKEN = 0L
        val logger = KotlinLogging.logger {}
    }
}
