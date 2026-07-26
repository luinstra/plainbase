package com.plainbase.domain.content

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Strict RFC 3986 percent-coding for path/segment data — the SINGLE percent
 * decoder/encoder for all path code (chunk 1.5 Rule: no second decoder anywhere).
 *
 * This is the frozen home of PB-LINK-1's "decode-once" decoding rules (§A2 step 2);
 * full link-resolution outcomes live in chunk 2, but the decode-LEVEL behavior is
 * pinned here. Decoding is byte-level and happens **exactly once**:
 *
 *  - Each `%XX` escape decodes to one raw byte; the resulting byte sequence is then
 *    decoded as **strict UTF-8**. Any invalid sequence (e.g. a lone `%E9`) is rejected.
 *  - Decoded characters are literal data and are **never re-scanned**: `%252e` decodes
 *    once to the three literal characters `%2e` and stops. Double-encoded traversal
 *    cannot exist by construction.
 *  - Encoded slash `%2F`/`%2f` is **rejected** — an encoded slash is segment data per
 *    RFC 3986, but `/` is impossible in a filename and letting encoding change path
 *    structure is unsafe. Frozen rejection for the PB-LINK-1 (per-segment) decode. The one
 *    documented exception is [decodeOnce]'s opt-in `allowEncodedSlash` for decoding a WHOLE
 *    S3 `encoding-type=url` key, where the wire itself percent-encodes the `/` separators.
 *  - Malformed escape syntax (`%G1`, a trailing `%`, `%A`) is rejected.
 *  - `java.net.URLDecoder` is explicitly NOT used: it maps `+`→space. This decoder
 *    performs **no** `+`→space translation; `+` is a literal byte.
 */
object PercentCoding {

    /** Why a percent-decode failed. All cases map to PB-LINK-1 `broken_malformed`. */
    enum class DecodeError {
        /** Malformed `%XX` escape syntax: a `%` not followed by two hex digits. */
        MALFORMED_ESCAPE,

        /** An encoded slash (`%2F`/`%2f`) — rejected by frozen policy. */
        ENCODED_SLASH,

        /** Not valid strict UTF-8: a lone `%E9` in escaped bytes, or an unpaired surrogate in literal input. */
        INVALID_UTF8,
    }

    /** Outcome of [decodeOnce]: either the decoded string or a [DecodeError]. */
    sealed interface DecodeResult {
        data class Success(val value: String) : DecodeResult
        data class Failure(val error: DecodeError) : DecodeResult
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val PERCENT_ESCAPE_LENGTH = 3
    private const val BITS_PER_HEX_DIGIT = 4
    private const val HEX_ALPHA_OFFSET = 10
    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Decodes [input] EXACTLY ONCE per the frozen rules above.
     *
     * Bytes are accumulated (literal chars as their UTF-8 encoding, `%XX` as the raw
     * byte) then decoded as strict UTF-8 in a single pass — the decoded output is never
     * re-scanned for further escapes.
     *
     * [allowEncodedSlash] is the ONE documented relaxation: default false keeps the frozen
     * per-segment PB-LINK-1 rejection of `%2F`; true decodes `%2F`/`%2f` to `/` for decoding a
     * WHOLE S3 `encoding-type=url` LIST key (where the wire percent-encodes the separators). Every
     * other rule (single decode, no `+`→space, strict UTF-8) is unchanged either way.
     */
    fun decodeOnce(input: String, allowEncodedSlash: Boolean = false): DecodeResult {
        val bytes = ByteArrayOutputStream(input.length)
        var i = 0
        var failure: DecodeError? = null
        while (i < input.length && failure == null) {
            val c = input[i]
            when (c) {
                '%' -> {
                    val hi = input.getOrNull(i + 1)?.let(::hexValue)
                    val lo = input.getOrNull(i + 2)?.let(::hexValue)
                    val byte = hi?.let { high -> lo?.let { low -> (high shl BITS_PER_HEX_DIGIT) or low } }
                    when {
                        byte == null -> failure = DecodeError.MALFORMED_ESCAPE
                        byte == '/'.code && !allowEncodedSlash -> failure = DecodeError.ENCODED_SLASH
                        else -> {
                            bytes.write(byte)
                            i += PERCENT_ESCAPE_LENGTH
                        }
                    }
                }

                else -> {
                    val start = i
                    i++
                    while (i < input.length && input[i] != '%') i++
                    when (val runBytes = strictUtf8Encode(input.substring(start, i))) {
                        null -> failure = DecodeError.INVALID_UTF8
                        else -> bytes.write(runBytes)
                    }
                }
            }
        }

        return when (failure) {
            null -> strictUtf8Decode(bytes.toByteArray())
                ?.let(DecodeResult::Success)
                ?: DecodeResult.Failure(DecodeError.INVALID_UTF8)
            else -> DecodeResult.Failure(failure)
        }
    }

    /**
     * Encodes [value] per RFC 3986 for a single path segment: bytes outside the
     * unreserved set are percent-encoded as uppercase `%XX`; `/` is encoded too (the
     * caller joins already-encoded segments). UTF-8 byte basis; never emits `+`.
     */
    fun encodeSegment(value: String): String = buildString(value.length) {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt() and 0xFF
            if (ch.toChar() in UNRESERVED) {
                append(ch.toChar())
            } else {
                append('%')
                append(HEX[ch ushr BITS_PER_HEX_DIGIT])
                append(HEX[ch and 0x0F])
            }
        }
    }

    /**
     * Encodes [value] as a `/`-separated path: each segment is [encodeSegment]'d and the
     * separators are preserved. The leading/trailing/empty-segment structure is kept.
     */
    fun encodePath(value: String): String = value.split('/').joinToString("/") { encodeSegment(it) }

    /**
     * Strict UTF-8 encode of literal (non-escaped) input: returns null if [s] contains an unpaired
     * surrogate. A valid surrogate pair encodes to its 4-byte form; a lone surrogate is rejected
     * rather than silently replaced with U+FFFD, so [decodeOnce]'s "strict" promise holds on the
     * literal side too.
     */
    private fun strictUtf8Encode(s: String): ByteArray? {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val encoded = encoder.encode(CharBuffer.wrap(s))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Strict UTF-8 decode: returns null on any malformed or unmappable input. */
    private fun strictUtf8Decode(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hexValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + HEX_ALPHA_OFFSET
        in 'A'..'F' -> c - 'A' + HEX_ALPHA_OFFSET
        else -> null
    }
}
