package com.plainbase.domain.content

import java.util.Arrays

/**
 * Lexicographic UNSIGNED-byte order over a name's UTF-8 bytes — the single deterministic,
 * platform-stable winner rule. "The entry whose raw on-disk name bytes sort first" means exactly
 * this order in both places the spec says it: the B3 NFC path-collision policy (chunk 1,
 * `LocalContentStore`) and the §A4 same-parent slug-collision policy (chunk 5,
 * `CanonicalUrlBuilder`).
 *
 * Unsigned matters: a multi-byte UTF-8 lead byte (e.g. `0xC3` in NFC `é`) must sort AFTER every
 * ASCII byte, which a signed comparison would invert.
 */
object RawByteOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int =
        Arrays.compareUnsigned(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
}
