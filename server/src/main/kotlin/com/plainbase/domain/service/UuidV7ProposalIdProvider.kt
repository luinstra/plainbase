@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.page.ProposalId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The production [ProposalIdProvider]: monotonic, time-ordered UUIDv7 ids (RFC 9562) straight from the
 * Kotlin stdlib generator — the [UuidV7IdProvider] shape, minting a [ProposalId] rather than a `PageId`.
 * Time-ordered ids sort by creation instant, the right-edge-insert property the BLOB primary key wants.
 *
 * Test determinism comes from injecting a fake [ProposalIdProvider], not from parameterizing this — so the
 * production path stays a trivial delegation to a battle-tested generator.
 */
class UuidV7ProposalIdProvider : ProposalIdProvider {

    override fun next(): ProposalId = ProposalId.of(Uuid.generateV7())
}
