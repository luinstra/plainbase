package com.plainbase.domain.service

import com.plainbase.domain.page.PageId

/**
 * Deterministic [IdProvider] for tests: hands back canonical, v7-shaped [PageId]s in a fixed,
 * monotonically increasing sequence. A test that needs a known minted id gets one with no clock or
 * RNG mocking — the whole point of putting minting behind a port. Successive calls return distinct
 * ids (the counter is the low 12 hex digits) that are version 7 / variant 10.
 */
class TestIdProvider(private var counter: Int = 1) : IdProvider {

    override fun next(): PageId = PageId.require("01900000-0000-7000-8000-%012d".format(counter++))
}
