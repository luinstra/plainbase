package com.plainbase.domain.page

import java.util.UUID

/**
 * A page's stable identity: a UUID carried in canonical lowercase hyphenated text and 16 raw bytes.
 *
 * **Version-agnostic by owner ruling (§A4, frozen):** a [PageId] validates only the canonical
 * *shape* (8-4-4-4-12 hex, case-insensitive) — **any** UUID version is a valid id. Uniqueness is the
 * contract; version is mere provenance (imported trees may legitimately carry v4/v5 ids). The v7
 * version/variant guarantees apply ONLY to [UuidV7]'s own output, never to acceptance here.
 *
 * **Spec-owned shape gate, not JDK leniency (§A4, frozen):** parsing goes through the canonical-shape
 * regex *before* [UUID.fromString], because `fromString` leniently accepts non-canonical forms (e.g.
 * `1-1-1-1-1`) and its leniency could drift across JDK releases — neither may ever leak into the
 * 400-vs-404 boundary, the frontmatter `id` validity test, or citation parsing. Shape-invalid input
 * is rejected here; a shape-valid id of any version is accepted (and is `page_not_found`, not a
 * parse error, when absent from the index).
 *
 * The text form ([value]) is always lowercase; [of] parses case-insensitively. Pure domain code: the
 * only carrier is [java.util.UUID]; no framework or persistence type appears.
 */
class PageId private constructor(
    /** The underlying UUID; the canonical lowercase text is [value], the 16 raw bytes are [toByteArray]. */
    val uuid: UUID,
) {

    /** The canonical lowercase hyphenated text form (`UUID.toString` is already lowercase). */
    val value: String get() = uuid.toString()

    /** The 16 raw bytes, `msb||lsb` big-endian — the at-rest BLOB form (chunk 4b adapter). */
    fun toByteArray(): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        return ByteArray(16) { i ->
            val bits = if (i < 8) msb else lsb
            (bits ushr (8 * (7 - (i and 7)))).toByte()
        }
    }

    override fun equals(other: Any?): Boolean = other is PageId && other.uuid == uuid

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = value

    companion object {

        /** The §A4 canonical UUID shape: 8-4-4-4-12 hex, case-insensitive, any version. */
        private val CANONICAL_SHAPE =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * Parses [text] as a [PageId], or returns null when it is not canonical-shape (§A4). Accepts
         * uppercase input (folded to lowercase by [UUID.toString]); rejects every non-canonical form
         * `UUID.fromString` would otherwise tolerate.
         */
        fun of(text: String): PageId? =
            if (CANONICAL_SHAPE.matches(text)) PageId(UUID.fromString(text)) else null

        /** Like [of] but throws [IllegalArgumentException] on a non-canonical-shape input. */
        fun require(text: String): PageId =
            requireNotNull(of(text)) { "not a canonical-shape UUID: '$text'" }

        /** Wraps an already-constructed [uuid] (e.g. [UuidV7] output, or a 16-byte BLOB decode). */
        fun of(uuid: UUID): PageId = PageId(uuid)

        /**
         * Decodes 16 raw `msb||lsb` big-endian [bytes] (the at-rest BLOB form) into a [PageId].
         * Requires exactly 16 bytes — the storage adapter's only valid input.
         */
        fun fromByteArray(bytes: ByteArray): PageId {
            require(bytes.size == 16) { "a PageId is exactly 16 bytes, got ${bytes.size}" }
            var msb = 0L
            var lsb = 0L
            for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
            for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
            return PageId(UUID(msb, lsb))
        }
    }
}
