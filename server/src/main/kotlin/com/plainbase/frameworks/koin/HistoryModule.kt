package com.plainbase.frameworks.koin

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.GitRepoLocks
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.git.runAutoMaintenance
import com.plainbase.frameworks.objectstore.ObjectContentStore
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * Wires the optional Git-history layer (ADR-0006, extended by C5's git-over-the-mirror, made per-root by C4's
 * ADR-0011 D4). EVERY root's impl - main included - is selected by its own `history` knob; only main's AUTO arm
 * falls back to the `git.enabled` override / repo detection in its content root (`mainContentRoot()` - contentDir
 * for every legacy config). The [WriteHistoryHook] single adapts the per-root [HistoryProviders] map to the
 * write-pipeline seam - `RestModule` passes it into the `WritePipeline`.
 *
 * [GitRepoLocks] and [GitBundleDr] are declared UNCONDITIONALLY-but-LAZY singles (the R9 exemplar,
 * `ContentModule.kt`'s `contentDirStoreConstructions`): resolved ONLY on the object+`git.enabled=true`
 * path (this file's [autoHistoryProvider] only calls `get<GitRepoLocks>()` there, and
 * `Application.kt`'s `serve()` only calls `get<GitBundleDr>()` there too), so a LOCAL boot or a
 * git-disabled object boot never constructs either.
 *
 * The git-home is NOT created here (it touches an unvalidated/unlocked DATA_DIR); the providers
 * create it lazily at the first commit/reconcile.
 */
val historyModule = module {
    single { GitRepoLocks() }
    single<GitBundleDr> {
        val config = get<PlainbaseConfig>()
        GitBundleDr(
            exec = GitExecutor(workTree = config.dataDir.resolve("mirror"), home = config.dataDir.resolve("git-home")),
            objectStore = get<ObjectContentStore>(),
            mirrorRoot = config.dataDir.resolve("mirror"),
            tmpDir = config.dataDir.resolve("tmp"),
            sentinelPath = config.dataDir.resolve("restore-pending"),
            identity = CommitIdentity(config.git.authorName, config.git.authorEmail),
            clock = Clock.System,
            repoPath = { path -> get<ObjectContentStore>().mirror.resolveRepoRelativePath(path) },
            gitHome = config.dataDir.resolve("git-home"),
            locks = get<GitRepoLocks>(),
        )
    }
    // repoPath stages the RAW on-disk repo-relative path - loose coupling: the provider gets a
    // function, not a whole store. LOCAL binds to the CONCRETE LocalContentStore (contentModule
    // registers it and aliases the ContentStore port to it): staging git paths is a local-filesystem
    // concern the backend-neutral port deliberately does not carry. OBJECT binds to the mirror inside
    // ObjectContentStore (C5) - both resolve their store ON CALL (commit time), never at wiring time.
    single<HistoryProvider> {
        val config = get<PlainbaseConfig>()
        val main = get<RootRegistry>().main
        val repoPath: (TreePath) -> String = { path ->
            if (config.storage.backend == StorageBackend.LOCAL) {
                get<LocalContentStore>().resolveRepoRelativePath(path)
            } else {
                get<ObjectContentStore>().mirror.resolveRepoRelativePath(path)
            }
        }
        // This single is MAIN's provider, and it OBEYS MAIN'S MODE - the same three-way selection every extra
        // gets, not a mode-blind legacy path. `off` really is off (an explicit `history = off` must not commit,
        // and the AUTO arm below commits whenever `git.enabled`/detection says so), and `native` really is
        // CLAIMED (it must run the strict four-check guard and never `git init`) - main is where an operator is
        // MOST likely to be pointing Plainbase at a repository that exists for somebody else's reasons.
        //
        // AUTO - which every synthesized (legacy) config produces, object mode included - is EXACTLY today's
        // selectHistoryProvider result, `git.enabled` tri-state and all. A contradiction between the two knobs
        // is already a boot error naming both (PlainbaseConfig.requireCoherentMainHistory), never a silent winner.
        when (main.history) {
            HistoryMode.OFF -> NoOpHistoryProvider
            // A `roots {}` block is LOCAL-only (object + roots{} is refused at parse), and only a roots{} block
            // can set main's mode, so a NATIVE main always has a declared local path.
            HistoryMode.NATIVE -> claimedRootProvider(
                config = config,
                root = requireNotNull(main.localPath) { "a native-history root must be local-backed" },
                repoPath = repoPath,
            )
            HistoryMode.AUTO -> autoHistoryProvider(config, repoPath, koin = this)
        }
    }
    // The per-root history providers (ADR-0011 D4). `single<HistoryProvider>` above STAYS and is main's, so every
    // main-only consumer (the object-mode warning) keeps resolving unchanged.
    //
    // Selection per root:
    //  - main: its own `history` knob, resolved by the single above (AUTO = today's behavior, `git.enabled`
    //    tri-state and all; `off` = the no-op; `native` = the strict-guarded provider).
    //  - extras: `off` -> no-op. `native` -> a guarded provider over the root's own declared path, which never inits
    //    and refuses the boot on a linked worktree / submodule / foreign repo. `auto` is a boot VALIDATION error
    //    (rejected at config parse): detect-and-maybe-init with lax worktree acceptance is precisely what D4 exists
    //    to deny an extra root.
    //
    // Lazily constructed per the R9 discipline - a NoOp root builds no GitExecutor and touches no disk.
    single {
        val config = get<PlainbaseConfig>()
        val registry = get<RootRegistry>()
        val stores = get<RootStores>()
        HistoryProviders(
            registry.roots.associate { root ->
                root.name to when {
                    root.name == registry.main.name -> get<HistoryProvider>()
                    root.history == HistoryMode.NATIVE -> claimedRootProvider(
                        config = config,
                        root = requireNotNull(root.localPath) { "a native-history root must be local-backed" },
                        // The RAW on-disk repo-relative path, resolved through THIS root's store - so an NFD-named
                        // file is staged at its real path rather than at an NFC phantom that does not exist.
                        repoPath = { path -> stores.localOrNull(root.name)?.resolveRepoRelativePath(path) ?: path.value },
                    )
                    else -> NoOpHistoryProvider
                }
            },
        )
    }
    single<WriteHistoryHook> {
        // Root-aware: history is per-root topology, so the hook DISPATCHES over the provider map rather than
        // closing over one. A root with `history = off` records nothing and returns a null SHA, exactly as the
        // no-op adapter always has.
        val histories = get<HistoryProviders>()
        WriteHistoryHook { root, path, bytes, author, committer ->
            histories[root].commit(path, bytes, author, committer)?.sha
        }
    }
}

/**
 * Main's AUTO arm — detect-or-override, the pre-multi-root selection verbatim (`git.enabled` tri-state and all).
 *
 * OBJECT + `git.enabled == true` is the ONLY combination that touches [GitRepoLocks]/[GitBundleDr]; every other
 * combination (LOCAL, OBJECT git-off/null) leaves both singles UNRESOLVED (R9), which is why [koin] is threaded in
 * rather than the two collaborators being resolved eagerly at the call site.
 */
private fun autoHistoryProvider(config: PlainbaseConfig, repoPath: (TreePath) -> String, koin: Scope): HistoryProvider =
    with(koin) {
        if (config.storage.backend == StorageBackend.OBJECT && config.git.enabled == true) {
            // A throwaway GitExecutor for the maintenance daemon (GitExecutor is stateless - hermetic per
            // call - so a second instance over the same workTree/home is equivalent to the one
            // selectHistoryProvider constructs internally for the provider itself).
            val maintenanceExec = GitExecutor(workTree = config.dataDir.resolve("mirror"), home = config.dataDir.resolve("git-home"))
            selectHistoryProvider(
                config = config,
                // Inert in object mode: the OBJECT arm roots at dataDir/mirror, never at contentRoot.
                contentRoot = config.mainContentRoot(),
                repoPath = repoPath,
                // BLOCKING #2 fix: the C5 per-save ship OBLIGATION is decided SYNCHRONOUSLY here
                // (`recordCommit()` is in-memory-only - no git call, no network call) so a crash right
                // after this save returns success can never race past a "never got scheduled" async
                // dispatch (the prior bug: the trigger only ran AFTER auto-maintenance, on ITS thread).
                // Auto-maintenance (best-effort gc, non-critical) still runs off-monitor on its OWN
                // daemon thread, independent of - never gating - the ship dispatch below. The actual
                // ship (the slow `bundle create` + network PUT) is dispatched OFF this thread, only when
                // due, so it never runs under the write-pipeline monitor or `GitRepoLocks.repoWrite`.
                objectMaintenance = {
                    Thread { runCatching { runAutoMaintenance(maintenanceExec) } }.apply { isDaemon = true }.start()
                    // R1/R5: record synchronously (BLOCKING #2) + dispatch the slow ship onto GitBundleDr's OWNED
                    // single-thread executor - NOT a raw daemon `Thread` (close() cannot join those, so one could
                    // ship against a closed transport) and NOT wrapped in runCatching (which re-swallowed the fatal
                    // Errors shipBestEffort deliberately rethrows). onCommitAsync owns both halves.
                    get<GitBundleDr>().onCommitAsync()
                },
                repoWriteMonitor = get<GitRepoLocks>().repoWrite,
            )
        } else {
            selectHistoryProvider(config, config.mainContentRoot(), repoPath)
        }
    }

/**
 * The per-root [HistoryProvider] map. Registry-built, so a name it does not hold is a PROGRAMMING error - the
 * [RootStores] rule, and the same loud, named failure for the same reason.
 */
class HistoryProviders(private val byRoot: Map<RootName, HistoryProvider>) {

    /** Main's provider — the one every main-only consumer (the capability flag, the object-mode warning) wants. */
    val main: HistoryProvider get() = get(RootName.MAIN)

    operator fun get(root: RootName): HistoryProvider = requireNotNull(byRoot[root]) {
        "no history provider for root '$root': a per-root lookup ran on an unregistered root"
    }
}

/**
 * A provider over a root whose repository the operator CLAIMED (`history = native`): it never `git init`s and its
 * gate check runs the strict four-check guard. Its git-home is Plainbase's own (in DATA_DIR), because the hermetic
 * executor needs one — but the REPOSITORY is entirely the operator's.
 */
private fun claimedRootProvider(config: PlainbaseConfig, root: Path, repoPath: (TreePath) -> String): HistoryProvider {
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
        maintenance = { Thread { runCatching { runAutoMaintenance(exec) } }.apply { isDaemon = true }.start() },
        claimedRepo = true,
    )
}

/**
 * Selects the history adapter for [config] (the testable core of [historyModule]): OBJECT mode wires a
 * real [GitCliHistoryProvider] over `DATA_DIR/mirror` when `git.enabled=true` (C5), or [NoOpHistoryProvider]
 * otherwise (`false`/`null` - auto-detection against the now-ignored CONTENT_DIR would be meaningless
 * against a fresh mirror and must not run). LOCAL mode keeps [gitEnabled] (the `git.enabled` override or
 * repo auto-detection in [contentRoot], main's content root - `config.mainContentRoot()` at the module
 * seam, deliberately not defaulted so no call site silently falls back to the legacy field). [repoPath]
 * resolves the raw on-disk repo-relative path to stage in git; [objectMaintenance]/[repoWriteMonitor]
 * are the OBJECT-only collaborators the module lambda passes (both null on LOCAL, so LOCAL stays
 * byte-identical to pre-C5 behavior). The git-home is NOT created here - the provider creates it lazily
 * at the first commit.
 */
internal fun selectHistoryProvider(
    config: PlainbaseConfig,
    contentRoot: Path,
    repoPath: (TreePath) -> String = { it.value },
    objectMaintenance: (() -> Unit)? = null,
    repoWriteMonitor: Any? = null,
): HistoryProvider {
    if (config.storage.backend == StorageBackend.OBJECT) {
        if (config.git.enabled != true) return NoOpHistoryProvider
        // BOUND decision 1 (C5): a real git-over-the-mirror provider, rooted at DATA_DIR/mirror - the
        // object-mode counterpart to the LOCAL arm below, sharing the SAME GitCliHistoryProvider adapter.
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
            // BLOCKING-1: the pre-lock gate must never hard-fail on a missing/incomplete mirror `.git` —
            // that's GitBundleDr.restore()'s job, inside the data-dir lock.
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
            // Auto-maintenance off the write-pipeline monitor: a daemon thread so it never blocks the save's
            // return, running the shared helper so the `gc --auto` fallback is live on git < 2.30 too.
            maintenance = { Thread { runCatching { runAutoMaintenance(exec) } }.apply { isDaemon = true }.start() },
        )
    } else {
        NoOpHistoryProvider
    }
}

