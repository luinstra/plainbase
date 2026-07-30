package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.service.CitationFactory
import com.plainbase.frameworks.filesystem.FileAtomics
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam (g), every mirror-apply-failure site, under the native image (an injected [FileAtomics]
 * throw is an NIO-edge divergence surface): GET-heal apply failure, precondition/ambiguous
 * read-back heal failure, poll-apply failure, hydrate-apply failure - plus the REV 4
 * CAS-after-failed-heal edge (map-absent, read-back heal write fails, still no `!!`/NPE). A
 * MINIMAL local [ObjectStoreClient]/[FileAtomics] test double (this source set cannot see the
 * Kotest-based `FakeObjectStore` in `src/test` - kotlin.test only, kept lean per policy).
 */
@Tag("native")
class ObjectMirrorApplyFailureTest {

    private val hasher = CitationFactory()::contentHash

    private fun newFixture(): Fixture {
        val mirrorRoot = Files.createTempDirectory("pb-native-mirror")
        val stateFile = mirrorRoot.resolveSibling("${mirrorRoot.fileName}-state")
        val atomics = MiniFailableAtomics()
        val ignoreRules = IgnoreRules()
        val mirror = LocalContentStore(root = mirrorRoot, ignoreRules = ignoreRules, atomics = atomics)
        val client = MiniFakeObjectStore()
        val state = MirrorState(stateFile)
        val store = ObjectContentStore(
            client = client,
            mirror = mirror,
            state = state,
            binding = RootBinding("https://fake|bucket|"),
            keyPrefix = "",
            pollSeconds = 3_600,
            dirtyPaths = { emptySet() },
            mirrorRoot = mirrorRoot,
            ignoreRules = ignoreRules,
            atomics = atomics,
        )
        return Fixture(mirrorRoot, stateFile, atomics, mirror, client, state, store)
    }

    private class Fixture(
        val mirrorRoot: Path,
        val stateFile: Path,
        val atomics: MiniFailableAtomics,
        val mirror: LocalContentStore,
        val client: MiniFakeObjectStore,
        val state: MirrorState,
        val store: ObjectContentStore,
    ) {
        fun close() {
            deleteRecursively(mirrorRoot)
            Files.deleteIfExists(stateFile)
        }
    }

    @Test
    fun `GET-heal apply failure - map-absent CAS whose read-back heal write throws still resolves correctly, no NPE`() {
        val fx = newFixture()
        try {
            val path = TreePath.require("map-absent-heal-fails.md")
            val original = "v1".toByteArray()
            fx.client.seed(fx.mirror.resolveRepoRelativePath(path), original)
            fx.mirror.write(path, original)
            fx.mirror.scan()
            // The map has NO entry for this path (never recorded) - forces the map-absent read-back.
            assertNull(fx.state.etagOf(path))
            // Fail ONLY the opportunistic GET-heal's mirror write (its own internal retry pair) - the
            // CAS's own subsequent apply (after a successful conditional PUT) must still succeed and
            // durably mirror the RESULT, proving the heal failure alone never blocks the outcome.
            fx.atomics.failFirst(2)

            val updated = "v2".toByteArray()
            val result = fx.store.compareAndSwapWrite(path, hasher(original), updated, hasher)

            assertEquals(CasResult.Written(hasher(updated)), result)
            assertEquals(updated.toString(Charsets.UTF_8), fx.mirror.read(path)?.toString(Charsets.UTF_8))
            assertEquals(fx.client.currentEtag(fx.mirror.resolveRepoRelativePath(path)), fx.state.etagOf(path))
        } finally {
            fx.close()
        }
    }

    @Test
    fun `precondition read-back heal failure - Mismatch still serves the authoritative bucket bytes, entry stays absent`() {
        val fx = newFixture()
        try {
            val path = TreePath.require("precondition-heal-fails.md")
            val original = "v1".toByteArray()
            val key = fx.mirror.resolveRepoRelativePath(path)
            fx.client.seed(key, original)
            fx.mirror.write(path, original)
            fx.mirror.scan()
            fx.state.recordConfirmed(path, fx.client.currentEtag(key)!!)
            fx.state.persist()
            // A concurrent external write lands at the bucket BEFORE our PUT - our If-Match is now stale.
            val external = "external write".toByteArray()
            fx.client.seed(key, external)
            fx.atomics.failAlways() // the precondition read-back's heal write always throws

            val result = fx.store.compareAndSwapWrite(path, hasher(original), "our bytes".toByteArray(), hasher)

            val mismatch = result as? CasResult.Mismatch ?: error("expected Mismatch, got $result")
            assertTrue(mismatch.currentBytes!!.contentEquals(external))
            assertNull(fx.state.etagOf(path)) // invalidated - never recordConfirmed over the failed heal
        } finally {
            fx.close()
        }
    }

