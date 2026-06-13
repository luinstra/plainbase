@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.domain.page

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A page's stable identity: a [Uuid] carried as canonical lowercase hyphenated text and 16 raw bytes.
 *
 * **Version-agnostic by owner ruling (§A4):** identity is the UUID value itself; the version nibble
 * is mere provenance (an imported tree may legitimately carry v4/v5 ids). Uniqueness is the contract.
 *
 * Parsing leans on [Uuid.parseOrNull] — it accepts the standard 36-char hyphenated form (and the
 * 32-char hex form) and returns null for anything else. Non-UUID text is therefore `null` here and
 * surfaces as page_not_found (not a parse error) when absent from the index. [value] is always
 * lowercase. Pure domain code: the only carrier is [kotlin.uuid.Uuid].
 */
class PageId private constructor(
    /** The underlying UUID; the canonical lowercase text is [value], the 16 raw bytes are [toByteArray]. */
    val uuid: Uuid,
) {

    /** The canonical lowercase hyphenated text form. */
    val value: String get() = uuid.toString()

    /** The 16 raw bytes, `msb||lsb` big-endian — the at-rest BLOB form (chunk 4b adapter). */
    fun toByteArray(): ByteArray = uuid.toByteArray()

    override fun equals(other: Any?): Boolean = other is PageId && other.uuid == uuid

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = value

    companion object {

        /** Parses [text] as a [PageId], or returns null when it is not a recognizable UUID string. */
        fun of(text: String): PageId? = Uuid.parseOrNull(text)?.let(::PageId)

        /** Like [of] but throws [IllegalArgumentException] on unparseable input. */
        fun require(text: String): PageId = requireNotNull(of(text)) { "not a valid UUID: '$text'" }

        /** Wraps an already-constructed [uuid] (e.g. [com.plainbase.domain.service.UuidV7IdProvider] output). */
        fun of(uuid: Uuid): PageId = PageId(uuid)

        /** Decodes 16 raw `msb||lsb` big-endian [bytes] (the at-rest BLOB form) into a [PageId]. */
        fun fromByteArray(bytes: ByteArray): PageId = PageId(Uuid.fromByteArray(bytes))
    }
}
