package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider

/**
 * Git mode off — no history. Every operation is a no-op: [commit] returns null, the read ops return
 * empty, and nothing creates a `.git` or spawns a process. The empty returns are intentional no-op
 * semantics, NOT a not-yet-implemented stub.
 */
object NoOpHistoryProvider : HistoryProvider {

    override val enabled: Boolean = false

    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit? = null

    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = emptyMap()

    override fun log(path: TreePath, limit: Int?): List<Commit> = emptyList()

    override fun diff(from: String, to: String, path: TreePath): FileDiff =
        FileDiff(from = from, to = to, path = path, unifiedDiff = "")

    override fun prepare() = Unit

    override fun gateCheck() = Unit
}
