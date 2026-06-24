@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.domain.page

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A proposal's stable identity (Phase 5, chunk P1a): a [Uuid] carried as canonical lowercase hyphenated
 * text and 16 raw bytes — the [PageId] shape, but a DISTINCT type so a [ProposalId] can never be passed
 * where a [PageId] is expected (and vice versa). Minted by [com.plainbase.domain.service.ProposalIdProvider]
 * over the stdlib UUIDv7 generator; stored as the 16-byte `msb||lsb` BLOB form
 * ([com.plainbase.frameworks.sqldelight.ProposalIdColumnAdapter]).
 *
 * Like [PageId] it is version-agnostic — uniqueness is the contract. A plain class (NOT `data`) with
 * hand-written [equals]/[hashCode] over [uuid]; pure domain code, the only carrier is [kotlin.uuid.Uuid].
 */
class ProposalId private constructor(
    /** The underlying UUID; the canonical lowercase text is [value], the 16 raw bytes are [toByteArray]. */
    val uuid: Uuid,
) {

    /** The canonical lowercase hyphenated text form. */
    val value: String get() = uuid.toString()

    /** The 16 raw bytes, `msb||lsb` big-endian — the at-rest BLOB form. */
    fun toByteArray(): ByteArray = uuid.toByteArray()

    override fun equals(other: Any?): Boolean = other is ProposalId && other.uuid == uuid

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = value

    companion object {

        /** Parses [text] as a [ProposalId], or returns null when it is not a recognizable UUID string. */
        fun of(text: String): ProposalId? = Uuid.parseOrNull(text)?.let(::ProposalId)

        /** Like [of] but throws [IllegalArgumentException] on unparseable input. */
        fun require(text: String): ProposalId = requireNotNull(of(text)) { "not a valid UUID: '$text'" }

        /** Wraps an already-constructed [uuid] (e.g. [com.plainbase.domain.service.UuidV7ProposalIdProvider] output). */
        fun of(uuid: Uuid): ProposalId = ProposalId(uuid)

        /** Decodes 16 raw `msb||lsb` big-endian [bytes] (the at-rest BLOB form) into a [ProposalId]. */
        fun fromByteArray(bytes: ByteArray): ProposalId = ProposalId(Uuid.fromByteArray(bytes))
    }
}
