package com.plainbase.frameworks.objectstore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

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
                    when {
                        response.omitContentLength ->
                            // Chunked write -> no Content-Length header (the M5 absent-length case); body stays empty.
                            call.respondBytesWriter(status = response.status) { }
                        response.declaredLengthOverride != null -> {
                            // A LYING Content-Length: declare more than the writer sends, then end the
                            // stream - the wire shape of a truncated transfer surfacing as clean EOF.
                            call.response.header(HttpHeaders.ContentLength, response.declaredLengthOverride.toString())
                            call.respondBytesWriter(status = response.status) { writeFully(response.body) }
                        }
                        else -> call.respondBytes(response.body, status = response.status)
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
        // No <Size> in this response: absent stays null (the size is DECLARED data, never invented).
        page.contents shouldBe listOf(ListResponseParser.Entry("smoke-1/a.md", "\"e1\"", size = null))
        val first = recorded.single()
        first.uri shouldBe "/scratch?list-type=2&encoding-type=url&prefix=smoke-1%2F&max-keys=2"
        first.verifySignature()

        recorded.clear()
        client.list("smoke-1/", continuationToken = page.nextContinuationToken)
        val second = recorded.single()
        second.uri shouldContain "continuation-token=1%2Ftok%2BX%3D"
        second.verifySignature()
    }

    test("a truncated GET (declared Content-Length, short body) REFUSES - never a silent short body") {
        // The exact-size read path holds the declared length in hand; returning the short body would
        // let the caller recordConfirmed corrupt bytes under the REAL etag - a mirror entry that never
        // self-heals because the etag matches and nothing re-fetches. Mirrors getToFile's guard.
        // Truncation has TWO wire shapes, and both must refuse:
        // (1) clean-EOF under-send - a well-behaved Ktor server cannot produce it, so the guard is
        //     driven directly through the readDeclaredBody seam with a raw channel;
        val cleanEof = ByteChannel()
        cleanEof.writeFully("hello".toByteArray())
        cleanEof.flush()
        cleanEof.close()
        shouldThrow<ObjectStoreException> {
            client.readDeclaredBody(cleanEof, declared = 10, op = "GET", target = "truncated.md", cap = 1024)
        }.message.orEmpty() shouldContain "truncated"
        // (2) a killed connection (what the real server produces when it cannot honor its declared
        //     length) - refused via the engine's own exception; the property is NO silent short body.
        respond = {
            FakeResponse(
                HttpStatusCode.OK,
                mapOf("ETag" to "\"t1\""),
                body = "hello".toByteArray(),
                declaredLengthOverride = 10,
            )
        }
        shouldThrowAny { client.get("truncated.md") }
    }

    test("the exact-size path round-trips a declared-length body byte-for-byte (the common GET shape)") {
        respond = { FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"x1\""), body = "exact body bytes".toByteArray()) }
        checkNotNull(client.get("exact.md")).bytes.decodeToString() shouldBe "exact body bytes"
    }

    // ---- Bounded backoff on a THROTTLED GET (503/SlowDown, 429) -------------------------------
    // Each row builds its own client so it can record the sleeps, and CLOSES it: afterSpec closes only the
    // spec-level client, so an unclosed per-row CIO client leaks its threads for the rest of the run.

    fun throttleClient(sleeps: MutableList<Long>) = S3ObjectClient(
        S3ClientConfig(
            endpoint = "http://127.0.0.1:$port",
            region = "auto",
            bucket = "scratch",
            accessKeyId = "AKIDTEST",
            secretAccessKey = "SECRETTEST",
        ),
        clock = { Instant.parse("2026-07-05T12:00:00Z") },
        sleeper = { sleeps += it },
    )

    test("B1: a throttled GET (503, 503, 200) succeeds after two bounded backoffs") {
        val sleeps = mutableListOf<Long>()
        var call = 0
        respond = {
            call++
            if (call <= 2) {
                FakeResponse(HttpStatusCode.ServiceUnavailable, body = "<Error><Code>SlowDown</Code></Error>".toByteArray())
            } else {
                FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"s1\""), "slowed".toByteArray())
            }
        }
        throttleClient(sleeps).use { checkNotNull(it.get("throttled.md")).bytes.decodeToString() shouldBe "slowed" }
        recorded.size shouldBe 3
        sleeps.size shouldBe 2
        (sleeps[0] in 200L..300L).shouldBeTrue()
        (sleeps[1] in 400L..600L).shouldBeTrue()
    }

    test("B2: a persistently throttled GET exhausts the bound and throws, every delay AND the total bounded")
        .config(timeout = 30.seconds) {
            // The injected sleeper returns instantly, so an unbounded-retry regression spins rather than waits:
            // the request count and the timeout are both observing it.
            val sleeps = mutableListOf<Long>()
            respond = { FakeResponse(HttpStatusCode.ServiceUnavailable, body = "<Error><Code>SlowDown</Code></Error>".toByteArray()) }
            throttleClient(sleeps).use {
                shouldThrow<ObjectStoreException> { it.get("always-throttled.md") }.message.orEmpty() shouldContain "503"
            }
            recorded.size shouldBe 1 + S3ObjectClient.THROTTLE_MAX_RETRIES
            sleeps.size shouldBe S3ObjectClient.THROTTLE_MAX_RETRIES
            (sleeps[0] in 200L..300L).shouldBeTrue()
            (sleeps[1] in 400L..600L).shouldBeTrue()
            (sleeps[2] in 800L..1200L).shouldBeTrue()
            (sleeps.sum() <= 2100L).shouldBeTrue()
        }

    test("B3: a non-throttle 5xx does not retry - a misconfiguration must surface at once, not at triple the latency") {
        val sleeps = mutableListOf<Long>()
        respond = { FakeResponse(HttpStatusCode.InternalServerError, body = "boom".toByteArray()) }
        throttleClient(sleeps).use { shouldThrow<ObjectStoreException> { it.get("broken.md") } }
        recorded.size shouldBe 1
        sleeps.shouldBeEmpty()
    }

    test("B4: a 429 retries exactly like a 503") {
        val sleeps = mutableListOf<Long>()
        var call = 0
        respond = {
            call++
            if (call == 1) {
                FakeResponse(HttpStatusCode.TooManyRequests, body = "too many".toByteArray())
            } else {
                FakeResponse(HttpStatusCode.OK, mapOf("ETag" to "\"s2\""), "after 429".toByteArray())
            }
        }
        throttleClient(sleeps).use { checkNotNull(it.get("rate-limited.md")).bytes.decodeToString() shouldBe "after 429" }
        recorded.size shouldBe 2
        sleeps.size shouldBe 1
    }

    // ---- The exact-size read's clamped initial allocation ---------------------------------------
    // These rows drive readDeclaredBody with a fully materialized ByteReadChannel, NOT the raw ByteChannel +
    // writeFully idiom above: at clamp scale writeFully fills Ktor's channel buffer and suspends before the read
    // ever starts, so a sequential write-then-read row would deadlock itself.

    // The timeout bounds a read that STALLS (a channel that never delivers). It cannot bound a growth bug that
    // spins on zero-length reads: that loop neither suspends nor blocks, so nothing can interrupt it and the run
    // hangs rather than failing. Verified, not assumed - the honest limit of these two rows.
    test("C1: a body past the clamp grows to the declared size and round-trips byte-for-byte").config(timeout = 30.seconds) {
        val body = ByteArray(S3ObjectClient.EXACT_READ_INITIAL_CLAMP + 3) { (it % 251).toByte() }
        val read = client.readDeclaredBody(ByteReadChannel(body), body.size, "GET", "grow.md", cap = Long.MAX_VALUE)
        read.contentEquals(body).shouldBeTrue()
    }

    test("C2: a body of exactly the clamp round-trips with no growth at all") {
        val body = ByteArray(S3ObjectClient.EXACT_READ_INITIAL_CLAMP) { (it % 241).toByte() }
        val read = client.readDeclaredBody(ByteReadChannel(body), body.size, "GET", "boundary.md", cap = Long.MAX_VALUE)
        read.contentEquals(body).shouldBeTrue()
    }

    test("C3: a short body past the clamp still REFUSES - the clamp must not reopen the silent-truncation hole")
        .config(timeout = 30.seconds) {
            val declared = 2 * S3ObjectClient.EXACT_READ_INITIAL_CLAMP
            val sent = ByteArray(S3ObjectClient.EXACT_READ_INITIAL_CLAMP + 10)
            shouldThrow<ObjectStoreException> {
                client.readDeclaredBody(ByteReadChannel(sent), declared, "GET", "short.md", cap = Long.MAX_VALUE)
            }.message.orEmpty() shouldContain "truncated: read ${sent.size} bytes of a declared"
        }

    test("C4: the initial capacity is clamped, and a small declared length is left alone") {
        S3ObjectClient.exactReadInitialCapacity(64 * 1024 * 1024) shouldBe S3ObjectClient.EXACT_READ_INITIAL_CLAMP
        S3ObjectClient.exactReadInitialCapacity(10) shouldBe 10
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
            // contentType rides the STREAMED path too: WriteChannelContent has no contentType of its own,
            // so the signed header is the one on the wire - same exactly-once + signed==wire guarantees
            // the byte-array PUT proves, asserted here for the body shape that diverges.
            client.putFromFile("bundle.bin", source, contentType = "application/x-git-bundle") shouldBe PutOutcome.Stored("\"e4\"")
            val request = recorded.single()
            request.method shouldBe "PUT"
            request.headers["content-type"] shouldBe "application/x-git-bundle"
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

    test("a case-variant of an engine-managed header name refuses at the signing seam") {
        // The exactly-once wire test catches the 403 empirically; this seam guard catches the CLASS at
        // construction time - Content-Length has the identical case-sensitive skip Content-Type had, so
        // a future extraHeaders caller must not be able to reintroduce the double emission.
        shouldThrow<IllegalArgumentException> {
            client.signedRequest(HttpMethod.Put, "a.md", extraHeaders = mapOf("content-length" to "3"))
        }.message.orEmpty() shouldContain "exact-case"
        // Host is in the same class from the SIGNING side: a lowercase "host" would coexist with the
        // exact-case Host key in the map, sign host twice in canonical form, and 403 - while the
        // ignoreCase wire drop hides both from the wire, making it invisible to the multiplicity check.
        shouldThrow<IllegalArgumentException> {
            client.signedRequest(HttpMethod.Get, "a.md", extraHeaders = mapOf("host" to "evil.example"))
        }.message.orEmpty() shouldContain "exact-case"
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
    /** When set, the wire declares THIS Content-Length while [body] holds fewer bytes - a truncated transfer. */
    val declaredLengthOverride: Long? = null,
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
    // Scope, stated honestly: only headers named in SignedHeaders= are counted (an unsigned header may
    // legally repeat), and the VALUE equality of the surviving copy is the collapsed-map recomputation's
    // job below - with exactly one copy, the collapsed value IS the wire value, so the pair of checks
    // covers value drift without a separate assertion.
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
