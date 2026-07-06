package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.PercentCoding

/**
 * Decodes a ListObjectsV2 key returned under `encoding-type=url` back to its raw byte-form.
 *
 * The wire encodes the WHOLE key, `/` separators included, as `%2F` (unlike a per-segment
 * PB-LINK-1 decode, which refuses `%2F`), so this uses [PercentCoding.decodeOnce]'s opt-in
 * `allowEncodedSlash` mode: every `%XX` decodes byte-wise (incl. `%2F` -> `/`), the bytes decode as
 * strict UTF-8, and `+` stays a literal byte (S3 emits `%20` for space, never `+`). This is decode
 * ONLY — the real safety check is the downstream `TreePath` funnel (C4's LIST-key validation, R8),
 * so it deliberately does no traversal/containment vetting of its own.
 */
object S3WireKey {

    /** The raw key, or an [ObjectStoreException] when the wire key is not decodable (a malformed response). */
    fun decode(encoded: String): String =
        when (val result = PercentCoding.decodeOnce(encoded, allowEncodedSlash = true)) {
            is PercentCoding.DecodeResult.Success -> result.value
            is PercentCoding.DecodeResult.Failure ->
                throw ObjectStoreException("undecodable ListObjectsV2 key '$encoded' (${result.error})")
        }
}
