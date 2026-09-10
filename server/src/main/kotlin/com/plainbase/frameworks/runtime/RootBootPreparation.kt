package com.plainbase.frameworks.runtime

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
import com.plainbase.frameworks.git.GitRepoLocks
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
)

/** The server's pre-lock LOCAL constructor path; the counter intentionally counts the primary only. */
internal val contentDirStoreConstructions = AtomicInteger()

internal fun prepareRootBootInputs(
    config: PlainbaseConfig,
    openLocal: (LocalStoreInputs) -> LocalContentStore,
): RootBootInputs {
    val registry = RootRegistry.of(config.roots.list)
    val ignoreRules = IgnoreRules()
    val availability = RootAvailability(Clock.System)
    val signals = BootRootSignals()
    val localStores = buildMap {
        val primary = registry.primary
        if (config.storage.backend != StorageBackend.OBJECT) {
            primary.localPath?.let { localPath ->
                contentDirStoreConstructions.incrementAndGet()
                put(primary.name, openPreparedLocal(primary, localPath, config, ignoreRules, availability, signals, openLocal))
            }
        }
        registry.extras.forEach { root ->
            root.localPath?.let { localPath ->
                put(root.name, openPreparedLocal(root, localPath, config, ignoreRules, availability, signals, openLocal))
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
    return RootBootInputs(registry, ignoreRules, availability, localStores, signals, history, probes)
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
    openLocal: (LocalStoreInputs) -> LocalContentStore,
): LocalContentStore = openLocal(
    LocalStoreInputs(
        root = localPath,
        ignoreRules = ignoreRules,
        exclusions = listOf(config.dataDir),
        rootName = root.name,
        onRootUnavailable = { availability.markUnavailable(root.name, UnavailableCause.VANISHED) },
        onIdentityRebind = { signals.broke(root.name, BreakCause.IDENTITY_REBIND) },
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
