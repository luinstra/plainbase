package com.plainbase.domain.content

import java.text.Normalizer

/**
 * The single NFC (Normalization Form C) helper that ALL path code calls.
 *
 * Plainbase's boundary rule is "decode strictly precedes normalization" and
 * "everything humans, files, URLs, and agents see is NFC". To keep that rule
 * honest there is exactly one normalization call site for paths — this object.
 * No other path code may invoke [Normalizer] directly (see chunk 1.5 Rule).
 *
 * NFC is idempotent: `nfc(nfc(x)) == nfc(x)`.
 */
object Nfc {

    /** Returns [value] in Unicode Normalization Form C. */
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    /** True when [value] is already in NFC (i.e. normalization would be a no-op). */
    fun isNormalized(value: String): Boolean = Normalizer.isNormalized(value, Normalizer.Form.NFC)
}