    // NOTE: the poll-apply-failure site is NOT reachable from this source set - `pollOnce()` is
    // `internal` in `ObjectContentStore`, and `nativeTest` is not friend-associated with `main` for
    // Kotlin internal visibility (unlike the default `test` source set). It is covered instead as a
    // JVM/Kotest twin in `ObjectContentStoreConcurrencyTest.kt` (`src/test`), which reuses the SAME
    // `FileAtomics`-throw injection mechanism - the divergence surface under test here is the NIO
    // primitive (`FileAtomics.Real`/temp+ATOMIC_MOVE), which the other three sites below (all reached
    // through PUBLIC API) already exercise natively.

    @Test
    fun `hydrate-apply failure - boot does NOT fail on a single-key mirror-write error, the key stays absent`() {
        val fx = newFixture()
        try {
            val path = TreePath.require("hydrate-failing.md")
            fx.client.seed(fx.mirror.resolveRepoRelativePath(path), "bytes".toByteArray())
            fx.atomics.failAlways()

            fx.store.hydrate() // must NOT throw - a single-key mirror-write error is not a boot failure

            assertNull(fx.state.etagOf(path))
        } finally {
            fx.close()
        }
    }
}

/** A minimal in-memory [ObjectStoreClient] fake - kotlin.test dialect, no injected-failure modes. */
private class MiniFakeObjectStore : ObjectStoreClient {
    private val objects = mutableMapOf<String, Pair<ByteArray, String>>()
    private var seq = 0

    override suspend fun head(key: String): ObjectStat? = objects[key]?.let { ObjectStat(it.second, it.first.size.toLong()) }

    override suspend fun get(key: String, maxBytes: Long?): FetchedObject? =
        objects[key]?.let { FetchedObject(it.first.copyOf(), it.second) }

    override suspend fun getToFile(key: String, target: Path, requestTimeoutMillis: Long?): Boolean {
        val bytes = objects[key]?.first ?: return false
        Files.write(target, bytes)
        return true
    }

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        condition: PutCondition,
        contentType: String?,
        requestTimeoutMillis: Long?,
    ): PutOutcome {
        val current = objects[key]
        val allowed = when (condition) {
            PutCondition.None -> true
            is PutCondition.IfMatch -> current?.second == condition.etag
            PutCondition.IfAbsent -> current == null
        }
        if (!allowed) return PutOutcome.PreconditionFailed(412)
        val etag = "\"native-fake-${++seq}\""
        objects[key] = bytes.copyOf() to etag
        return PutOutcome.Stored(etag)
    }

    override suspend fun putFromFile(key: String, source: Path, contentType: String?, requestTimeoutMillis: Long?): PutOutcome =
        put(key, Files.readAllBytes(source), PutCondition.None, contentType, requestTimeoutMillis) // in-memory: buffer + delegate

    override suspend fun delete(key: String) {
        objects.remove(key)
    }

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int?): ListResponseParser.Listing {
        val entries = objects.filterKeys { it.startsWith(prefix) }
            .map { (k, v) -> ListResponseParser.Entry(k, v.second, size = v.first.size.toLong()) }
        return ListResponseParser.Listing(entries, isTruncated = false, nextContinuationToken = null)
    }

    override fun close() = Unit

    fun seed(key: String, bytes: ByteArray): String {
        val etag = "\"native-fake-${++seq}\""
        objects[key] = bytes.copyOf() to etag
        return etag
    }

    fun currentEtag(key: String): String? = objects[key]?.second
}

/**
 * A [FileAtomics] that throws when [shouldFailFor] (evaluated against the write TARGET, so a batch
 * with multiple keys can fail exactly one) says so - kotlin.test dialect twin of the src/test seam.
 */
private class MiniFailableAtomics(private val delegate: FileAtomics = FileAtomics.Real) : FileAtomics {
    var shouldFailFor: (Path) -> Boolean = { false }

    override fun createLink(link: Path, existing: Path) = delegate.createLink(link, existing)

    override fun atomicMove(source: Path, target: Path) {
        if (shouldFailFor(target)) throw IOException("simulated native mirror write failure")
        delegate.atomicMove(source, target)
    }

    override fun copyReplace(source: Path, target: Path) {
        if (shouldFailFor(target)) throw IOException("simulated native mirror write failure")
        delegate.copyReplace(source, target)
    }

    override fun fsync(path: Path) = delegate.fsync(path)

    fun failAlways() {
        shouldFailFor = { true }
    }

    /** Fails exactly the first [times] write attempts, then delegates normally. */
    fun failFirst(times: Int) {
        val remaining = java.util.concurrent.atomic.AtomicInteger(times)
        shouldFailFor = { remaining.getAndDecrement() > 0 }
    }
}

private fun deleteRecursively(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
