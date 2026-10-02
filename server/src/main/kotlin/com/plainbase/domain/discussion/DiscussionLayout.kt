package com.plainbase.domain.discussion

import com.plainbase.domain.content.Nfc

const val RESERVED_COLLECTION_ROOT = ".plainbase"
const val COLLECTION_DIR = "discussions"
const val MARKER_NAME = "discussion.md"
const val MAX_MARKER_BYTES = 524_288
const val MAX_COMMENT_BYTES = 73_728
const val MAX_COMMENT_ENTRIES = 1_000

sealed interface EntryName {
    val fileName: String
    val cap: Int

    data object Marker : EntryName {
        override val fileName: String = MARKER_NAME
        override val cap: Int = MAX_MARKER_BYTES
    }

    data class Comment(val id: CommentId) : EntryName {
        override val fileName: String = "${id.value}.md"
        override val cap: Int = MAX_COMMENT_BYTES
    }

    companion object {
        fun parse(fileName: String): EntryName? {
            if (fileName == MARKER_NAME) return Marker
            if (!fileName.endsWith(".md")) return null
            val id = CommentId.of(fileName.removeSuffix(".md")) ?: return null
            return Comment(id)
        }
    }
}

fun isReservedTopSegment(segment: String): Boolean =
    Nfc.normalize(segment).equals(RESERVED_COLLECTION_ROOT, ignoreCase = true)
