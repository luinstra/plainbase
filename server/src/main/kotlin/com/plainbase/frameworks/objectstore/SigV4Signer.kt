package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.PercentCoding
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hand-rolled AWS Signature Version 4 request signing (header-based auth, single-chunk signed
 * payloads) - the Q7 decision: JDK-only crypto (`javax.crypto.Mac` + `MessageDigest`), zero SDK.
 *
 * Scope is deliberately the S3-shaped subset this codebase needs, frozen by golden vectors:
 * - canonical URIs are SINGLE-encoded (the S3 rule; other services double-encode),
 *   via the domain's [PercentCoding] (the RFC 3986 unreserved set, uppercase hex),
 * - query pairs are encoded the same way and sorted by encoded key, then encoded value,
 * - every header the caller passes is signed (lowercased, sorted, values trimmed with inner
 *   whitespace runs collapsed), and
 * - the payload hash is always an explicit `x-amz-content-sha256` hex digest (R2 requires it;
 *   `UNSIGNED-PAYLOAD` is deliberately not offered).
 *
 * Not in scope (v1, per plan Q7): STS session tokens, chunked/streaming signing, presigned URLs.
 *
 * The signature path is a crypto divergence surface, so its test-vector suite runs under the
 * native image: spike check #9 (`s3-sigv4-vector`) plus SigV4SignerNativeTest (kotlin.test,
 * `@Tag("native")`), both pinned to published AWS S3 documentation vectors.
 */
class SigV4Signer(
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String,
    private val service: String = "s3",
) {

    /** The signed artifacts: [authorization] is what goes on the wire; the rest are test seams. */
    data class Signed(val authorization: String, val canonicalRequest: String, val stringToSign: String, val signature: String)

    /**
     * Signs one request. [canonicalPath] is the already-encoded absolute path (see [uriEncodePath]);
     * [query] pairs are RAW (this method encodes and sorts them); [headers] are exactly the headers
     * the caller will send, `host` and `x-amz-date` and `x-amz-content-sha256` included - all of
     * them are signed. [amzDate] is the ISO-basic UTC timestamp (`yyyyMMdd'T'HHmmss'Z'`).
     */
    fun sign(
        method: String,
        canonicalPath: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadSha256: String,
        amzDate: String,
    ): Signed {
        val canonicalQuery = query
            .map { (name, value) -> PercentCoding.encodeSegment(name) to PercentCoding.encodeSegment(value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }
        val canonicalHeaders = headers.entries
            .map { (name, value) -> name.lowercase() to value.trim().replace(WHITESPACE_RUN, " ") }
            .sortedBy { it.first }
        val signedHeaderNames = canonicalHeaders.joinToString(";") { it.first }
        val canonicalRequest = buildString {
            append(method).append('\n')
            append(canonicalPath).append('\n')
            append(canonicalQuery).append('\n')
            canonicalHeaders.forEach { (name, value) -> append(name).append(':').append(value).append('\n') }
            append('\n')
            append(signedHeaderNames).append('\n')
            append(payloadSha256)
        }

        val dateStamp = amzDate.substringBefore('T')
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"

        val signingKey = listOf(dateStamp, region, service, "aws4_request")
            .fold("AWS4$secretAccessKey".toByteArray()) { key, part -> hmacSha256(key, part.toByteArray()) }
        val signature = hmacSha256(signingKey, stringToSign.toByteArray()).toHex()

        val authorization = "$ALGORITHM Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaderNames, Signature=$signature"
        return Signed(authorization, canonicalRequest, stringToSign, signature)
    }

    companion object {
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private val WHITESPACE_RUN = Regex("[ \t]+")

        /**
         * SigV4 canonical-URI encoding of an S3 object key: each `/`-separated segment
         * percent-encoded once over the RFC 3986 unreserved set, separators preserved,
         * leading `/` supplied. Single-encoded per the S3 rule (never re-encoded).
         */
        fun uriEncodePath(key: String): String = "/" + PercentCoding.encodePath(key)

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(message)
            }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
