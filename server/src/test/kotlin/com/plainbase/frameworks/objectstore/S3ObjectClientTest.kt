package com.plainbase.frameworks.objectstore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant

/**
 * [S3ObjectClient] against a scripted fake-S3 over a REAL loopback CIO server: URL/addressing
 * shape, both conditional-PUT forms, status mapping, LIST wiring - and, on every recorded
 * request, a full server-side SigV4 recomputation from the RECEIVED wire bytes, proving the
 * request as sent is exactly the request that was signed (the drift a real endpoint would
 * reject with a 403). Live provider semantics (R2 vs S3 codes, etag quoting) are the
 * credentialed `s3-smoke`'s job, not this test's.
 */
class S3ObjectClientTest : FunSpec({

    val recorded = mutableListOf<RecordedRequest>()
    var respond: () -> FakeResponse = { FakeResponse(HttpStatusCode.NotFound) }

    val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            route("{path...}") {
                handle {
                    val request = RecordedRequest(
                        method = call.request.httpMethod.value,
                        uri = call.request.uri,
                        headers = call.request.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") },
                        // UNCOLLAPSED: one pair per wire value. The collapsed map above once hid a real 403:
                        // the client emitted Content-Type twice (case-variant names), `associate` kept one,
                        // and verifySignature recomputed from the collapsed view - green over a signature R2
                        // rejects. Multiplicity must be recorded, or it cannot be asserted.
                        wireHeaders = call.request.headers.entries().flatMap { entry -> entry.value.map { entry.key to it } },
                        body = call.receiveChannel().toByteArray(),
                    )
                    recorded += request
                    val response = respond()
                    response.headers.forEach { (name, value) -> call.response.header(name, value) }
                    if (response.omitContentLength) {
                        // Chunked write -> no Content-Length header (the M5 absent-length case); body stays empty.
                        call.respondBytesWriter(status = response.status) { }
                    } else {
                        call.respondBytes(response.body, status = response.status)
                    }
                }
            }
        }
    }.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }

    val client = S3ObjectClient(
        S3ClientConfig(
            endpoint = "http://127.0.0.1:$port",
            region = "auto",
            bucket = "scratch",
            accessKeyId = "AKIDTEST",
            secretAccessKey = "SECRETTEST",
        ),
        clock = { Instant.parse("2026-07-05T12:00:00Z") },
    )

    beforeTest { recorded.clear() }
    afterSpec {
        client.close()
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
    }

    test("GET: path-style URL with an encoded key, signed wire bytes, bytes + etag through") {
        respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"abc123\""), "hello".toByteArray()) }
        val fetched = checkNotNull(client.get("dir/sp ace & ガ.md"))
        fetched.bytes.decodeToString() shouldBe "hello"
        fetched.etag shouldBe "\"abc123\""
        val request = recorded.single()
        request.method shouldBe "GET"
        request.uri shouldBe "/scratch/dir/sp%20ace%20%26%20%E3%82%AC.md"
        request.headers["x-amz-date"] shouldBe "20260705T120000Z"
        request.headers["x-amz-content-sha256"] shouldBe SigV4Signer.sha256Hex(ByteArray(0))
        request.verifySignature()
    }

    test("GET and HEAD map 404 to null") {
        respond = { FakeResponse(HttpStatusCode.NotFound) }
        client.get("absent.md").shouldBeNull()
        client.head("absent.md").shouldBeNull()
    }

    test("HEAD returns the etag and the Content-Length the engine reports") {
        respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e1\"")) }
        client.head("a.md") shouldBe ObjectStat(etag = "\"e1\"", size = 0L)
        recorded.single().verifySignature()
    }

    test("successful object responses without an ETag fail closed") {
        respond = { FakeResponse(HttpStatusCode.OK, body = "content".toByteArray()) }
        shouldThrow<ObjectStoreException> { client.get("missing-get-etag.md") }.message.orEmpty() shouldContain "no ETag"

        respond = { FakeResponse(HttpStatusCode.OK) }
        shouldThrow<ObjectStoreException> {
            client.put("missing-put-etag.md", "content".toByteArray(), PutCondition.None)
        }.message.orEmpty() shouldContain "no ETag"
    }

    test("PUT If-None-Match:* sends the exclusive-create header and signs the payload") {
        respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e2\"")) }
        val body = "# created\n".toByteArray()
        client.put("a.md", body, PutCondition.IfAbsent, contentType = "text/markdown") shouldBe PutOutcome.Stored("\"e2\"")
        val request = recorded.single()
        request.headers["if-none-match"] shouldBe "*"
        request.headers["content-type"] shouldBe "text/markdown"
        request.body.contentEquals(body).shouldBeTrue()
        request.headers["x-amz-content-sha256"] shouldBe SigV4Signer.sha256Hex(body)
        request.verifySignature()
    }

    test("PUT signs the RAW content-type and sends it byte-for-byte (a normalizing ContentType would 403)") {
        respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e3\"")) }
        // No space after the ';': ContentType.parse(..).toString() re-inserts one, so the OLD code would have
        // signed `text/markdown;charset=UTF-8` but put `text/markdown; charset=UTF-8` on the wire - a drift a
        // real endpoint rejects with 403. Asserting the wire header equals the raw string AND that the
        // server-side SigV4 recomputation from the received bytes reproduces the Authorization proves signed==wire.
        val rawContentType = "text/markdown;charset=UTF-8"
        client.put("a.md", "x".toByteArray(), PutCondition.None, contentType = rawContentType) shouldBe PutOutcome.Stored("\"e3\"")
        val request = recorded.single()
        request.headers["content-type"] shouldBe rawContentType
        request.verifySignature()
    }

    test("PUT If-Match sends the CAS etag; 412 and 409 both surface as PreconditionFailed with the raw code") {
        respond = { FakeResponse(HttpStatusCode.PreconditionFailed) }
        client.put("a.md", "v2".toByteArray(), PutCondition.IfMatch("\"e2\"")) shouldBe PutOutcome.PreconditionFailed(412)
        recorded.single().headers["if-match"] shouldBe "\"e2\""
        recorded.single().verifySignature()

        respond = { FakeResponse(HttpStatusCode.Conflict) }
        client.put("a.md", "v2".toByteArray(), PutCondition.IfAbsent) shouldBe PutOutcome.PreconditionFailed(409)
    }

    test("unconditional byte-array and streamed PUTs throw on 409 or 412 instead of reporting a refused condition") {
        respond = { FakeResponse(HttpStatusCode.Conflict) }
        shouldThrow<ObjectStoreException> {
            client.put("history.bundle", "bundle".toByteArray(), PutCondition.None)
        }.message.orEmpty() shouldContain "409 Conflict"

        val source = Files.createTempFile("pb-put-conflict", ".bundle")
        try {
            Files.writeString(source, "bundle")
            respond = { FakeResponse(HttpStatusCode.PreconditionFailed) }
            shouldThrow<ObjectStoreException> {
                client.putFromFile("history.bundle", source)
            }.message.orEmpty() shouldContain "412 Precondition Failed"
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("DELETE succeeds on 204 and on 404 (idempotent), throws on anything else") {
        respond = { FakeResponse(HttpStatusCode.NoContent) }
        client.delete("a.md")
        respond = { FakeResponse(HttpStatusCode.NotFound) }
        client.delete("a.md")
        respond = { FakeResponse(HttpStatusCode.InternalServerError) }
        shouldThrow<ObjectStoreException> { client.delete("a.md") }.message.orEmpty() shouldContain "500"
    }

    test("LIST: v2 + encoding-type=url always, prefix and pagination params encoded and signed") {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult>
                <IsTruncated>true</IsTruncated>
                <NextContinuationToken>1/tok+X=</NextContinuationToken>
                <Contents><Key>smoke-1/a.md</Key><ETag>&quot;e1&quot;</ETag></Contents>
            </ListBucketResult>
        """.trimIndent()
        respond = { FakeResponse(HttpStatusCode.OK, body = xml.toByteArray()) }
        val page = client.list("smoke-1/", maxKeys = 2)
        page.isTruncated shouldBe true
        page.nextContinuationToken shouldBe "1/tok+X="
        page.contents shouldBe listOf(ListResponseParser.Entry("smoke-1/a.md", "\"e1\""))
        val first = recorded.single()
        first.uri shouldBe "/scratch?list-type=2&encoding-type=url&prefix=smoke-1%2F&max-keys=2"
        first.verifySignature()

        recorded.clear()
        client.list("smoke-1/", continuationToken = page.nextContinuationToken)
        val second = recorded.single()
        second.uri shouldContain "continuation-token=1%2Ftok%2BX%3D"
        second.verifySignature()
    }

    test("HEAD on a 2xx with no Content-Length fails closed, never a silent zero-size (M5)") {
        respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e\""), omitContentLength = true) }
        shouldThrow<ObjectStoreException> { client.head("a.md") }.message.orEmpty() shouldContain "Content-Length"
    }

    test("GET and LIST refuse a response body over the configured cap (M2)") {
        // A tiny cap so a modest body trips it - the DoS bound against a hostile/misconfigured endpoint.
        val capped = S3ObjectClient(
            S3ClientConfig(
                endpoint = "http://127.0.0.1:$port",
                region = "auto",
                bucket = "scratch",
                accessKeyId = "AKIDTEST",
                secretAccessKey = "SECRETTEST",
                maxResponseBytes = 8,
            ),
            clock = { Instant.parse("2026-07-05T12:00:00Z") },
        )
        capped.use {
            respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e\""), body = ByteArray(64) { 'a'.code.toByte() }) }
            shouldThrow<ObjectStoreException> { it.get("big.md") }.message.orEmpty() shouldContain "exceeds"
            respond = { FakeResponse(HttpStatusCode.OK, body = ByteArray(64) { '<'.code.toByte() }) }
            shouldThrow<ObjectStoreException> { it.list("p/") }.message.orEmpty() shouldContain "exceeds"
        }
    }

    test("get with an unbounded maxBytes override bypasses the response cap (the override exists for completeness/tests)") {
        val capped = S3ObjectClient(
            S3ClientConfig(
                endpoint = "http://127.0.0.1:$port",
                region = "auto",
                bucket = "scratch",
                accessKeyId = "AKIDTEST",
                secretAccessKey = "SECRETTEST",
                maxResponseBytes = 8, // a tiny asset-derived cap
            ),
            clock = { Instant.parse("2026-07-05T12:00:00Z") },
        )
        capped.use {
            val big = ByteArray(4096) { 'b'.code.toByte() }
            respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e\""), body = big) }
            // The default (asset-derived) cap refuses; the explicit Long.MAX_VALUE override returns the whole
            // body. (The DR-bundle path itself streams via getToFile, tested separately - this only exercises
            // the get() maxBytes override mechanism.)
            shouldThrow<ObjectStoreException> { it.get("over") }.message.orEmpty() shouldContain "exceeds"
            checkNotNull(it.get("over", maxBytes = Long.MAX_VALUE)).bytes.size shouldBe 4096
        }
    }

    test("getToFile STREAMS the body to a file uncapped (B-C3): a body over the response cap still lands; a 404 returns false") {
        val capped = S3ObjectClient(
            S3ClientConfig(
                endpoint = "http://127.0.0.1:$port",
                region = "auto",
                bucket = "scratch",
                accessKeyId = "AKIDTEST",
                secretAccessKey = "SECRETTEST",
                maxResponseBytes = 8, // a tiny asset-derived cap that get() would refuse
            ),
            clock = { Instant.parse("2026-07-05T12:00:00Z") },
        )
        capped.use {
            val target = Files.createTempFile("pb-bundle-stream", ".bin")
            try {
                // A bundle larger than the response cap: get() would refuse, but getToFile STREAMS it straight
                // to disk (never a whole-body in-heap array), so a large-history DR bundle still restores.
                val big = ByteArray(4096) { 'z'.code.toByte() }
                respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e\""), body = big) }
                it.getToFile("bundle", target) shouldBe true
                Files.readAllBytes(target).contentEquals(big).shouldBeTrue()

                respond = { FakeResponse(HttpStatusCode.NotFound) }
                it.getToFile("absent", target) shouldBe false // a true 404 -> not found (no bundle shipped)
            } finally {
                Files.deleteIfExists(target)
            }
        }
    }

    test("putFromFile STREAMS the file body over the wire and stream-hashes it for SigV4 (signed == wire, B-C3)") {
        respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"e4\"")) }
        val source = Files.createTempFile("pb-put-stream", ".bin")
        try {
            // Bigger than READ_CHUNK so the streaming write AND the streaming file-hash both span >1 chunk.
            val body = ByteArray(200_000) { (it * 31 % 251).toByte() }
            Files.write(source, body)
            client.putFromFile("bundle.bin", source) shouldBe PutOutcome.Stored("\"e4\"")
            val request = recorded.single()
            request.method shouldBe "PUT"
            request.body.contentEquals(body).shouldBeTrue() // the streamed body arrived intact
            // LOAD-BEARING: the `body.contentEquals` + `x-amz-content-sha256 == sha256Hex(body)` assertions
            // are what prove the STREAMED body equals the STREAM-computed signed hash. Do NOT drop them and
            // lean on verifySignature() alone - it recomputes from the SENT x-amz-content-sha256 header, so it
            // would still pass even if the streamed body and the signed hash had drifted apart.
            request.headers["x-amz-content-sha256"] shouldBe SigV4Signer.sha256Hex(body)
            request.verifySignature()
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("deriveMaxResponseBytes: default floor wins for small assets, a raised asset cap lifts it, overflow saturates (R2-4/R3-1)") {
        // The default 64 MiB floor dominates the 10 MiB default asset cap.
        S3ObjectClient.deriveMaxResponseBytes(10L * 1024 * 1024) shouldBe S3ObjectClient.DEFAULT_MAX_RESPONSE_BYTES
        // A raised PLAINBASE_MAX_ASSET_BYTES (100 MiB) lifts the derived cap above the floor, plus headroom.
        S3ObjectClient.deriveMaxResponseBytes(100L * 1024 * 1024) shouldBe (100L * 1024 * 1024 + S3ObjectClient.RESPONSE_HEADROOM_BYTES)
        // A near-Long.MAX asset cap must SATURATE, never wrap negative and collapse back to the default.
        S3ObjectClient.deriveMaxResponseBytes(Long.MAX_VALUE) shouldBe Long.MAX_VALUE
    }

    test("an unexpected status surfaces as ObjectStoreException with the status and body") {
        respond = { FakeResponse(HttpStatusCode.Forbidden, body = "<Error><Code>AccessDenied</Code></Error>".toByteArray()) }
        shouldThrow<ObjectStoreException> { client.get("a.md") }.message.orEmpty() shouldContain "AccessDenied"
    }

    test("an oversized hostile error body stays bounded while preserving the status and useful prefix") {
        val prefix = "<Error><Code>AccessDenied</Code><Message>"
        val hostileBody = (prefix + "x".repeat(100_000) + "SECRET-TAIL").toByteArray()
        respond = { FakeResponse(HttpStatusCode.Forbidden, body = hostileBody) }

        val message = shouldThrow<ObjectStoreException> { client.get("bounded-error.md") }.message.orEmpty()

        message shouldContain "403 Forbidden"
        message shouldContain "AccessDenied"
        (message.length < 600) shouldBe true
        ("SECRET-TAIL" in message) shouldBe false
    }

    test("virtual-host addressing: bucket prefixes the host, drops out of the path, and is the signed host") {
        // A virtual-host `bucket.host` does not resolve on the loopback server, so this proves the
        // construction + signature off the wire, through the signedRequest seam. Path-style above
        // already proves end-to-end that the engine sends exactly the signed host.
        val vhost = S3ObjectClient(
            S3ClientConfig(
                endpoint = "http://127.0.0.1:$port",
                region = "auto",
                bucket = "scratch",
                accessKeyId = "AKIDTEST",
                secretAccessKey = "SECRETTEST",
                addressing = S3Addressing.VIRTUAL_HOST,
            ),
            clock = { Instant.parse("2026-07-05T12:00:00Z") },
        )
        vhost.use {
            val get = it.signedRequest(HttpMethod.Get, "dir/a.md")
            get.host shouldBe "scratch.127.0.0.1"
            get.encodedPath shouldBe "/dir/a.md" // bucket is NOT in the path under virtual-host
            get.signedHeaders[HttpHeaders.Host] shouldBe "scratch.127.0.0.1:$port" // the signed host == the connect host
            get.verifySignature()

            val list = it.signedRequest(HttpMethod.Get, key = null, query = listOf("list-type" to "2"))
            list.encodedPath shouldBe "/"
            list.verifySignature()
        }
    }
})

private class RecordedRequest(
    val method: String,
    val uri: String,
    /** Names lowercased at capture (HTTP header names are case-insensitive). */
    val headers: Map<String, String>,
    /** Every wire value as its own pair, duplicates preserved - the view [headers] collapses. */
    val wireHeaders: List<Pair<String, String>>,
    val body: ByteArray,
)

private class FakeResponse(
    val status: HttpStatusCode,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    /** When true the server writes chunked (no Content-Length header) - the M5 absent-length case. */
    val omitContentLength: Boolean = false,
)

/**
 * Recomputes the SigV4 signature server-side from the wire bytes actually received (the exact
 * check a real endpoint performs): the request's own path/query/headers must reproduce the
 * Authorization header it carried.
 */
private fun RecordedRequest.verifySignature() {
    val url = Url("http://placeholder$uri")
    val query = url.parameters.entries().flatMap { (name, values) -> values.map { name to it } }
    val authorization = checkNotNull(headers["authorization"])
    val signedHeaderNames = authorization.substringAfter("SignedHeaders=").substringBefore(",").split(";")
    // Every signed header must arrive EXACTLY once. A duplicate is a signature break even when each copy
    // carries the signed value: the endpoint canonicalizes all copies joined, the client signed one. The
    // recomputation below cannot see this (it reads the collapsed map), so it is asserted on the raw wire
    // pairs first. This is the check that was missing when a case-variant Content-Type shipped twice.
    signedHeaderNames.forEach { name ->
        val copies = wireHeaders.count { (wireName, _) -> wireName.equals(name, ignoreCase = true) }
        check(copies == 1) { "signed header '$name' appeared $copies times on the wire; signed exactly once" }
    }
    val recomputed = SigV4Signer("AKIDTEST", "SECRETTEST", "auto", "s3").sign(
        method = method,
        canonicalPath = url.encodedPath,
        query = query,
        headers = signedHeaderNames.associateWith { checkNotNull(headers[it]) { "signed header '$it' was not sent" } },
        payloadSha256 = checkNotNull(headers["x-amz-content-sha256"]),
        amzDate = checkNotNull(headers["x-amz-date"]),
    )
    authorization shouldBe recomputed.authorization
}

/** The seam's own signature must reproduce from exactly the header set it signed (off-wire twin). */
private fun SignedRequest.verifySignature() {
    val recomputed = SigV4Signer("AKIDTEST", "SECRETTEST", "auto", "s3").sign(
        method = method.value,
        canonicalPath = encodedPath,
        query = query,
        headers = signedHeaders,
        payloadSha256 = checkNotNull(signedHeaders["x-amz-content-sha256"]),
        amzDate = checkNotNull(signedHeaders["x-amz-date"]),
    )
    authorization shouldBe recomputed.authorization
}
