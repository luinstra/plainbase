package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The W4 test harness, mirroring [withTempTree][com.plainbase.domain.service.withTempTree]: a fresh
 * temp content tree + a hermetic [GitExecutor] over it (work tree = the tree, home = a separate temp
 * dir), always cleaned up. A fixed [Clock] feeds reproducible commit timestamps; the JGit oracle (a
 * testImplementation, JVM-only dep) reads the commits shell-`git` wrote back as the differential check.
 */
fun <T> withGitRepo(block: (root: Path, exec: GitExecutor) -> T): T = withGitRepoHome { root, exec, _ -> block(root, exec) }

/** Like [withGitRepo] but also exposes the executor's git-home (where the provider's temp indexes live). */
fun <T> withGitRepoHome(block: (root: Path, exec: GitExecutor, home: Path) -> T): T {
    val root = Files.createTempDirectory("plainbase-git-test")
    val home = Files.createTempDirectory("plainbase-git-home")
    return try {
        block(root, GitExecutor(workTree = root, home = home), home)
    } finally {
        root.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }
}

/** A [Clock] pinned at [seconds] epoch — reproducible commit SHAs across runs. */
fun fixedClock(seconds: Long = FIXED_EPOCH_SECONDS): Clock = object : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(seconds)
}

/** A canonical, reproducible commit time (2026-06-13T00:00:00Z) for the pinned-SHA tests. */
const val FIXED_EPOCH_SECONDS: Long = 1_780_272_000L

/** The default test identity. */
fun testIdentity(name: String = "Plainbase", email: String = "plainbase@localhost") = CommitIdentity(name, email)

/**
 * Builds a [GitCliHistoryProvider] over [exec] with the harness defaults: the repo [workTree] (the same
 * root [exec] was built over), a git-home holding the temp indexes, the fixed clock, and a no-op
 * maintenance dispatcher (so a test never spawns background GC unless it injects its own). [home] must
 * match the executor's home so the temp indexes land in a real, writable dir.
 */
fun providerOver(
    exec: GitExecutor,
    workTree: Path,
    home: Path,
    author: CommitIdentity = testIdentity(),
    committer: CommitIdentity = testIdentity(),
    clock: Clock = fixedClock(),
    repoPath: (TreePath) -> String = { it.value },
    maintenance: (() -> Unit)? = {},
): GitCliHistoryProvider = GitCliHistoryProvider(
    exec = exec,
    workTree = workTree,
    gitHome = home,
    defaultAuthor = author,
    defaultCommitter = committer,
    clock = clock,
    repoPath = repoPath,
    maintenance = maintenance,
)

// ---- JGit oracle reads -----------------------------------------------------------------------

/** Opens the repo at [root] through JGit (the differential oracle). */
fun openOracle(root: Path): Repository =
    FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).readEnvironment().findGitDir().build()

/** The commits reachable from HEAD, newest first, via JGit. */
fun Repository.headCommits(): List<RevCommit> = RevWalk(this).use { walk ->
    val head = resolve("HEAD") ?: return emptyList()
    walk.markStart(walk.parseCommit(head))
    walk.toList()
}

/** The raw bytes of [path] in [commit]'s tree, via JGit (byte-fidelity oracle). */
fun Repository.blobBytes(commit: RevCommit, path: String): ByteArray? = TreeWalk.forPath(this, path, commit.tree)?.use { tw ->
    open(tw.getObjectId(0)).bytes
}

/** Every path present in [commit]'s tree, via JGit (one-file-isolation oracle). */
fun Repository.treePaths(commit: RevCommit): List<String> = TreeWalk(this).use { tw ->
    tw.addTree(commit.tree)
    tw.isRecursive = true
    buildList { while (tw.next()) add(tw.pathString) }
}
