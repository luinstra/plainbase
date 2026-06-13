package com.plainbase.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The production [IdProvider]. We don't re-test the stdlib UUIDv7 generator — only that our thin
 * delegation yields ids of the right shape and that successive mints are distinct and time-ordered
 * (the right-edge-insert property the BLOB key relies on). The version/variant nibbles are read
 * straight off the canonical text so the test stays clear of the experimental Uuid API.
 */
class UuidV7IdProviderTest : FunSpec({

    val provider = UuidV7IdProvider()

    test("mints canonical-shape version-7 ids (variant 10)") {
        val value = provider.next().value
        value[14] shouldBe '7' // version nibble — first hex digit of the third group
        value[19] shouldBeIn listOf('8', '9', 'a', 'b') // variant 10xx — first hex digit of the fourth group
    }

    test("successive mints are distinct and time-ordered") {
        val first = provider.next()
        val second = provider.next()
        first shouldNotBe second
        // The 48-bit millisecond prefix is non-decreasing, so the canonical text sorts by time.
        (first.value <= second.value) shouldBe true
    }
})
