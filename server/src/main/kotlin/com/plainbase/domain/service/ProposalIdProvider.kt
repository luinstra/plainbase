package com.plainbase.domain.service

import com.plainbase.domain.page.ProposalId

/**
 * Mints fresh, unique proposal identities (Phase 5, chunk P1a). A SEPARATE port from [IdProvider], which
 * is typed to [com.plainbase.domain.page.PageId] (`next(): PageId`) — wiring it to mint a [ProposalId]
 * would not type-check. Both ports sit over the SAME stdlib UUIDv7 generator; this is a thin sibling, not
 * a new id mechanism. The production impl ([UuidV7ProposalIdProvider]) delegates to the stdlib generator;
 * tests inject a deterministic fake.
 */
fun interface ProposalIdProvider {

    /** A freshly minted [ProposalId]. Successive calls return distinct ids. */
    fun next(): ProposalId
}
