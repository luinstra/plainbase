package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.PercentCoding
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The five S3 ops over the already-present Ktor CIO client, signed by [SigV4Signer] (plan C0/Q7:
 * hand-rolled, zero new dependencies). Both addressing modes: path-style (the default; R2 is
 * account-endpoint path-style) and virtual-host. Every request signs a single-chunk payload hash
 * (`x-amz-content-sha256`, R2 requires it) plus every header it sends, so the canonical request
 * and the wire bytes cannot drift apart (the URL is built from `encodedPath`/`encodedParameters`,
 * never re-encoded by Ktor).
 *
 * TLS is the endpoint's business: `https` endpoints validate certificates through the platform
 * default trust (no trust-all path exists here); the native-image proof of that client TLS stack
 * is spike check #1, and the credentialed `plainbase s3-smoke` run proves it against real R2/S3.
 */
class S3ObjectClient(
    private val config: S3ClientConfig,
    private val clock: () -> Instant = Instant::now,
) : ObjectStoreClient {

    private val endpoint = Url(config.endpoint)
    private val signer = SigV4Signer(config.accessKeyId, config.secretAccessKey, config.region)

    init {
        constructions.incrementAndGet() // R9 test hook: a LOCAL boot must construct ZERO of these
    }

    // Bounded timeouts: a hung endpoint must never block `s3-smoke` (or the later C4 hybrid)
    // indefinitely. HttpTimeout ships in ktor-client-core (no new dependency). The request bound
    // covers the whole call (TLS handshake + signed round-trip); static keys mean fresh
    // connections per op are fine, so these are generous rather than aggressive.
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMillis
            requestTimeoutMillis = config.requestTimeoutMillis
            socketTimeoutMillis = config.requestTimeoutMillis
        }
    }

    override suspend fun head(key: String): ObjectStat? {
        val response = execute(HttpMethod.Head, key)
        return when {
            // Fail-closed on an absent Content-Length (M5): a malformed/short response must refuse, never
            // be reported as a zero-size object (the rest of the client is fail-closed; size is load-bearing).
            response.status.isSuccess() ->
                ObjectStat(
                    etag = etagOf(response),
                    size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        ?: throw ObjectStoreException("HEAD '$key' returned no Content-Length"),
                )
            response.status == HttpStatusCode.NotFound -> null
            else -> unexpected("HEAD", key, response)
        }
    }

    override suspend fun get(key: String, maxBytes: Long?): FetchedObject? {
        val response = execute(HttpMethod.Get, key)
        return when {
            response.status.isSuccess() ->
                FetchedObject(bytes = readBody(response, "GET", key, maxBytes ?: config.maxResponseBytes), etag = etagOf(response))
            response.status == HttpStatusCode.NotFound -> null
            else -> unexpected("GET", key, response)
        }
    }

    override suspend fun getToFile(key: String, target: Path, requestTimeoutMillis: Long?): Boolean {
        val response = execute(HttpMethod.Get, key, requestTimeoutMillis = requestTimeoutMillis)
        return when {
            response.status.isSuccess() -> {
                // Stream the body straight to disk - never a whole-body in-heap array (B-C3). READ_CHUNK at a time.
                val channel = response.bodyAsChannel()
                var written = 0L
                runCatching {
                    Files.newOutputStream(target).use { out ->
                        val chunk = ByteArray(READ_CHUNK)
                        while (true) {
                            val read = channel.readAvailable(chunk, 0, chunk.size)
                            if (read == -1) break
                            out.write(chunk, 0, read)
                            written += read
                        }
                    }
                }.onFailure { failure ->
                    channel.cancel(null) // release the connection on an abnormal mid-stream exit (mirrors readBody)
                    throw failure
                }
                // Truncation guard: if a Content-Length was declared, a short body (Ktor may surface a cut
                // transfer as a clean EOF) is a corrupt download - refuse so the caller's bootRefusal fires,
                // rather than a downstream `git fsck`/GitCommandException on a truncated bundle.
                val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declared != null && written != declared) {
                    throw ObjectStoreException("GET '$key' truncated: wrote $written bytes of a declared $declared")
                }
                true
            }
            response.status == HttpStatusCode.NotFound -> false
            else -> unexpected("GET", key, response)
        }
    }

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        condition: PutCondition,
        contentType: String?,
        requestTimeoutMillis: Long?,
    ): PutOutcome {
        val conditionHeaders = when (condition) {
            PutCondition.None -> emptyMap()
            is PutCondition.IfMatch -> mapOf(HttpHeaders.IfMatch to condition.etag)
            PutCondition.IfAbsent -> mapOf(HttpHeaders.IfNoneMatch to "*")
        }
        val response = execute(
            HttpMethod.Put,
            key,
            extraHeaders = conditionHeaders,
            body = bytes,
            contentType = contentType,
            requestTimeoutMillis = requestTimeoutMillis,
        )
        return when {
            response.status.isSuccess() -> PutOutcome.Stored(etag = etagOf(response))
            condition != PutCondition.None && response.status.isPreconditionRefusal() ->
                PutOutcome.PreconditionFailed(status = response.status.value)
            else -> unexpected("PUT", key, response)
        }
    }

    override suspend fun delete(key: String) {
        val response = execute(HttpMethod.Delete, key)
        // 204 is the norm; a 404 stays success (idempotent delete), matching S3's own semantics.
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) unexpected("DELETE", key, response)
    }

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int?): ListResponseParser.Listing =
        ListResponseParser.parse(listRaw(prefix, continuationToken, maxKeys))

    /** The unparsed ListObjectsV2 body - the seam `s3-smoke` uses to record raw goldens. */
    suspend fun listRaw(prefix: String, continuationToken: String? = null, maxKeys: Int? = null): String {
        val query = buildList {
            add("list-type" to "2")
            add("encoding-type" to "url")
            if (prefix.isNotEmpty()) add("prefix" to prefix)
            continuationToken?.let { add("continuation-token" to it) }
            maxKeys?.let { add("max-keys" to it.toString()) }
        }
        val response = execute(HttpMethod.Get, key = null, query = query)
        if (!response.status.isSuccess()) unexpected("LIST", prefix, response)
        // Decode the bounded body as UTF-8 explicitly (was `bodyAsText()`, which honored the response charset):
        // ListObjectsV2 XML is always UTF-8 for S3/R2, so this is a deliberate, correct fix, not a regression.
        return String(readBody(response, "LIST", prefix, config.maxResponseBytes), Charsets.UTF_8)
    }

    override fun close() = http.close()

    private suspend fun execute(
        method: HttpMethod,
        key: String?,
        query: List<Pair<String, String>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        requestTimeoutMillis: Long? = null,
    ): HttpResponse {
        val request = signedRequest(method, key, query, extraHeaders, body, contentType)
        return http.request {
            applySignedRequest(request, requestTimeoutMillis)
            request.body?.let { setBody(ByteArrayContent(it)) }
        }
    }

    /**
     * Configures a request builder from a [SignedRequest] (URL, signed header set, Authorization, optional
     * per-request timeout) WITHOUT setting a body - shared by [execute] (ByteArray body) and [putFromFile]
     * (streamed file body). Ktor derives Host from url.host:port, so only host is dropped here. Content-Type,
     * when present, is appended as the RAW signed string (M1): a normalized type would differ from the signed
     * value and 403; the body carries no content type of its own, so this header is the one on the wire -
     * exactly once, which is only true under the exact-case key documented at the signed-headers map.
     */
    private fun HttpRequestBuilder.applySignedRequest(request: SignedRequest, requestTimeoutMillis: Long?) {
        // Per-request timeout override (B-C3): a large bundle transfer must not share the short page-op request
        // timeout. Only the whole-call bound is raised; connect/socket-gap bounds stay the default.
        requestTimeoutMillis?.let { timeout { this.requestTimeoutMillis = it } }
        this.method = request.method
        url {
            protocol = URLProtocol.createOrDefault(request.protocol)
            host = request.host
            port = request.port
            encodedPath = request.encodedPath
            request.query.forEach { (name, value) ->
                encodedParameters.append(PercentCoding.encodeSegment(name), PercentCoding.encodeSegment(value))
            }
        }
        request.signedHeaders.forEach { (name, value) -> if (name != HttpHeaders.Host) headers.append(name, value) }
        headers.append(HttpHeaders.Authorization, request.authorization)
    }

    override suspend fun putFromFile(key: String, source: Path, contentType: String?, requestTimeoutMillis: Long?): PutOutcome {
        // B-C3: STREAM the file to the PUT body, and STREAM-hash it for SigV4 - never a whole-file in-heap array,
        // so the DR-bundle ship cannot OOM on a memory-constrained host. Symmetric with getToFile on restore.
        val payloadHash = SigV4Signer.sha256HexOfFile(source)
        val request = signedRequest(HttpMethod.Put, key, contentType = contentType, payloadHashOverride = payloadHash)
        val response = http.request {
            applySignedRequest(request, requestTimeoutMillis)
            setBody(fileBody(source))
        }
        return when {
            response.status.isSuccess() -> PutOutcome.Stored(etag = etagOf(response))
            else -> unexpected("PUT", key, response)
        }
    }

    private fun HttpStatusCode.isPreconditionRefusal(): Boolean =
        this == HttpStatusCode.PreconditionFailed || this == HttpStatusCode.Conflict

    /** A streaming request body over [source]: [READ_CHUNK] at a time from the file, never buffered whole. */
    private fun fileBody(source: Path): OutgoingContent = object : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = Files.size(source)

        override suspend fun writeTo(channel: ByteWriteChannel) {
            Files.newInputStream(source).use { input ->
                val buffer = ByteArray(READ_CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    channel.writeFully(buffer, 0, read)
                }
            }
        }
    }

    /**
     * Builds the fully-signed request (host, path, query, the signed header set, Authorization) for
     * [method] WITHOUT issuing it. Extracted as an internal seam so both addressing modes are
     * directly assertable: a virtual-host `bucket.host` does not resolve on a loopback test server,
     * so its host construction + signature can only be proven here, off the wire. [execute] dispatches
     * whatever this returns, so the signed host is exactly the host the engine connects to.
     */
    internal fun signedRequest(
        method: HttpMethod,
        key: String?,
        query: List<Pair<String, String>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        /** A pre-computed payload hash (the STREAMED file-hash for [putFromFile]); else hashed from [body]. */
        payloadHashOverride: String? = null,
    ): SignedRequest {
        val requestHost = when (config.addressing) {
            S3Addressing.PATH_STYLE -> endpoint.host
            S3Addressing.VIRTUAL_HOST -> "${config.bucket}.${endpoint.host}"
        }
        val requestPath = buildString {
            if (config.addressing == S3Addressing.PATH_STYLE) append('/').append(config.bucket)
            key?.let { append(SigV4Signer.uriEncodePath(it)) }
            if (isEmpty()) append('/')
        }
        val amzDate = AMZ_DATE_FORMAT.format(clock())
        val payloadHash = payloadHashOverride ?: SigV4Signer.sha256Hex(body ?: ByteArray(0))
        val signedHeaders = buildMap {
            // Sign the exact host[:port] the engine will send (the port only when non-default).
            put(HttpHeaders.Host, if (endpoint.port == endpoint.protocol.defaultPort) requestHost else "$requestHost:${endpoint.port}")
            put("x-amz-content-sha256", payloadHash)
            put("x-amz-date", amzDate)
            // MUST be Ktor's exact-case constant, never a lowercase literal. Ktor's mergeHeaders
            // (ktor-client-core Utils.kt) skips Content-Type from the request headers with a
            // CASE-SENSITIVE comparison, then re-emits it from a case-INSENSITIVE fallback lookup - so a
            // lowercase "content-type" ships TWICE (both case variants), the endpoint canonicalizes the
            // pair comma-joined, and every body-carrying request 403s with SignatureDoesNotMatch. Found
            // by the first credentialed s3-smoke against real R2; pinned by verifySignature's
            // exactly-once wire assertion in S3ObjectClientTest. The signer lowercases names for the
            // canonical form either way, so the signature itself is unaffected by this key's case.
            contentType?.let { put(HttpHeaders.ContentType, it) }
            putAll(extraHeaders)
        }
        val signed = signer.sign(method.value, requestPath, query, signedHeaders, payloadHash, amzDate)
        return SignedRequest(
            method = method,
            protocol = endpoint.protocol.name,
            host = requestHost,
            port = endpoint.port,
            encodedPath = requestPath,
            query = query,
            signedHeaders = signedHeaders,
            authorization = signed.authorization,
            body = body,
            contentType = contentType,
        )
    }

    private fun etagOf(response: HttpResponse): String =
        response.headers[HttpHeaders.ETag] ?: throw ObjectStoreException("${response.call.request.method.value} returned no ETag")

    private suspend fun unexpected(op: String, target: String, response: HttpResponse): Nothing {
        val detail = errorDetail(response).take(500)
        throw ObjectStoreException("$op '$target' failed with ${response.status}: $detail")
    }

    /**
     * Reads the whole response body into memory, refusing a body over [cap] (M2): `bodyAsBytes()`/
     * `bodyAsText()` would buffer an arbitrarily large body from a hostile or misconfigured endpoint.
     * Content correctness matters here (GET/LIST), so an oversize body REFUSES rather than truncates.
     * [cap] is per-call so the app-owned bundle GET can pass an effectively-unbounded value (R3-1).
     */
    private suspend fun readBody(response: HttpResponse, op: String, target: String, cap: Long): ByteArray {
        val channel = response.bodyAsChannel()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read == -1) break
            if (out.size().toLong() + read > cap) {
                channel.cancel(null) // release the rest of the oversize body before refusing (mirrors errorDetail)
                // Name the offending key AND the operator knob: the cap derivation only guarantees a
                // Plainbase-PUT-able asset is GET-able; the bucket authority can legally hold larger objects.
                throw ObjectStoreException(
                    "$op '$target' response body exceeds the $cap-byte cap - raise PLAINBASE_MAX_ASSET_BYTES if " +
                        "this is a legitimate large object, else check for a hostile or misconfigured endpoint",
                )
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /** The bounded error-body detail for [unexpected]: a hostile error page is TRUNCATED (not refused) so the status still surfaces. */
    private suspend fun errorDetail(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        while (out.size() < ERROR_DETAIL_CAP) {
            val read = channel.readAvailable(chunk, 0, minOf(chunk.size, ERROR_DETAIL_CAP - out.size()))
            if (read == -1) break
            out.write(chunk, 0, read)
        }
        channel.cancel(null) // release the rest of a (possibly large) error body instead of leaving it dangling
        val bytes = out.toByteArray()
        // If the cap cut mid-codepoint, drop the incomplete trailing UTF-8 sequence so the operator message
        // is not tailed by a U+FFFD replacement char (a complete trailing char is kept as-is).
        return String(bytes, 0, completeUtf8End(bytes), Charsets.UTF_8)
    }

    /** The length of [bytes] with any incomplete trailing UTF-8 multi-byte sequence trimmed. */
    private fun completeUtf8End(bytes: ByteArray): Int {
        val end = bytes.size
        if (end == 0 || bytes[end - 1].toInt() and UTF8_CONTINUATION_PREFIX == 0) return end // empty or ends on an ASCII byte
        var k = 0
        while (
            k < MAX_UTF8_CONTINUATION_BYTES &&
            end - 1 - k >= 0 &&
            bytes[end - 1 - k].toInt() and UTF8_LEAD_MASK == UTF8_CONTINUATION_PREFIX
        ) {
            k++
        }
        val leadIdx = end - 1 - k
        if (leadIdx < 0) return end
        val lead = bytes[leadIdx].toInt() and UNSIGNED_BYTE_MASK
        val need = when {
            lead and UTF8_TWO_BYTE_MASK == UTF8_TWO_BYTE_PREFIX -> UTF8_TWO_BYTE_LENGTH
            lead and UTF8_THREE_BYTE_MASK == UTF8_THREE_BYTE_PREFIX -> UTF8_THREE_BYTE_LENGTH
            lead and UTF8_FOUR_BYTE_MASK == UTF8_FOUR_BYTE_PREFIX -> UTF8_FOUR_BYTE_LENGTH
            else -> return end // a stray continuation byte with no lead - leave it, String() handles it
        }
        return if (k + 1 < need) leadIdx else end // incomplete sequence: trim it; complete: keep
    }

    companion object {
        private const val MAX_UTF8_CONTINUATION_BYTES = 3
        private const val UTF8_LEAD_MASK = 0xC0
        private const val UTF8_CONTINUATION_PREFIX = 0x80
        private const val UTF8_TWO_BYTE_MASK = 0xE0
        private const val UTF8_TWO_BYTE_PREFIX = 0xC0
        private const val UTF8_THREE_BYTE_MASK = 0xF0
        private const val UTF8_THREE_BYTE_PREFIX = 0xE0
        private const val UTF8_FOUR_BYTE_MASK = 0xF8
        private const val UTF8_FOUR_BYTE_PREFIX = 0xF0
        private const val UTF8_TWO_BYTE_LENGTH = 2
        private const val UTF8_THREE_BYTE_LENGTH = 3
        private const val UTF8_FOUR_BYTE_LENGTH = 4
        private const val UNSIGNED_BYTE_MASK = 0xFF

        /** R9 test hook (counter-proven boot laziness, never reasoned from `single {}` laziness). */
        internal val constructions = java.util.concurrent.atomic.AtomicInteger()

        /** M2 default response-body cap: 64 MiB. A DoS bound on a hostile endpoint, well above any content/asset size. */
        internal const val DEFAULT_MAX_RESPONSE_BYTES: Long = 64L * 1024 * 1024

        /**
         * R2-4 headroom added to `maxAssetBytes` when object mode derives the response cap: a GET body is exactly
         * the object bytes, but this margin keeps the cap comfortably above the largest PUT-able asset so a
         * legitimately-large asset (operators can raise `PLAINBASE_MAX_ASSET_BYTES` with no ceiling) is always
         * GET-able on hydration, never refused by its own transport at boot.
         */
        internal const val RESPONSE_HEADROOM_BYTES: Long = 1L * 1024 * 1024

        /**
         * The object-mode response cap (R2-4): at least [DEFAULT_MAX_RESPONSE_BYTES], else [maxAssetBytes] plus
         * [RESPONSE_HEADROOM_BYTES] so a PUT-able asset is always GET-able. The `+` is SATURATING (opus): a
         * near-`Long.MAX_VALUE` `PLAINBASE_MAX_ASSET_BYTES` must not wrap negative and collapse back to the default.
         */
        internal fun deriveMaxResponseBytes(maxAssetBytes: Long): Long {
            val withHeadroom =
                if (maxAssetBytes > Long.MAX_VALUE - RESPONSE_HEADROOM_BYTES) Long.MAX_VALUE else maxAssetBytes + RESPONSE_HEADROOM_BYTES
            return maxOf(DEFAULT_MAX_RESPONSE_BYTES, withHeadroom)
        }

        private const val READ_CHUNK = 64 * 1024

        /** Error bodies are only for the operator message; a few KiB is plenty and bounds a hostile error page. */
        private const val ERROR_DETAIL_CAP = 8 * 1024

        private val AMZ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

enum class S3Addressing { PATH_STYLE, VIRTUAL_HOST }

/** A signed-but-not-yet-issued request: the seam [S3ObjectClient.signedRequest] returns. */
internal data class SignedRequest(
    val method: HttpMethod,
    val protocol: String,
    val host: String,
    val port: Int,
    val encodedPath: String,
    val query: List<Pair<String, String>>,
    /** The exact header set fed to SigV4 (host included); Authorization is [authorization]. */
    val signedHeaders: Map<String, String>,
    val authorization: String,
    val body: ByteArray?,
    val contentType: String?,
)

data class S3ClientConfig(
    /** The service endpoint, scheme included (R2: `https://<account>.r2.cloudflarestorage.com`). */
    val endpoint: String,
    /** SigV4 scope region (R2 uses `auto`). */
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val addressing: S3Addressing = S3Addressing.PATH_STYLE,
    /** Bounds a hung endpoint (TCP connect); generous, since static keys allow fresh connections per op. */
    val connectTimeoutMillis: Long = 10_000,
    /** Whole-call bound (TLS handshake + signed round-trip); also the socket read bound. */
    val requestTimeoutMillis: Long = 30_000,
    /** M2: the max GET/LIST response body buffered into memory; an oversize body refuses (DoS bound on a hostile endpoint). */
    val maxResponseBytes: Long = S3ObjectClient.DEFAULT_MAX_RESPONSE_BYTES,
)
