@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The production [IdProvider]: monotonic, time-ordered UUIDv7 ids (RFC 9562) straight from the
 * Kotlin stdlib generator. Time-ordered ids sort by creation instant — the right-edge-insert
 * property the BLOB primary key wants (chunk 4b) — and the generator is monotonic within a
 * millisecond, keeping bulk `adopt` mints locally ordered. Owner ratified UUIDv7 over ULID before
 * the Phase-1 contracts froze (memory: id-format-preference).
 *
 * Test determinism comes from injecting a fake [IdProvider], not from parameterizing this — so the
 * production path stays a trivial delegation to a battle-tested generator.
 */
class UuidV7IdProvider : IdProvider {

    override fun next(): PageId = PageId.of(Uuid.generateV7())
}
