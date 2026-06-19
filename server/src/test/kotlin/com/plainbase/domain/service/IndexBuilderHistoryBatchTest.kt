package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.HistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.time.Instant

/**
 * W5 §5 tests 2 / 2b — the fix-C corollary asserted with a counting [HistoryProvider]: a full [rebuild]
 * issues ONE `lastCommits` call regardless of corpus size, and a targeted [reindex] issues ONE BOUNDED
 * `log(path, limit = 1)` lookup (re-review P2-1) — NOT the unbounded `lastCommits`, which buffers a page's
 * full history on every save. The counting provider records the `limit` arg, so 2b guards the BOUND, not
 * just the call count. Mirrors the `IndexBuilderReindexTargetedTest` counting-collaborator pattern — the
 * count is asserted at N ∈ {3, 30} so a per-page git spawn would show up as a count that scales with N.
 */
class IndexBuilderHistoryBatchTest : FunSpec({

    fun seedCorpus(root: java.nio.file.Path, n: Int) {
        repeat(n) { i -> writePage(root, "p%03d.md".format(i), "---\ntitle: Page $i\n---\n\n# Page $i\n\nbody $i.\n") }
    }

    test("rebuild calls lastCommits exactly once regardless of page count, never log per page") {
        for (n in listOf(3, 30)) {
            withTempTree({ seedCorpus(it, n) }) { root ->
                val history = CountingHistoryProvider()
                IndexHarness(root, history = history).use { h ->
                    h.builder.rebuild()
                    history.lastCommitsCalls shouldBe 1
                    history.logCalls shouldBe 0
                }
            }
        }
    }

    test("reindex(pageId) does one bounded log(path,1) lookup, never an unbounded lastCommits scan") {
        for (n in listOf(3, 30)) {
            withTempTree({ seedCorpus(it, n) }) { root ->
                val history = CountingHistoryProvider()
                IndexHarness(root, history = history).use { h ->
                    h.builder.rebuild()
                    val targetId = h.builder.current.pages.first().id
                    val target = h.builder.current.byId.getValue(targetId)
                    history.reset()
                    Files.write(root.resolve(target.path.value), "---\ntitle: Page 0\n---\n\n# Page 0\n\nnow $n.\n".toByteArray())

                    h.builder.reindex(targetId)

                    // Bounded single-commit read (re-review P2-1): exactly ONE log(path, 1) for the single
                    // target, ZERO unbounded lastCommits — and the limit arg itself is asserted, so the
                    // bound (not merely the call count) is guarded against a regression to a full scan.
                    history.logCalls shouldBe 1
                    history.lastLogPath shouldBe target.path
                    history.lastLogLimit shouldBe 1
                    history.lastCommitsCalls shouldBe 0
                }
            }
        }
    }
})

/** Counts the history-provider calls rebuild/reindex make; returns a stub commit per requested path. */
private class CountingHistoryProvider : HistoryProvider {
    override val enabled = true
    var lastCommitsCalls = 0
    var lastPathsSize = -1
    var logCalls = 0
    var lastLogPath: TreePath? = null
    var lastLogLimit: Int? = null

    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?) =
        error("rebuild/reindex must never commit")

    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> {
        lastCommitsCalls += 1
        lastPathsSize = paths.size
        return paths.associateWith { stubCommit }
    }

    override fun log(path: TreePath, limit: Int?): List<Commit> {
        logCalls += 1
        lastLogPath = path
        lastLogLimit = limit
        return listOf(stubCommit)
    }

    override fun diff(from: String, to: String, path: TreePath) = error("not used")
    override fun prepare() = Unit
    override fun gateCheck() = Unit

    fun reset() {
        lastCommitsCalls = 0
        lastPathsSize = -1
        logCalls = 0
        lastLogPath = null
        lastLogLimit = null
    }

    private val stubCommit = Commit(
        sha = "a".repeat(40),
        author = CommitIdentity("T", "t@localhost"),
        committer = CommitIdentity("T", "t@localhost"),
        authorTime = Instant.fromEpochSeconds(0),
        committerTime = Instant.fromEpochSeconds(0),
        message = "stub",
    )
}
