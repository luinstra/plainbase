package com.plainbase.domain.service

import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.DiscussionAssembly
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionRow
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.PageBytes
import com.plainbase.domain.discussion.Reanchor
import com.plainbase.domain.discussion.Stored
import com.plainbase.domain.discussion.UnreadableReason
import com.plainbase.domain.discussion.effectiveAnchor
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger

data class DiscussionAnchorMatch(val pageId: PageId, val id: DiscussionId, val match: AnchorMatch)

@Suppress("TooGenericExceptionCaught", "SwallowedException")
class AnchorMatches(
    private val rows: DiscussionRows,
    private val discussions: DiscussionStore,
    private val fullReads: DiscussionFullReads,
    private val sync: DiscussionSyncState,
) {
    private val hitCount = AtomicInteger()
    private val computationCount = AtomicInteger()
    private val pageReadCount = AtomicInteger()

    val hits: Int get() = hitCount.get()
    val computations: Int get() = computationCount.get()
    val pageReads: Int get() = pageReadCount.get()

    fun forPage(
        root: RootName,
        pageId: PageId,
        contentHash: String,
        pageRows: List<DiscussionRow>,
        bytes: () -> PageBytes,
    ): List<DiscussionAnchorMatch> {
        sync.current(root)
        val result = ArrayList<DiscussionAnchorMatch>(pageRows.size)
        val misses = ArrayList<DiscussionRow>()
        for (row in pageRows) {
            if (row.root != root || row.state != "ok") continue
            when (row.anchorKind) {
                "page" -> result += DiscussionAnchorMatch(pageId, row.id, AnchorMatch.PageLevel)
                "quote" -> {
                    val cached = if (sync.isUnsynced(root)) {
                        null
                    } else {
                        try {
                            rows.cached(root, row.id)
                        } catch (failure: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw failure
                        } catch (failure: Exception) {
                            sync.enter(root, failure.message ?: "discussion anchor cache query failed")
                            null
                        }
                    }
                    if (cached != null && cached.anchorHash == row.anchorHash && cached.pageHash == contentHash) {
                        hitCount.incrementAndGet()
                        result += DiscussionAnchorMatch(pageId, row.id, cached.match)
                    } else {
                        misses += row
                    }
                }
            }
        }
        if (misses.isEmpty()) return result

        pageReadCount.incrementAndGet()
        val page = bytes()
        for (row in misses) {
            val marker = readMarker(root, row.id) ?: continue
            val match = Reanchor.match(effectiveAnchor(marker.value.anchor, marker.value.reattachment), page.page)
            computationCount.incrementAndGet()
            try {
                rows.tryWriting {
                    storeMatch(root, row.id, marker.version.token, page.hash, match)
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                logger.warn(failure) {
                    "discussion match cache write failed for root ${root.value}, discussion ${row.id.value}"
                }
                sync.enter(root, failure.message ?: "discussion match cache write failed")
            }
            result += DiscussionAnchorMatch(pageId, row.id, match)
        }
        return result
    }

    private fun readMarker(root: RootName, id: DiscussionId): Stored<DiscussionRecord>? {
        val assembled = try {
            fullReads.withNarrowed {
                val read = try {
                    discussions.read(root, id, setOf(EntryName.Marker))
                } catch (failure: RootUnavailable) {
                    throw failure
                } catch (failure: InterruptedException) {
                    throw failure
                } catch (failure: Exception) {
                    throw DiscussionReadFailed(root, failure.message ?: "anchor marker read failed")
                }
                DiscussionAssembly.assemble(id, read, retain = { false })
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        return when (assembled) {
            is DiscussionRead.Ok -> assembled.files.marker
            is DiscussionRead.Failed -> throw DiscussionReadFailed(
                root,
                "anchor marker unavailable for ${id.value}",
            )
            is DiscussionRead.Unreadable -> {
                if (assembled.reason == UnreadableReason.SYMLINK && assembled.entry == EntryName.Marker.fileName) {
                    throw DiscussionReadFailed(root, "anchor marker unavailable for ${id.value}")
                }
                null
            }
            DiscussionRead.Absent, is DiscussionRead.Incomplete -> null
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
