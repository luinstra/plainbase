package com.plainbase.frameworks.koin

import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.runtime.RootHistorySelection
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Clock

/** Registers the already-selected history collaborators and Koin-owned write/DR adapters. */
internal fun createHistoryModule(
    config: PlainbaseConfig,
    selection: RootHistorySelection,
    resourceOwner: ServerResourceOwner,
    onDrAcquired: (GitBundleDr) -> Unit = {},
    closeDr: (GitBundleDr) -> Unit = { it.close() },
) = module {
    single { selection.objectLocks.value }
    single<GitBundleDr> {
        val build = {
            GitBundleDr(
                exec = GitExecutor(workTree = config.dataDir.resolve("mirror"), home = config.dataDir.resolve("git-home")),
                objectStore = get<ObjectContentStore>(),
                mirrorRoot = config.dataDir.resolve("mirror"),
                tmpDir = config.dataDir.resolve("tmp"),
                sentinelPath = config.dataDir.resolve("restore-pending"),
                identity = CommitIdentity(config.git.authorName, config.git.authorEmail),
                clock = Clock.System,
                repoPath = selection.objectHistory::repoPath,
                gitHome = config.dataDir.resolve("git-home"),
                locks = selection.objectLocks.value,
            )
        }
        resourceOwner.construct("git bundle DR") {
            build().also { dr ->
                resourceOwner.own(ServerResourcePhase.DISASTER_RECOVERY, dr, closeDr)
                onDrAcquired(dr)
            }
        }
    } onClose {
        resourceOwner.drainServices()
    }
    single<HistoryProvider> {
        get<HistoryProviders>().primary
    }
    single { HistoryProviders(selection.byRoot) }
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

/** The per-root provider map; missing names are programming errors at the lookup boundary. */
class HistoryProviders(private val byRoot: Map<RootName, HistoryProvider>) {

    /** Primary provider used by primary-only consumers. */
    val primary: HistoryProvider get() = get(RootName.PRIMARY)

    operator fun get(root: RootName): HistoryProvider = requireNotNull(byRoot[root]) {
        "no history provider for root '$root': a per-root lookup ran on an unregistered root"
    }
}
