package com.plainbase.frameworks.git

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.FetchedObject
import com.plainbase.frameworks.objectstore.ListResponseParser
import com.plainbase.frameworks.objectstore.MirrorState
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectStat
import com.plainbase.frameworks.objectstore.ObjectStoreClient
import com.plainbase.frameworks.objectstore.PutCondition
import com.plainbase.frameworks.objectstore.PutOutcome
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * C5's bundle-DR round trip (native, process-exec divergence surface - real `git` subprocesses, no
 * mocking of [GitExecutor]): a REAL bucket-shipped bundle recovers commit-grained history after a
 * total `DATA_DIR` wipe, the boot reconcile captures EXACTLY the authority-vs-bundle divergence
 * (HOLE A), a crash mid-fetch self-heals, a crash between restore and reconcile is recovered via the
 * FORK-2 sentinel, and the reconcile's blob staging is byte-fidelity-safe under a hostile
 * `.gitattributes` (the ADR-0006 Amendment-2 golden, proven on the reconcile path - the SAME
 * [GitPlumbing] chokepoint [GitCliHistoryProvider.commit] uses).
 *
 * [NativeFakeObjectStore] is a hand-rolled, in-memory [ObjectStoreClient] defined HERE (not reused from
 * `server/src/test`'s `FakeObjectStore`): the `nativeTest` source set's classpath is `main` output only
 * (no `test` sourceSet), by design (kotlin.test/JUnit only, no Kotest/MockK on this classpath).
 */
@Tag("native")
class GitBundleDrNativeTest {

    @Test
    fun `restore + reconcile recovers content and history after a total DATA_DIR wipe, capturing exactly the divergence`() {
        val fake = NativeFakeObjectStore()
        val mirrorRoot = Files.createTempDirectory("plainbase-bundledr-native-mirror")
        val sentinelPath = mirrorRoot.resolveSibling("${mirrorRoot.fileName}-restore-pending")
        var shippedTip: String

        Harness(fake, mirrorRoot, sentinelPath).use { harness ->
            // Day 1: a page lands at the bucket, hydrates into the mirror, and gets committed + shipped.
            fake.seed("page1.md", "page one\n".toByteArray())
            harness.objectStore.hydrate()
            val page1 = TreePath.require("page1.md")
            harness.provider.commit(page1, requireNotNull(harness.mirror.read(page1)))
            harness.bundleDr.ship()
            shippedTip = requireNotNull(GitExecutor.parseSha(harness.exec.run(listOf("rev-parse", "HEAD")).stdout))

            // Day 2: a SECOND page lands at the bucket, but the process crashes before the next routine
            // ship/commit ever ran for it.
            fake.seed("page2.md", "page two (never committed before the crash)\n".toByteArray())
        }

        // Disaster: DATA_DIR (the whole mirror + its .git) is gone. A fresh instance, the same bucket.
        mirrorRoot.toFile().deleteRecursively()

        val freshMirrorRoot = Files.createTempDirectory("plainbase-bundledr-native-mirror2")
        Harness(fake, freshMirrorRoot, sentinelPath).use { harness ->
            val restored = harness.bundleDr.restore()
            assertTrue(restored.isRestored, "a bundle exists at the bucket - restore must be owed")
            assertEquals(shippedTip, restored.tip)

            harness.objectStore.hydrate(strict = restored.isRestored) // STRICT on the restore path (FORK 1)

            // The ADR-0006 Amendment-2 golden, proven on THIS reconcile path: a hostile .gitattributes +
            // clean filter in the recovered worktree (a local git-only artifact, never bucket-managed)
            // must NOT alter page2.md's committed bytes - GitPlumbing.stageBlob is filter-free.
            harness.exec.run(listOf("config", "filter.evil.clean", "tr a-z A-Z")).let { assertTrue(it.ok) }
            Files.writeString(freshMirrorRoot.resolve(".gitattributes"), "*.md filter=evil\n")

            harness.bundleDr.reconcileBootCommit(restored)

            // Content: both pages are back, byte-for-byte (page2 was never mangled by the hostile
            // .gitattributes' clean filter - GitPlumbing.stageBlob is filter-free).
            assertEquals("page one\n", String(requireNotNull(harness.mirror.read(TreePath.require("page1.md")))))
            assertEquals(
                "page two (never committed before the crash)\n",
                String(requireNotNull(harness.mirror.read(TreePath.require("page2.md")))),
            )

            // History: exactly TWO commits (the shipped one + the reconcile); the reconcile's parent is
            // the shipped tip, its message is the frozen reconcile message, and its committed blob for
            // page2.md is EXACTLY the bucket bytes (Amendment-2 byte-fidelity via the reconcile path).
            assertEquals("2", harness.exec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim())
            val headLine = harness.exec.run(listOf("log", "-1", "--format=%H %P %s")).stdoutText.trim()
            val (headSha, parentSha, message) = headLine.split(" ", limit = 3)
            assertEquals(shippedTip, parentSha)
            assertEquals("reconcile: bucket state at boot", message)
            assertEquals(headSha, requireNotNull(GitExecutor.parseSha(harness.exec.run(listOf("rev-parse", "HEAD")).stdout)))
            val committedPage2 = harness.exec.run(listOf("show", "HEAD:page2.md")).stdout
            assertEquals("page two (never committed before the crash)\n", String(committedPage2))

            // The sentinel is cleared - the reconcile obligation is fully discharged.
            assertFalse(Files.exists(sentinelPath))
        }
    }

    @Test
    fun `a crash mid-fetch (a partial dot-git from an earlier interrupted restore) self-heals on the next restore`() {
        val fake = NativeFakeObjectStore()
        val sourceRoot = Files.createTempDirectory("plainbase-bundledr-native-source")
        val sentinelPath = sourceRoot.resolveSibling("${sourceRoot.fileName}-restore-pending")
        Harness(fake, sourceRoot, sentinelPath).use { source ->
            fake.seed("page.md", "content\n".toByteArray())
            source.objectStore.hydrate()
            val path = TreePath.require("page.md")
            source.provider.commit(path, requireNotNull(source.mirror.read(path)))
            source.bundleDr.ship()
        }

        val mirrorRoot = Files.createTempDirectory("plainbase-bundledr-native-heal")
        Harness(fake, mirrorRoot, mirrorRoot.resolveSibling("${mirrorRoot.fileName}-restore-pending")).use { harness ->
            // Simulate an earlier interrupted restore: a `.git` directory exists but is INCOMPLETE (no
            // valid HEAD commit) - gitState() must classify DEFINITIVELY_INCOMPLETE (git says "not a git
            // repository", a DEFINITIVE signature, never UNREADABLE), and restore() must rename this
            // partial state aside and re-fetch cleanly rather than erroring on a half-built repo.
            Files.createDirectories(mirrorRoot.resolve(".git"))
            Files.writeString(mirrorRoot.resolve(".git/HEAD"), "ref: refs/heads/main\n")

            val restored = harness.bundleDr.restore()

            assertTrue(restored.isRestored)
            assertTrue(harness.exec.run(listOf("rev-parse", "--verify", "HEAD^{commit}")).ok, "the repo must be complete after the heal")
        }
    }

    @Test
    fun `a crash between restore and reconcile is recovered via the FORK-2 sentinel on the next boot`() {
        val fake = NativeFakeObjectStore()
        val sourceRoot = Files.createTempDirectory("plainbase-bundledr-native-source2")
        Harness(fake, sourceRoot, sourceRoot.resolveSibling("${sourceRoot.fileName}-restore-pending")).use { source ->
            fake.seed("page.md", "content\n".toByteArray())
            source.objectStore.hydrate()
            val path = TreePath.require("page.md")
            source.provider.commit(path, requireNotNull(source.mirror.read(path)))
            source.bundleDr.ship()
        }

        val mirrorRoot = Files.createTempDirectory("plainbase-bundledr-native-sentinel")
        val sentinelPath = mirrorRoot.resolveSibling("${mirrorRoot.fileName}-restore-pending")

        // Boot 1: restore() runs and leaves the sentinel present, but the process "crashes" (this
        // harness closes) BEFORE reconcileBootCommit ever runs.
        val firstTip = Harness(fake, mirrorRoot, sentinelPath).use { harness -> harness.bundleDr.restore() }
        assertTrue(firstTip.isRestored)
        assertTrue(Files.exists(sentinelPath), "the sentinel must survive an interrupted process (a plain file, not a ref)")

        // Boot 2: a FRESH process over the SAME (now-complete) mirror. The gate sees a COMPLETE .git
        // WITH the sentinel present - reconcile is merely owed, no re-fetch.
        Harness(fake, mirrorRoot, sentinelPath).use { harness ->
            val secondRestore = harness.bundleDr.restore()
            assertTrue(secondRestore.isRestored)
            assertEquals(firstTip.tip, secondRestore.tip)

            harness.objectStore.hydrate(strict = true)
            harness.bundleDr.reconcileBootCommit(secondRestore)

            assertFalse(Files.exists(sentinelPath))
        }
    }

    /** One `GitBundleDr` + collaborators over [mirrorRoot] - the CALLER owns [mirrorRoot]'s lifetime (it
     *  may be reused across successive [Harness] instances to simulate successive boots). */
    private class Harness(
        fake: NativeFakeObjectStore,
        val mirrorRoot: Path,
        val sentinelPath: Path,
    ) : AutoCloseable {
        val gitHome: Path = Files.createTempDirectory("plainbase-bundledr-native-home")
        val tmpDir: Path = Files.createTempDirectory("plainbase-bundledr-native-tmp")
        val ignoreRules = IgnoreRules()
        val mirror = LocalContentStore(root = mirrorRoot, ignoreRules = ignoreRules)
        val state = MirrorState(tmpDir.resolve("mirror-state"))
        val objectStore = ObjectContentStore(
            client = fake,
            mirror = mirror,
            state = state,
            keyPrefix = "",
            pollSeconds = 3600,
            dirtyPaths = { emptySet() },
            mirrorRoot = mirrorRoot,
            ignoreRules = ignoreRules,
        )
        val exec = GitExecutor(workTree = mirrorRoot, home = gitHome)
        val locks = GitRepoLocks()
        private val identity = CommitIdentity("Plainbase", "plainbase@localhost")
        private val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1_780_272_000L)
        }
        val provider = GitCliHistoryProvider(
            exec = exec,
            workTree = mirrorRoot,
            gitHome = gitHome,
            defaultAuthor = identity,
            defaultCommitter = identity,
            clock = clock,
            repoPath = { path -> mirror.resolveRepoRelativePath(path) },
            maintenance = {},
            repoWriteMonitor = locks.repoWrite,
        )
        val bundleDr = GitBundleDr(
            exec = exec,
            objectStore = objectStore,
            mirrorRoot = mirrorRoot,
            tmpDir = tmpDir,
            sentinelPath = sentinelPath,
            identity = identity,
            clock = clock,
            repoPath = { path -> mirror.resolveRepoRelativePath(path) },
            gitHome = gitHome,
            locks = locks,
        )

        override fun close() {
            objectStore.close()
            gitHome.toFile().deleteRecursively()
            tmpDir.toFile().deleteRecursively()
        }
    }
}

