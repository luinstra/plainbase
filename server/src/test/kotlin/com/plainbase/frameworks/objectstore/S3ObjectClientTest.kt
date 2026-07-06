package com.plainbase.frameworks.objectstore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
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
                        body = call.receiveChannel().toByteArray(),
                    )
                    recorded += request
                    val response = respond()
                    response.headers.forEach { (name, value) -> call.response.header(name, value) }
                    call.respondBytes(response.body, status = response.status)
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

    test("PUT If-Match sends the CAS etag; 412 and 409 both surface as PreconditionFailed with the raw code") {
        respond = { FakeResponse(HttpStatusCode.PreconditionFailed) }
        client.put("a.md", "v2".toByteArray(), PutCondition.IfMatch("\"e2\"")) shouldBe PutOutcome.PreconditionFailed(412)
        recorded.single().headers["if-match"] shouldBe "\"e2\""
        recorded.single().verifySignature()

        respond = { FakeResponse(HttpStatusCode.Conflict) }
        client.put("a.md", "v2".toByteArray(), PutCondition.IfAbsent) shouldBe PutOutcome.PreconditionFailed(409)
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

    test("an unexpected status surfaces as ObjectStoreException with the status and body") {
        respond = { FakeResponse(HttpStatusCode.Forbidden, body = "<Error><Code>AccessDenied</Code></Error>".toByteArray()) }
        shouldThrow<ObjectStoreException> { client.get("a.md") }.message.orEmpty() shouldContain "AccessDenied"
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
            get.signedHeaders["host"] shouldBe "scratch.127.0.0.1:$port" // the signed host == the connect host
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
    val body: ByteArray,
)

private class FakeResponse(
    val status: HttpStatusCode,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
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
