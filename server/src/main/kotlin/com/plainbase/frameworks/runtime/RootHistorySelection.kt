package com.plainbase.frameworks.runtime

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.GitRepoLocks
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.lifecycle.GitMaintenanceTasks
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

internal data class RootHistorySelection(
    val byRoot: Map<RootName, HistoryProvider>,
    val objectHistory: DeferredObjectHistory,
    val objectLocks: Lazy<GitRepoLocks>,
)

internal fun prepareRootHistorySelection(
    config: PlainbaseConfig,
    registry: RootRegistry,
    primaryRepoPath: (TreePath) -> String,
    extraRepoPaths: Map<RootName, (TreePath) -> String>,
    objectHistory: DeferredObjectHistory,
    objectLocks: Lazy<GitRepoLocks>,
    maintenanceTasks: GitMaintenanceTasks = GitMaintenanceTasks.inert(),
): RootHistorySelection {
    val byRoot = buildMap {
        val primary = registry.primary
        put(
            primary.name,
            preparePrimaryHistoryProvider(config, primary, primaryRepoPath, objectHistory, objectLocks, maintenanceTasks),
        )
        registry.extras.forEach { root ->
            put(root.name, extraHistoryProvider(config, root, extraRepoPaths, maintenanceTasks))
        }
    }
    return RootHistorySelection(byRoot, objectHistory, objectLocks)
}

private fun preparePrimaryHistoryProvider(
    config: PlainbaseConfig,
    primary: Root,
    repoPath: (TreePath) -> String,
    objectHistory: DeferredObjectHistory,
    objectLocks: Lazy<GitRepoLocks>,
    maintenanceTasks: GitMaintenanceTasks,
): HistoryProvider = when (primary.history) {
    HistoryMode.OFF -> NoOpHistoryProvider
    HistoryMode.NATIVE -> claimedRootProvider(
        config = config,
        root = requireNotNull(primary.localPath) { "a native-history root must be local-backed" },
        repoPath = repoPath,
        maintenanceTasks = maintenanceTasks,
    )
    HistoryMode.AUTO -> autoHistoryProvider(config, repoPath, objectHistory, objectLocks, maintenanceTasks)
}

private fun autoHistoryProvider(
    config: PlainbaseConfig,
    repoPath: (TreePath) -> String,
    objectHistory: DeferredObjectHistory,
    objectLocks: Lazy<GitRepoLocks>,
    maintenanceTasks: GitMaintenanceTasks,
): HistoryProvider {
    if (config.storage.backend == StorageBackend.OBJECT && config.git.enabled == true) {
        val maintenanceExec = GitExecutor(workTree = config.dataDir.resolve("mirror"), home = config.dataDir.resolve("git-home"))
        return selectHistoryProvider(
            config = config,
            contentRoot = config.mainContentRoot(),
            repoPath = repoPath,
            objectMaintenance = {
                maintenanceTasks.dispatch(maintenanceExec)
                objectHistory.onCommit()
            },
            repoWriteMonitor = objectLocks.value.repoWrite,
        )
    }
    return selectHistoryProvider(
        config = config,
        contentRoot = config.mainContentRoot(),
        repoPath = repoPath,
        maintenanceTasks = maintenanceTasks,
    )
}

private fun extraHistoryProvider(
    config: PlainbaseConfig,
    root: Root,
    repoPaths: Map<RootName, (TreePath) -> String>,
    maintenanceTasks: GitMaintenanceTasks,
): HistoryProvider =
    if (root.history == HistoryMode.NATIVE) {
        claimedRootProvider(
            config = config,
            root = requireNotNull(root.localPath) { "a native-history root must be local-backed" },
            repoPath = repoPaths.getValue(root.name),
            maintenanceTasks = maintenanceTasks,
        )
    } else {
        NoOpHistoryProvider
    }

private fun claimedRootProvider(
    config: PlainbaseConfig,
    root: Path,
    repoPath: (TreePath) -> String,
    maintenanceTasks: GitMaintenanceTasks,
): HistoryProvider {
    val gitHome = config.dataDir.resolve("git-home")
    val exec = GitExecutor(workTree = root, home = gitHome)
    return GitCliHistoryProvider(
        exec = exec,
        workTree = root,
        gitHome = gitHome,
        defaultAuthor = CommitIdentity(config.git.authorName, config.git.authorEmail),
        defaultCommitter = CommitIdentity(config.git.authorName, config.git.authorEmail),
        clock = Clock.System,
        repoPath = repoPath,
        maintenance = { maintenanceTasks.dispatch(exec) },
        claimedRepo = true,
    )
}

/** Selects the history adapter while preserving the configured backend and git-enabled tri-state. */
internal fun selectHistoryProvider(
    config: PlainbaseConfig,
    contentRoot: Path,
    repoPath: (TreePath) -> String = { it.value },
    objectMaintenance: (() -> Unit)? = null,
    repoWriteMonitor: Any? = null,
    maintenanceTasks: GitMaintenanceTasks = GitMaintenanceTasks.inert(),
): HistoryProvider {
    if (config.storage.backend == StorageBackend.OBJECT) {
        if (config.git.enabled != true) return NoOpHistoryProvider
        // Mirror validation belongs to post-lock restore; this only wires the deferred provider.
        val mirrorRoot = config.dataDir.resolve("mirror")
        val gitHome = config.dataDir.resolve("git-home")
        return GitCliHistoryProvider(
            exec = GitExecutor(workTree = mirrorRoot, home = gitHome),
            workTree = mirrorRoot,
            gitHome = gitHome,
            defaultAuthor = CommitIdentity(config.git.authorName, config.git.authorEmail),
            defaultCommitter = CommitIdentity(config.git.authorName, config.git.authorEmail),
            clock = Clock.System,
            repoPath = repoPath,
            maintenance = objectMaintenance,
            repoWriteMonitor = repoWriteMonitor,
            objectMode = true,
        )
    }
    val exec = GitExecutor(workTree = contentRoot, home = config.dataDir.resolve("git-home"))
    return if (gitEnabled(config, contentRoot, exec)) {
        GitCliHistoryProvider(
            exec = exec,
            workTree = contentRoot,
            gitHome = config.dataDir.resolve("git-home"),
            defaultAuthor = CommitIdentity(config.git.authorName, config.git.authorEmail),
            defaultCommitter = CommitIdentity(config.git.authorName, config.git.authorEmail),
            clock = Clock.System,
            repoPath = repoPath,
            maintenance = { maintenanceTasks.dispatch(exec) },
        )
    } else {
        NoOpHistoryProvider
    }
}

/** Detects or honors git mode; only a successful explicit `false` result disables detection. */
internal fun gitEnabled(config: PlainbaseConfig, contentRoot: Path, exec: GitExecutor): Boolean {
    config.git.enabled?.let { return it }
    // A .git file is intentional linked-worktree compatibility, not a missing repository.
    if (!Files.exists(contentRoot.resolve(".git"))) return false
    val insideWorkTree = exec.run(listOf("rev-parse", "--is-inside-work-tree"))
    // Keep uncertain detection enabled so the startup gate can report the actionable fault.
    if (insideWorkTree.ok && insideWorkTree.stdoutText.trim() == "false") return false
    return true
}
