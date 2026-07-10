package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.PercentCoding
import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/**
 * An in-memory, TEST-ONLY [ObjectStoreClient] (C4 step 4): no new main-source dependency, no wire
 * I/O. Backs the differential-oracle suite and every Q8/Q13/R11/R16 injected-failure test.
 *
 * **Conditional-failure status is PARAMETERIZED over {409, 412}, not pinned to a guessed provider
 * value** (SP1 found R2 = 412 for both `If-Match`-stale and `If-Absent`-exists; AWS S3 is `?`). The
 * fake returns `PutOutcome.PreconditionFailed(conflictStatus)` on a refused conditional PUT, so the
 * oracle proves the C4 mapping identical at both codes - provider-agnostic by construction.
 *
 * Etags are opaque, strong, double-quoted tokens (`"fake-N"`); DELETE is idempotent (deleting an
 * absent key is a no-op, matching S3).
 */
class FakeObjectStore(
    private val conflictStatus: Int = 412,
) : ObjectStoreClient {

    private val lock = Any()
    private val objects = linkedMapOf<String, Stored>() // insertion order - deterministic LIST paging
    private val etagSeq = AtomicInteger()
    private val puts = AtomicInteger()
    private val gets = AtomicInteger()
    private val heads = AtomicInteger()
    private val lists = AtomicInteger()

    /** Total `put` calls issued (the create-family "no redundant PUT after heal" probe). */
    val putCount: Int get() = puts.get()

    /** Total `get`/`head`/`list` calls issued (the clean-create "no network read on the happy path" probe). */
    val getCount: Int get() = gets.get()
    val headCount: Int get() = heads.get()
    val listCount: Int get() = lists.get()

    /** The number of live keys in the bucket (the NFC-collision "exactly one key" assertion). */
    fun keyCount(): Int = synchronized(lock) { objects.size }

    /** The monitor tripwire seam (M1's no-lock-across-network rule): invoked at the top of every op. */
    var onNetworkOp: () -> Unit = {}

    /** When set, every op throws the DEFINITIVE pre-send connect-refusal class (Q13). */
    var connectRefusal: Boolean = false

    /** When set, every op throws the R16 fail-closed TLS/signature rejection class. */
    var tlsRejection: Boolean = false

    /** Keys whose NEXT `put` throws an AMBIGUOUS failure BEFORE the write applies (Q8a, "not landed"). */
    val ambiguousBeforeApply: MutableSet<String> = mutableSetOf()

    /** Keys whose NEXT `put` applies the write, THEN throws ambiguously (Q8a, "landed after all"). */
    val ambiguousAfterApply: MutableSet<String> = mutableSetOf()

    /** Per-key HEAD call counts - the Q8c audit-trail call-count spy. */
    val headCalls: MutableMap<String, Int> = mutableMapOf()

    /**
     * R11 named sync point (step 4): with [armInterleave] set, `put` counts down [pollCycleReached]
     * (signalling "the write has reached the point of return") and AWAITS [putMayComplete] before
     * returning `Stored` - so a test can drive a poll cycle to its apply phase in between.
     */
    var armInterleave: Boolean = false
    val pollCycleReached: CountDownLatch = CountDownLatch(1)
    val putMayComplete: CountDownLatch = CountDownLatch(1)

    /** Fails exactly the NEXT `head` call (auto-resets) - for isolating the Q8c audit HEAD from the PUT. */
    var failNextHead: Boolean = false

    /** Keys whose NEXT `get` throws once then removes itself - a one-shot GET failure (hydrate-retry test). */
    val failNextGetFor: MutableSet<String> = mutableSetOf()

    override suspend fun head(key: String): ObjectStat? {
        onNetworkOp()
        heads.incrementAndGet()
        if (synchronized(lock) { failNextHead.also { failNextHead = false } }) {
            throw ObjectStoreException("simulated HEAD-only failure")
        }
        failIfInjected()
        synchronized(lock) { headCalls.merge(key, 1, Int::plus) }
        return synchronized(lock) { objects[key]?.let { ObjectStat(etag = it.etag, size = it.bytes.size.toLong()) } }
    }

    override suspend fun get(key: String, maxBytes: Long?): FetchedObject? {
        // In-memory: no wire body to cap, so [maxBytes] is irrelevant here (the M2/R3-1 cap lives in S3ObjectClient).
        onNetworkOp()
        gets.incrementAndGet()
        if (synchronized(lock) { failNextGetFor.remove(key) }) {
            throw ObjectStoreException("simulated one-shot GET failure: $key")
        }
        failIfInjected()
        return synchronized(lock) { objects[key]?.let { FetchedObject(it.bytes.copyOf(), it.etag) } }
    }

    override suspend fun getToFile(key: String, target: java.nio.file.Path, requestTimeoutMillis: Long?): Boolean {
        // In-memory: reuse [get] then write the bytes to [target] (the streaming vs buffered distinction is
        // an S3ObjectClient wire concern; the fake only needs to honor the found/not-found + file-write contract).
        val fetched = get(key) ?: return false
        java.nio.file.Files.write(target, fetched.bytes)
        return true
    }

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        condition: PutCondition,
        contentType: String?,
        requestTimeoutMillis: Long?,
    ): PutOutcome {
        onNetworkOp()
        puts.incrementAndGet()
        failIfInjected()
        if (armInterleave) {
            pollCycleReached.countDown()
            putMayComplete.await(10, TimeUnit.SECONDS)
        }
        if (synchronized(lock) { ambiguousBeforeApply.remove(key) }) {
            throw ObjectStoreException("simulated ambiguous PUT failure (pre-apply, op NOT landed): $key")
        }
        val stored = synchronized(lock) {
            val current = objects[key]
            val allowed = when (condition) {
                PutCondition.None -> true
                is PutCondition.IfMatch -> current?.etag == condition.etag
                PutCondition.IfAbsent -> current == null
            }
            if (!allowed) return@synchronized null
            val next = Stored(bytes.copyOf(), "\"fake-${etagSeq.incrementAndGet()}\"")
            objects[key] = next
            next
        } ?: return PutOutcome.PreconditionFailed(conflictStatus)
        if (synchronized(lock) { ambiguousAfterApply.remove(key) }) {
            throw ObjectStoreException("simulated ambiguous PUT failure (post-apply, op DID land): $key")
        }
        return PutOutcome.Stored(stored.etag)
    }

    override suspend fun putFromFile(
        key: String,
        source: java.nio.file.Path,
        contentType: String?,
        requestTimeoutMillis: Long?,
    ): PutOutcome {
        // In-memory: buffer the file then delegate to [put] (the streaming-vs-buffered distinction is an
        // S3ObjectClient wire concern; the fake only needs to honor the unconditional-PUT contract).
        return put(key, java.nio.file.Files.readAllBytes(source), PutCondition.None, contentType, requestTimeoutMillis)
    }

    override suspend fun delete(key: String) {
        onNetworkOp()
        failIfInjected()
        synchronized(lock) { objects.remove(key) }
    }

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int?): ListResponseParser.Listing {
        onNetworkOp()
        lists.incrementAndGet()
        failIfInjected()
        val keys = synchronized(lock) { objects.keys.filter { it.startsWith(prefix) }.sorted() }
        val start = continuationToken?.toIntOrNull() ?: 0
        val page = keys.drop(start).let { if (maxKeys != null) it.take(maxKeys) else it }
        val truncated = start + page.size < keys.size
        val entries = page.map { key ->
            val stored = synchronized(lock) { objects.getValue(key) }
            ListResponseParser.Entry(key = wireEncode(key), etag = stored.etag)
        }
        return ListResponseParser.Listing(entries, truncated, if (truncated) (start + page.size).toString() else null)
    }

    override fun close() = Unit

    /** Test-only direct seed (bypasses [PutCondition]), returning the minted etag. */
    fun seed(key: String, bytes: ByteArray): String {
        val etag = "\"fake-${etagSeq.incrementAndGet()}\""
        synchronized(lock) { objects[key] = Stored(bytes.copyOf(), etag) }
        return etag
    }

    /** The current etag for [key], or null when absent - a test-setup/assertion convenience. */
    fun currentEtag(key: String): String? = synchronized(lock) { objects[key]?.etag }

    /** The current bytes for [key], or null when absent - a test-setup/assertion convenience. */
    fun currentBytes(key: String): ByteArray? = synchronized(lock) { objects[key]?.bytes?.copyOf() }

    private fun failIfInjected() {
        if (tlsRejection) throw SSLHandshakeException("simulated CA/TLS rejection")
        if (connectRefusal) throw ConnectException("simulated connection refusal")
    }

    /**
     * The S3 `encoding-type=url` wire form (seam a / SP1): the WHOLE key percent-encoded, `/`
     * included - [S3WireKey.decode] expects exactly this (`allowEncodedSlash = true`). Reusing
     * [PercentCoding.encodeSegment] over the whole string (not [PercentCoding.encodePath], which
     * preserves `/` literally) gives byte-for-byte the same class this decode expects: `%20` for
     * space, `%2F` for `/`, `%26`/`%24`/`%2B`, UTF-8 `%`-bytes, never `+`-for-space.
     */
    private fun wireEncode(key: String): String = PercentCoding.encodeSegment(key)

    private class Stored(val bytes: ByteArray, val etag: String)
}