/**
 * Whether to run the Git provider: the explicit [PlainbaseConfig.GitConfig.enabled] override wins either
 * direction; `null` auto-detects a repo in [contentRoot] (main's content root). Detection must catch
 * `.git`-as-a-FILE (linked worktree / submodule, P1-2): `Files.exists` (dir OR file) then a hermetic
 * `rev-parse --is-inside-work-tree` confirmation - `Files.isDirectory` alone would miss a worktree and
 * silently pick NoOp.
 *
 * Crucially, the presence of `.git` means Git mode is INTENDED, so ANY failure to confirm it is NOT a
 * reason to drop history (P1, refining P2-2): a missing binary (exitCode -1), `fatal: detected dubious
 * ownership` (exit 128, common under Docker/uid-mismatch), a permission error - all leave Git mode ON so
 * the startup gate produces the actionable "install git / set PLAINBASE_GIT_ENABLED=false" error instead
 * of silently recording NO history in a real repo. ONLY a DEFINITIVE run (git ran successfully and
 * explicitly reported "false" - a bare repo or inside `.git`) drops to NoOp.
 */
internal fun gitEnabled(config: PlainbaseConfig, contentRoot: Path, exec: GitExecutor): Boolean {
    config.git.enabled?.let { return it }
    if (!Files.exists(contentRoot.resolve(".git"))) return false
    val insideWorkTree = exec.run(listOf("rev-parse", "--is-inside-work-tree"))
    if (insideWorkTree.ok && insideWorkTree.stdoutText.trim() == "false") return false // definitively not a work tree
    return true // any failure (missing/dubious-ownership/permission) → keep Git on; the startup gate fails loud
}
