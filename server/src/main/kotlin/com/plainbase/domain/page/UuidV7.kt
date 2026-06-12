package com.plainbase.domain.page

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Plainbase's own UUIDv7 generator (RFC 9562): a 48-bit Unix-millisecond timestamp in the high bits,
 * the version nibble `7`, the variant bits `10`, and a `SecureRandom` tail. Time-ordered ids sort by
 * creation instant — the right-edge-insert property the BLOB primary key wants (chunk 4b).
 *
 * Owner ratified UUIDv7 over ULID before the Phase-1 contracts froze (memory: id-format-preference).
 * **Zero new dependencies** — hand-rolled bit layout over [java.util.UUID]; the v7 version/variant
 * assertions in §A4 are guarantees about *this* output only (not about ids parsed by [PageId.of]).
 *
 * [Clock] and [java.util.Random] are injectable so tests can freeze the millisecond and the tail — a
 * fixed clock pins timestamp-prefix ordering, a fixed random pins the exact id. Default construction
 * uses the system UTC clock and a [SecureRandom].
 */
class UuidV7(
    private val clock: Clock = Clock.systemUTC(),
    private val random: java.util.Random = SecureRandom(),
) {

    /**
     * Mints a fresh time-ordered [PageId]. Bit layout (RFC 9562 §5.7): bits 0–47 the millisecond
     * timestamp; bits 48–51 the version (`0b0111`); bits 52–63 + 0–61 of the low half random; bits
     * 62–63 the variant (`0b10`).
     */
    fun next(): PageId {
        val millis = clock.millis()
        val randA = random.nextInt(0x1000) // 12 random bits (rand_a)
        var msb = (millis and 0xFFFFFFFFFFFFL) shl 16
        msb = msb or (VERSION shl 12) or randA.toLong()

        var lsb = random.nextLong()
        lsb = (lsb and LOW_62_BITS) or VARIANT // top two bits to 0b10

        return PageId.of(UUID(msb, lsb))
    }

    private companion object {
        /** Version nibble 7, positioned at bits 12–15 of the high half. */
        const val VERSION = 0x7L

        /** Variant `0b10` in the top two bits of the low half. */
        const val VARIANT = Long.MIN_VALUE // 0x8000_0000_0000_0000

        /** Mask clearing the top two bits of the low half before the variant is set. */
        const val LOW_62_BITS = 0x3FFFFFFFFFFFFFFFL
    }
}
