package com.plainbase.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The production [ProposalIdProvider] — a thin delegation to the stdlib UUIDv7 generator (the
 * [UuidV7IdProviderTest] shape). We assert only the shape + distinctness + time-ordering of our wrapper.
 */
class UuidV7ProposalIdProviderTest : FunSpec({

    val provider = UuidV7ProposalIdProvider()

    test("mints canonical-shape version-7 ids (variant 10)") {
        val value = provider.next().value
        value[14] shouldBe '7'
        value[19] shouldBeIn listOf('8', '9', 'a', 'b')
    }

    test("successive mints are distinct and time-ordered") {
        val first = provider.next()
        val second = provider.next()
        first shouldNotBe second
        (first.value <= second.value) shouldBe true
    }
})
