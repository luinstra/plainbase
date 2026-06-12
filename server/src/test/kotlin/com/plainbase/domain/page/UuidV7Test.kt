package com.plainbase.domain.page

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random

/**
 * UuidV7 generator (§A4 chunk-4a acceptance): the version/variant assertions and timestamp-prefix
 * ordering, with an injected fixed clock + random so the output is exact and deterministic. These
 * guarantees apply ONLY to our own generator — PageId acceptance is version-agnostic (PageIdTest).
 */
class UuidV7Test : FunSpec({

    fun fixedClock(epochMillis: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)

    /** A seeded java.util.Random — deterministic, so the minted id is exact for a given clock+seed. */
    fun seededRandom(seed: Long): Random = Random(seed)

    test("output is canonical-shape and the version nibble is 7, variant bits 10") {
        val id = UuidV7(fixedClock(1_700_000_000_000L), seededRandom(42)).next()
        // Belt-and-suspenders: the canonical-shape regex (via PageId.of) accepts the toString form.
        PageId.of(id.value) shouldBe id
        id.uuid.version() shouldBe 7
        id.uuid.variant() shouldBe 2 // java.util.UUID reports the RFC 4122 (0b10) variant as 2
    }

    test("the high 48 bits carry the millisecond timestamp") {
        val millis = 1_700_000_000_000L
        val id = UuidV7(fixedClock(millis), seededRandom(7)).next()
        // msb >>> 16 is the 48-bit timestamp; reconstruct and compare.
        val timestamp = id.uuid.mostSignificantBits ushr 16
        timestamp shouldBe millis
    }

    test("ids minted in different milliseconds sort by time (timestamp-prefix ordering)") {
        val random = seededRandom(99)
        val earlier = UuidV7(fixedClock(1_700_000_000_000L), random).next()
        val later = UuidV7(fixedClock(1_700_000_000_001L), random).next()
        // Lexicographic order of the canonical text follows time order (the right-edge-insert property).
        (earlier.value < later.value) shouldBe true
    }

    test("UUID.fromString(id.toString()) round-trips") {
        val id = UuidV7(fixedClock(1_700_000_000_000L), seededRandom(1)).next()
        java.util.UUID.fromString(id.value) shouldBe id.uuid
    }

    test("a fixed clock+random yields a stable id; a different random yields a different id") {
        val a = UuidV7(fixedClock(1_700_000_000_000L), seededRandom(5)).next()
        val b = UuidV7(fixedClock(1_700_000_000_000L), seededRandom(5)).next()
        a shouldBe b
        val c = UuidV7(fixedClock(1_700_000_000_000L), seededRandom(6)).next()
        a shouldNotBe c
    }
})
