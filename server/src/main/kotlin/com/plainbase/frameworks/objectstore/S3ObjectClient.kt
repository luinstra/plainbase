package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.PercentCoding
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
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
            response.status.isSuccess() ->
                ObjectStat(etag = etagOf(response), size = response.headers[HttpHeaders.ContentLength]?.toLong() ?: 0L)
            response.status == HttpStatusCode.NotFound -> null
            else -> unexpected("HEAD", key, response)
        }
    }

    override suspend fun get(key: String): FetchedObject? {
        val response = execute(HttpMethod.Get, key)
        return when {
            response.status.isSuccess() -> FetchedObject(bytes = response.bodyAsBytes(), etag = etagOf(response))
            response.status == HttpStatusCode.NotFound -> null
            else -> unexpected("GET", key, response)
        }
    }

    override suspend fun put(key: String, bytes: ByteArray, condition: PutCondition, contentType: String?): PutOutcome {
        val conditionHeaders = when (condition) {
            PutCondition.None -> emptyMap()
            is PutCondition.IfMatch -> mapOf(HttpHeaders.IfMatch to condition.etag)
            PutCondition.IfAbsent -> mapOf(HttpHeaders.IfNoneMatch to "*")
        }
        val response = execute(HttpMethod.Put, key, extraHeaders = conditionHeaders, body = bytes, contentType = contentType)
        return when {
            response.status.isSuccess() -> PutOutcome.Stored(etag = etagOf(response))
            response.status == HttpStatusCode.PreconditionFailed || response.status == HttpStatusCode.Conflict ->
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
        return response.bodyAsText()
    }

    override fun close() = http.close()

    private suspend fun execute(
        method: HttpMethod,
        key: String?,
        query: List<Pair<String, String>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): HttpResponse {
        val request = signedRequest(method, key, query, extraHeaders, body, contentType)
        return http.request {
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
            // Ktor derives Host from url.host:port and Content-Type from the body, so only the
            // remaining signed headers plus Authorization are appended here (host/content-type
            // would otherwise be sent twice and break the signature).
            request.signedHeaders.forEach { (name, value) -> if (name != "host" && name != "content-type") headers.append(name, value) }
            headers.append(HttpHeaders.Authorization, request.authorization)
            request.body?.let { setBody(ByteArrayContent(it, contentType?.let(ContentType::parse))) }
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
        val payloadHash = SigV4Signer.sha256Hex(body ?: ByteArray(0))
        val signedHeaders = buildMap {
            // Sign the exact host[:port] the engine will send (the port only when non-default).
            put("host", if (endpoint.port == endpoint.protocol.defaultPort) requestHost else "$requestHost:${endpoint.port}")
            put("x-amz-content-sha256", payloadHash)
            put("x-amz-date", amzDate)
            contentType?.let { put("content-type", it) }
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
        val detail = response.bodyAsText().take(500)
        throw ObjectStoreException("$op '$target' failed with ${response.status}: $detail")
    }

    companion object {
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
)
