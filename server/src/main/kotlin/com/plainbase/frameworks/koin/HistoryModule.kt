package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.git.runAutoMaintenance
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.time.Clock

/**
 * Wires the optional Git-history layer (ADR-0006, chunk W4). The impl is selected by `git.enabled`
 * (explicit override) falling back to detection of a repo in CONTENT_DIR. The [WriteHistoryHook] single
 * adapts the chosen [HistoryProvider] to the W1 seam — `RestModule` passes it into the `WritePipeline`.
 *
 * The git-home is NOT created here (it touches an unvalidated/unlocked DATA_DIR — P1-3); the provider
 * creates it lazily at the first commit.
 */
val historyModule = module {
    // repoPath stages the RAW on-disk repo-relative path (r6b) — loose coupling: the provider gets a
    // function, not the whole ContentStore (registered in contentModule).
    single<HistoryProvider> { selectHistoryProvider(get(), get<ContentStore>()::resolveRepoRelativePath) }
    single<WriteHistoryHook> {
        val history = get<HistoryProvider>()
        WriteHistoryHook { path, bytes -> history.commit(path, bytes) }
    }
}

/**
 * Selects the history adapter for [config] (the testable core of [historyModule]): a [GitExecutor] over
 * CONTENT_DIR, then [gitEnabled] (the `git.enabled` override or repo auto-detection) → either a
 * [GitCliHistoryProvider] (with the off-monitor maintenance dispatcher wired — F3) or [NoOpHistoryProvider].
 * [repoPath] resolves the raw on-disk repo-relative path to stage in git (r6b). The git-home is NOT created
 * here (P1-3) — the provider creates it lazily at the first commit.
 */
internal fun selectHistoryProvider(
    config: PlainbaseConfig,
    repoPath: (TreePath) -> String = { it.value },
): HistoryProvider {
    val exec = GitExecutor(workTree = config.contentDir, home = config.dataDir.resolve("git-home"))
    return if (gitEnabled(config, exec)) {
        GitCliHistoryProvider(
            exec = exec,
            workTree = config.contentDir,
            gitHome = config.dataDir.resolve("git-home"),
            defaultAuthor = CommitIdentity(config.git.authorName, config.git.authorEmail),
            defaultCommitter = CommitIdentity(config.git.authorName, config.git.authorEmail),
            clock = Clock.System,
            repoPath = repoPath,
            // Auto-maintenance off the W1 monitor (F3): a daemon thread so it never blocks the save's
            // return, running the shared helper so the `gc --auto` fallback is live on git < 2.30 too (P2-C).
            maintenance = { Thread { runCatching { runAutoMaintenance(exec) } }.apply { isDaemon = true }.start() },
        )
    } else {
        NoOpHistoryProvider
    }
}

/**
 * Whether to run the Git provider: the explicit [PlainbaseConfig.GitConfig.enabled] override wins either
 * direction; `null` auto-detects a repo in CONTENT_DIR. Detection must catch `.git`-as-a-FILE (linked
 * worktree / submodule, P1-2): `Files.exists` (dir OR file) then a hermetic `rev-parse
 * --is-inside-work-tree` confirmation — `Files.isDirectory` alone would miss a worktree and silently
 * pick NoOp.
 *
 * Crucially, the presence of `.git` means Git mode is INTENDED, so ANY failure to confirm it is NOT a
 * reason to drop history (P1, refining P2-2): a missing binary (exitCode -1), `fatal: detected dubious
 * ownership` (exit 128, common under Docker/uid-mismatch), a permission error — all leave Git mode ON so
 * the startup gate produces the actionable "install git / set PLAINBASE_GIT_ENABLED=false" error instead
 * of silently recording NO history in a real repo. ONLY a DEFINITIVE run (git ran successfully and
 * explicitly reported "false" — a bare repo or inside `.git`) drops to NoOp.
 */
internal fun gitEnabled(config: PlainbaseConfig, exec: GitExecutor): Boolean {
    config.git.enabled?.let { return it }
    if (!Files.exists(config.contentDir.resolve(".git"))) return false
    val insideWorkTree = exec.run(listOf("rev-parse", "--is-inside-work-tree"))
    if (insideWorkTree.ok && insideWorkTree.stdoutText.trim() == "false") return false // definitively not a work tree
    return true // any failure (missing/dubious-ownership/permission) → keep Git on; the startup gate fails loud
}