/**
 * A minimal, hand-rolled in-memory [ObjectStoreClient] for `nativeTest` (which cannot see the `test`
 * source set's `FakeObjectStore`): no network, no new dependency - just a map. Etags are opaque,
 * strong tokens; keys are wire-encoded exactly like the real S3 `encoding-type=url` LIST response
 * ([ObjectContentStore.listBucket] decodes via [com.plainbase.frameworks.objectstore.S3WireKey]).
 */
private class NativeFakeObjectStore : ObjectStoreClient {
    private val objects = linkedMapOf<String, Pair<ByteArray, String>>()
    private var etagSeq = 0

    fun seed(key: String, bytes: ByteArray): String {
        val etag = "\"native-fake-${etagSeq++}\""
        objects[key] = bytes.copyOf() to etag
        return etag
    }

    override suspend fun head(key: String): ObjectStat? =
        objects[key]?.let { ObjectStat(etag = it.second, size = it.first.size.toLong()) }

    override suspend fun get(key: String): FetchedObject? = objects[key]?.let { FetchedObject(it.first.copyOf(), it.second) }

    override suspend fun put(key: String, bytes: ByteArray, condition: PutCondition, contentType: String?): PutOutcome {
        val etag = "\"native-fake-${etagSeq++}\""
        objects[key] = bytes.copyOf() to etag
        return PutOutcome.Stored(etag)
    }

    override suspend fun delete(key: String) {
        objects.remove(key)
    }

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int?): ListResponseParser.Listing {
        val entries = objects.keys.filter { it.startsWith(prefix) }.sorted()
            .map { key -> ListResponseParser.Entry(key = PercentCoding.encodeSegment(key), etag = objects.getValue(key).second) }
        return ListResponseParser.Listing(entries, isTruncated = false, nextContinuationToken = null)
    }

    override fun close() = Unit
}
