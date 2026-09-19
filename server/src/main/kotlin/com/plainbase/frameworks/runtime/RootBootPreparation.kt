package com.plainbase.frameworks.runtime

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.filesystem.contentPolicies
import com.plainbase.frameworks.git.GitRepoLocks
import com.plainbase.frameworks.lifecycle.GitMaintenanceTasks
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

internal interface RootBootProbe {
    fun available(): Boolean

    fun gateCheck()
}

internal data class RootBootInputs(
    val registry: RootRegistry,
    val ignoreRules: IgnoreRules,
    val availability: RootAvailability,
    val localStores: Map<RootName, LocalContentStore>,
    val signals: BootRootSignals,
    val history: RootHistorySelection,
    val probes: Map<RootName, RootBootProbe>,
    val policies: Map<RootName, ContentPathPolicy>,
)

/** The server's pre-lock LOCAL constructor path; the counter intentionally counts the primary only. */
internal val contentDirStoreConstructions = AtomicInteger()

internal fun prepareRootBootInputs(
    config: PlainbaseConfig,
    openLocal: (LocalStoreInputs) -> LocalContentStore,
    maintenanceTasks: GitMaintenanceTasks = GitMaintenanceTasks.inert(),
): RootBootInputs {
    val registry = RootRegistry.of(config.roots.list)
    val ignoreRules = IgnoreRules()
    val availability = RootAvailability(Clock.System)
    val signals = BootRootSignals()
    val policies = contentPolicies(registry, config, ignoreRules)
    val localStores = buildMap {
        val primary = registry.primary
        if (config.storage.backend != StorageBackend.OBJECT) {
            primary.localPath?.let { localPath ->
                contentDirStoreConstructions.incrementAndGet()
                put(
                    primary.name,
                    openPreparedLocal(
                        primary,
                        localPath,
                        config,
                        ignoreRules,
                        availability,
                        signals,
                        policies.getValue(primary.name),
                        openLocal,
                    ),
                )
            }
        }
        registry.extras.forEach { root ->
            root.localPath?.let { localPath ->
                put(
                    root.name,
                    openPreparedLocal(
                        root,
                        localPath,
                        config,
                        ignoreRules,
                        availability,
                        signals,
                        policies.getValue(root.name),
                        openLocal,
                    ),
                )
            }
        }
    }
    requirePreparedLocalStores(config, registry, localStores)
    val objectHistory = DeferredObjectHistory()
    val objectLocks = lazy { GitRepoLocks() }
    val primaryRepoPath = when (config.storage.backend) {
        StorageBackend.LOCAL -> localStores.getValue(registry.primary.name)::resolveRepoRelativePath
        StorageBackend.OBJECT -> objectHistory::repoPath
    }
    val extraRepoPaths = registry.extras.mapNotNull { root ->
        localStores[root.name]?.let { root.name to it::resolveRepoRelativePath }
    }.toMap()
    val history = prepareRootHistorySelection(
        config = config,
        registry = registry,
        primaryRepoPath = primaryRepoPath,
        extraRepoPaths = extraRepoPaths,
        objectHistory = objectHistory,
        objectLocks = objectLocks,
        maintenanceTasks = maintenanceTasks,
    )
    val primary = registry.primary
    val primaryProbe = FunctionalRootBootProbe(
        availableCheck = when (config.storage.backend) {
            StorageBackend.LOCAL -> localStores.getValue(primary.name)::available
            StorageBackend.OBJECT -> ({ true })
        },
        gateCheckAction = history.byRoot.getValue(primary.name)::gateCheck,
    )
    val extraProbes = registry.extras.associate { root ->
        root.name to FunctionalRootBootProbe(
            availableCheck = localStores.getValue(root.name)::available,
            gateCheckAction = history.byRoot.getValue(root.name)::gateCheck,
        )
    }
    val probes = buildMap {
        put(primary.name, primaryProbe)
        putAll(extraProbes)
    }
    return RootBootInputs(registry, ignoreRules, availability, localStores, signals, history, probes, policies)
}

private fun requirePreparedLocalStores(
    config: PlainbaseConfig,
    registry: RootRegistry,
    localStores: Map<RootName, LocalContentStore>,
) {
    val rootsRequiringLocalInput = if (config.storage.backend == StorageBackend.OBJECT) registry.extras else registry.roots
    rootsRequiringLocalInput.forEach { root ->
        require(localStores.containsKey(root.name)) {
            "no prepared LOCAL store for root '${root.name}': the required LOCAL input was omitted"
        }
    }
}

private fun openPreparedLocal(
    root: Root,
    localPath: Path,
    config: PlainbaseConfig,
    ignoreRules: IgnoreRules,
    availability: RootAvailability,
    signals: BootRootSignals,
    policy: ContentPathPolicy,
    openLocal: (LocalStoreInputs) -> LocalContentStore,
): LocalContentStore = openLocal(
    LocalStoreInputs(
        root = localPath,
        ignoreRules = ignoreRules,
        exclusions = listOf(config.dataDir),
        rootName = root.name,
        onRootUnavailable = { availability.markUnavailable(root.name, UnavailableCause.VANISHED) },
        onIdentityRebind = { signals.broke(root.name, BreakCause.IDENTITY_REBIND) },
        policy = policy,
    ),
)

private class FunctionalRootBootProbe(
    private val availableCheck: () -> Boolean,
    private val gateCheckAction: () -> Unit,
) : RootBootProbe {
    override fun available(): Boolean = availableCheck()

    override fun gateCheck() {
        gateCheckAction()
    }
}
