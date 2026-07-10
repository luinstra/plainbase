package com.plainbase.frameworks.objectstore

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SigV4 golden vectors + canonical-request seams. Crypto (HMAC-SHA256/SHA-256) and charset
 * (percent-encoding over UTF-8 bytes) are native divergence surfaces, so this suite is
 * `@Tag("native")` kotlin.test and runs inside the native image (test policy, CLAUDE.md
 * §Verification). The vectors are the published AWS S3 API-reference examples
 * (header-based-auth), independently reproduced against a reference implementation before
 * being pinned here; spike check #9 carries the same three signatures as the in-binary gate.
 */
@Tag("native")
class SigV4SignerNativeTest {

    private val signer = SigV4Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "s3")
    private val emptyPayload = SigV4Signer.sha256Hex(ByteArray(0))
    private val amzDate = "20130524T000000Z"
    private val host = "examplebucket.s3.amazonaws.com"

    @Test
    fun `published GET object vector - signature, canonical request, and string to sign`() {
        val signed = signer.sign(
            method = "GET",
            canonicalPath = SigV4Signer.uriEncodePath("test.txt"),
            query = emptyList(),
            headers = mapOf("host" to host, "range" to "bytes=0-9", "x-amz-content-sha256" to emptyPayload, "x-amz-date" to amzDate),
            payloadSha256 = emptyPayload,
            amzDate = amzDate,
        )
        assertEquals(
            """
            GET
            /test.txt

            host:examplebucket.s3.amazonaws.com
            range:bytes=0-9
            x-amz-content-sha256:$emptyPayload
            x-amz-date:$amzDate

            host;range;x-amz-content-sha256;x-amz-date
            $emptyPayload
            """.trimIndent(),
            signed.canonicalRequest,
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n$amzDate\n20130524/us-east-1/s3/aws4_request\n" +
                "7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972",
            signed.stringToSign,
        )
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signed.signature)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed.authorization,
        )
    }

    @Test
    fun `published PUT object vector - non-empty payload hash and a percent-encoded key`() {
        val payload = SigV4Signer.sha256Hex("Welcome to Amazon S3.".toByteArray())
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payload)
        val signed = signer.sign(
            method = "PUT",
            canonicalPath = SigV4Signer.uriEncodePath("test\$file.text"),
            query = emptyList(),
            headers = mapOf(
                "host" to host,
                "date" to "Fri, 24 May 2013 00:00:00 GMT",
                "x-amz-content-sha256" to payload,
                "x-amz-date" to amzDate,
                "x-amz-storage-class" to "REDUCED_REDUNDANCY",
            ),
            payloadSha256 = payload,
            amzDate = amzDate,
        )
        assertEquals("/test%24file.text", signed.canonicalRequest.lines()[1])
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signed.signature)
    }

    @Test
    fun `published GET lifecycle vector - a query parameter with an empty value`() {
        val signed = signer.sign(
            method = "GET",
            canonicalPath = "/",
            query = listOf("lifecycle" to ""),
            headers = mapOf("host" to host, "x-amz-content-sha256" to emptyPayload, "x-amz-date" to amzDate),
            payloadSha256 = emptyPayload,
            amzDate = amzDate,
        )
        assertEquals("lifecycle=", signed.canonicalRequest.lines()[2])
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543", signed.signature)
    }

    @Test
    fun `published list objects vector - query pairs sort by encoded key`() {
        val signed = signer.sign(
            method = "GET",
            canonicalPath = "/",
            // Handed over unsorted on purpose: the golden only reproduces if the signer sorts.
            query = listOf("prefix" to "J", "max-keys" to "2"),
            headers = mapOf("host" to host, "x-amz-content-sha256" to emptyPayload, "x-amz-date" to amzDate),
            payloadSha256 = emptyPayload,
            amzDate = amzDate,
        )
        assertEquals("max-keys=2&prefix=J", signed.canonicalRequest.lines()[2])
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", signed.signature)
    }

    @Test
    fun `header names lowercase and sort - values trim and collapse inner whitespace`() {
        val signed = signer.sign(
            method = "GET",
            canonicalPath = "/",
            query = emptyList(),
            headers = mapOf(
                "X-Amz-Date" to amzDate,
                "Host" to host,
                "X-Amz-Content-Sha256" to emptyPayload,
                "If-Match" to "  \"abc\"  \t def  ",
            ),
            payloadSha256 = emptyPayload,
            amzDate = amzDate,
        )
        val lines = signed.canonicalRequest.lines()
        assertEquals("host:$host", lines[3])
        assertEquals("if-match:\"abc\" def", lines[4])
        assertEquals("host;if-match;x-amz-content-sha256;x-amz-date", lines[8])
    }

    @Test
    fun `key encoding - unreserved kept, everything else UTF-8 percent-encoded uppercase, slashes preserved`() {
        assertEquals("/a/b/c.md", SigV4Signer.uriEncodePath("a/b/c.md"))
        assertEquals("/sp%20ace/pl%2Bus/ti~lde._-", SigV4Signer.uriEncodePath("sp ace/pl+us/ti~lde._-"))
        assertEquals("/%E3%83%89%E3%82%AD%E3%83%A5%E3%83%A1%E3%83%B3%E3%83%88.md", SigV4Signer.uriEncodePath("ドキュメント.md"))
        assertEquals("/a%26b%3Dc%3F%23.md", SigV4Signer.uriEncodePath("a&b=c?#.md"))
        // Single-encoded (the S3 rule): a '%' in the key encodes ONCE and is never re-encoded.
        assertEquals("/100%25.md", SigV4Signer.uriEncodePath("100%.md"))
    }

    @Test
    fun `query value encoding covers the continuation-token alphabet`() {
        val signed = signer.sign(
            method = "GET",
            canonicalPath = "/",
            query = listOf("continuation-token" to "1/wJalr+XUtnFEMI=", "list-type" to "2"),
            headers = mapOf("host" to host, "x-amz-content-sha256" to emptyPayload, "x-amz-date" to amzDate),
            payloadSha256 = emptyPayload,
            amzDate = amzDate,
        )
        assertEquals("continuation-token=1%2FwJalr%2BXUtnFEMI%3D&list-type=2", signed.canonicalRequest.lines()[2])
    }
}
