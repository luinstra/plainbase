@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.domain.discussion

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val CANONICAL_UUID_LENGTH = 36
private const val UUID_VERSION_INDEX = 14
private const val UUID_VARIANT_INDEX = 19

/** A discussion identity encoded as canonical lowercase hyphenated UUIDv7 text. */
class DiscussionId private constructor(val uuid: Uuid) {
    /** The canonical lowercase hyphenated UUID text. */
    val value: String get() = uuid.toString()

    override fun equals(other: Any?): Boolean = other is DiscussionId && other.uuid == uuid
    override fun hashCode(): Int = uuid.hashCode()
    override fun toString(): String = value

    companion object {

        /** Parses [text] when it is a canonical lowercase UUIDv7 with the RFC variant, or returns null. */
        fun of(text: String): DiscussionId? = parseCanonicalV7(text)?.let(::DiscussionId)

        /** Parses [text] like [of], throwing [IllegalArgumentException] when it is not a canonical UUIDv7. */
        fun require(text: String): DiscussionId = requireNotNull(of(text)) { "not a valid discussion id: '$text'" }

        /** Wraps a UUID after checking its version and variant. */
        fun of(uuid: Uuid): DiscussionId = uuid.toString().let { text ->
            require(hasV7VersionAndVariant(text)) { "not a canonical UUIDv7 discussion id: '$text'" }
            DiscussionId(uuid)
        }
    }
}

/** A comment identity encoded as canonical lowercase hyphenated UUIDv7 text, distinct from [DiscussionId]. */
class CommentId private constructor(val uuid: Uuid) {
    /** The canonical lowercase hyphenated UUID text. */
    val value: String get() = uuid.toString()

    override fun equals(other: Any?): Boolean = other is CommentId && other.uuid == uuid
    override fun hashCode(): Int = uuid.hashCode()
    override fun toString(): String = value

    companion object {

        /** Parses [text] when it is a canonical lowercase UUIDv7 with the RFC variant, or returns null. */
        fun of(text: String): CommentId? = parseCanonicalV7(text)?.let(::CommentId)

        /** Parses [text] like [of], throwing [IllegalArgumentException] when it is not a canonical UUIDv7. */
        fun require(text: String): CommentId = requireNotNull(of(text)) { "not a valid comment id: '$text'" }

        /** Wraps a UUID after checking its version and variant. */
        fun of(uuid: Uuid): CommentId = uuid.toString().let { text ->
            require(hasV7VersionAndVariant(text)) { "not a canonical UUIDv7 comment id: '$text'" }
            CommentId(uuid)
        }
    }
}

private fun parseCanonicalV7(text: String): Uuid? {
    if (!hasV7VersionAndVariant(text)) return null
    val uuid = Uuid.parseOrNull(text) ?: return null
    return uuid.takeIf { it.toString() == text }
}

private fun hasV7VersionAndVariant(text: String): Boolean =
    text.length == CANONICAL_UUID_LENGTH && text[UUID_VERSION_INDEX] == '7' && text[UUID_VARIANT_INDEX] in "89ab"
