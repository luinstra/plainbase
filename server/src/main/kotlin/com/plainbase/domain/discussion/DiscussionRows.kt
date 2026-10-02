package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

data class Stamp(val value: String, val racy: Boolean = false)

data class DiscussionRow(
    val root: RootName,
    val id: DiscussionId,
    val state: String,
    val reason: String?,
    val stamp: Stamp?,
    val pageId: PageId?,
    val pagePath: TreePath?,
    val status: String?,
    val anchorKind: String?,
    val anchorHash: String?,
    val starterKey: String?,
    val created: Long?,
    val updated: Long?,
    val commentCount: Int,
    val starterKind: String? = null,
    val starterLabel: String? = null,
    val quotePreview: String? = null,
)

data class EntryRow(
    val root: RootName,
    val id: DiscussionId,
    val name: EntryName,
    val sha256: String,
    val version: String,
    val authorKey: String,
)

data class CachedMatch(
    val anchorHash: String,
    val pageHash: String,
    val match: AnchorMatch,
)

data class DiscussionRowData(
    val state: String,
    val reason: String? = null,
    val pageId: PageId? = null,
    val pagePath: TreePath? = null,
    val status: String? = null,
    val anchorKind: String? = null,
    val anchorHash: String? = null,
    val starterKey: String? = null,
    val created: Long? = null,
    val updated: Long? = null,
    val commentCount: Int = 0,
    val starterKind: String? = null,
    val starterLabel: String? = null,
    val quotePreview: String? = null,
)

sealed interface RowUpdate {
    val state: String?

    data object Delete : RowUpdate {
        override val state: String? = null
    }

    data class Unknown(val cause: String) : RowUpdate {
        override val state: String? = null
    }

    data class Failed(val reason: String) : RowUpdate {
        override val state: String = "failed"
    }

    data class Upsert(val row: DiscussionRowData, val entries: List<EntryRowData>) : RowUpdate {
        override val state: String = row.state
    }
}

data class EntryRowData(
    val name: EntryName,
    val sha256: String,
    val version: String,
    val authorKey: String,
)

interface DiscussionRows {
    fun <T> writing(block: DiscussionRowWriter.() -> T): T

    fun <T> tryWriting(block: DiscussionRowWriter.() -> T): T?

    fun pageCount(root: RootName, pageId: PageId): Int

    fun row(root: RootName, id: DiscussionId): DiscussionRow?

    fun entry(root: RootName, id: DiscussionId, name: EntryName): EntryRow?

    fun onPageRaw(root: RootName, pageId: PageId, path: TreePath, after: DiscussionId?, limit: Int): List<DiscussionRow>

    fun ids(root: RootName, after: DiscussionId?, limit: Int): List<DiscussionId>

    fun rowsAfter(root: RootName, after: DiscussionId?, limit: Int): List<DiscussionRow>

    fun cached(root: RootName, id: DiscussionId): CachedMatch?
}

interface DiscussionRowWriter {
    fun truncate()

    fun apply(root: RootName, id: DiscussionId, update: RowUpdate, stamp: Stamp?, dropMatch: Boolean)

    fun storeMatch(root: RootName, id: DiscussionId, anchorHash: String, pageHash: String, match: AnchorMatch)
}
