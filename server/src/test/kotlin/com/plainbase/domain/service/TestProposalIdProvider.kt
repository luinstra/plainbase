package com.plainbase.domain.service

import com.plainbase.domain.page.ProposalId

/**
 * Deterministic [ProposalIdProvider] for tests (the [TestIdProvider] shape): hands back canonical, v7-shaped
 * [ProposalId]s in a fixed, monotonically increasing sequence so a test that needs a known minted id gets one
 * with no clock/RNG mocking.
 */
class TestProposalIdProvider(private var counter: Int = 1) : ProposalIdProvider {

    override fun next(): ProposalId = ProposalId.require("01900000-0000-7000-9000-%012d".format(counter++))
}
