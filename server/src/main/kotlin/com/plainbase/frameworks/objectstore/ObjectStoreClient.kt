package com.plainbase.frameworks.objectstore

/**
 * The small object-store SPI the storage hybrid consumes (plan C0/Q7): five S3 ops plus the two
 * conditional PUT forms PB-WRITE-1's CAS mapping needs. Implementations speak S3-compatible HTTP
 * ([S3ObjectClient] is the default, hand-rolled over Ktor CIO); the Q7 escape hatch (an SDK-backed
 * client on the container tier) would be a drop-in behind this interface.
 *
 * ETags are OPAQUE tokens throughout: compared only against values the API returned, never
 * computed locally (multipart/SSE make etag != MD5; nothing here depends on that identity).
 */
interface ObjectStoreClient : AutoCloseable {

    /** Object metadata, or null when the key does not exist (HEAD 404). */
    suspend fun head(key: String): ObjectStat?

    /** Object bytes + etag, or null when the key does not exist (GET 404). */
    suspend fun get(key: String): FetchedObject?

    /** Writes [bytes] under [key], optionally guarded by [condition]. Never partial on refusal. */
    suspend fun put(key: String, bytes: ByteArray, condition: PutCondition = PutCondition.None, contentType: String? = null): PutOutcome

    /** Deletes [key]; deleting a missing key succeeds (S3 DELETE is idempotent). */
    suspend fun delete(key: String)

    /**
     * One ListObjectsV2 page under [prefix] (always requested with `encoding-type=url`; entry keys
     * come back RAW/URL-encoded, see [ListResponseParser]). Pass the previous page's
     * `nextContinuationToken` to continue; [maxKeys] is a test/probe seam.
     */
    suspend fun list(prefix: String, continuationToken: String? = null, maxKeys: Int? = null): ListResponseParser.Listing
}

/**
 * Iterates EVERY object across all ListObjectsV2 pages under [prefix], handing each wire [entry]
 * [ListResponseParser.Entry] to [action] (keys are RAW/URL-encoded - the caller decodes). The
 * continuation-token loop and `isTruncated` termination live in this ONE place, so no caller
 * hand-rolls the pagination loop-and-a-half; the parser's fail-closed truncated-without-token guard
 * is inherited per page. [action] is `suspend` so a caller can issue further ops per entry (e.g. a
 * per-key DELETE); callers keep bridging sync-over-suspend at their own call site.
 */
suspend fun ObjectStoreClient.forEachListedObject(prefix: String, action: suspend (ListResponseParser.Entry) -> Unit) {
    var token: String? = null
    do {
        val page = list(prefix, token)
        for (entry in page.contents) action(entry)
        token = page.nextContinuationToken
    } while (page.isTruncated)
}

data class ObjectStat(val etag: String, val size: Long)

class FetchedObject(val bytes: ByteArray, val etag: String)

/** The conditional-PUT forms of plan Q7/Q8: CAS replace and exclusive create. */
sealed interface PutCondition {
    /** Unconditional write. */
    data object None : PutCondition

    /** CAS replace: `If-Match` on the etag a previous read returned. */
    data class IfMatch(val etag: String) : PutCondition

    /** Exclusive create: `If-None-Match: *`. */
    data object IfAbsent : PutCondition
}

sealed interface PutOutcome {
    data class Stored(val etag: String) : PutOutcome

    /**
     * The precondition refused the write. [status] carries the provider's raw code because R2 and
     * S3 diverge here (409-vs-412 on concurrent create is exactly what the C0 findings note
     * records); the C4 outcome mapping owns the interpretation.
     */
    data class PreconditionFailed(val status: Int) : PutOutcome
}

/** A response outside the client's expected surface: wrong status, refused LIST shape, missing etag. */
class ObjectStoreException(message: String) : RuntimeException(message)
