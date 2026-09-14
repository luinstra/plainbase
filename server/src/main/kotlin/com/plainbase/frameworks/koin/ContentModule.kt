package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.NoRetirements
import com.plainbase.domain.repository.NoTopology
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.runtime.RootBootInputs
import com.plainbase.frameworks.runtime.RootStoreFactory
import com.plainbase.frameworks.runtime.RootStores
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Wires the content tree adapter. Constructor DSL only - no reflection (native-image gate).
 *
 * `content.ignore` globs are a future config surface (Phase 2+); for now the [IgnoreRules]
 * always-ignore set (`.git`, dotfiles) is sufficient, so the glob list is empty.
 *
 * The LOCAL stores are prepared before the lock and registered here by identity. The OBJECT primary stays a lazy
 * definition so resolving this module never opens its transport or its database.
 */
internal fun createContentModule(
    config: PlainbaseConfig,
    inputs: RootBootInputs,
    openObject: (
        PlainbaseConfig,
        IgnoreRules,
        () -> Set<TreePath>,
        (TreePath) -> Boolean,
        () -> RowsAtStart,
    ) -> ObjectContentStore,
    closeObject: (ObjectContentStore) -> Unit,
    resourceOwner: ServerResourceOwner,
) = module {
    single { inputs.ignoreRules }
    single<RootRegistry> { inputs.registry }
    single { inputs.availability }
    // The availability holder's non-sticky twin: `serve()` records each watcher's coverage into it and `/healthz`
    // reads it. ONE instance for both, which is the whole reason it is a single - two would report a convergence
    // nobody observed.
    single { RootConvergence() }
    // The DERIVED limbo set (C0), republished every pass: durable rows whose pages the pass did not witness and
    // no proof covers. Never stored - a stored flag would be another snapshot from T used at T+n.
    single { RootLimbo() }
    // The observation epochs (C2) - the ONE holder that decides whether a scan may say a page is gone. It reads
    // coverage from the SAME RootConvergence the watchers write and `/healthz` reads, and it revokes through the
    // SAME RetirementRepository the proof-apply transaction re-checks against. Two of either would let an epoch
    // stay open on evidence nobody else believes.
    //
    // `getOrNull()` HONORS THE BOOT-GATE SEAL and is not a shrug: `bootGateFor` builds this module WITHOUT
    // repositoryModule on purpose - `plainbase root` holds `roots.lock`, never the DATA_DIR lock, so it may not open
    // (and migrate) the app database. A graph with no repository therefore has no durable token to mint, which means
    // it has no delete authority to hand out either - and [NoRetirements] is precisely that fact as a value. The gate
    // scans nothing and reaps nothing; degrading here is what keeps it that way, where a `get()` would have made
    // every CLI verb resolve a database it is forbidden to touch.
    single { ObservationEpoch(getOrNull() ?: NoRetirements, get()) }
    // The C3 binding latch - the OTHER half of the absence authority, and the one that asks whether the tree we are
    // looking at is the tree our rows describe. It degrades on the SAME boot-gate seal as the epochs above, and for
    // the same reason: a graph with no app database has no durable latch, so it can promote nothing and grant nothing.
    single { BindingLatch(getOrNull() ?: NoTopology) }
    val primary = inputs.registry.primary
    if (inputs.localStores.containsKey(primary.name)) {
        single<LocalContentStore> { inputs.localStores.getValue(primary.name) }
    }
    single<RootStores> {
        RootStoreFactory.roots(
            registry = inputs.registry,
            primary = get<ContentStore>(),
        ) { root ->
            requireNotNull(inputs.localStores[root.name]) {
                "no prepared LOCAL store for root '${root.name}': the required LOCAL input was omitted"
            }
        }
    }
    fun Scope.buildObject(): ObjectContentStore {
        val dirtyPages = get<DirtyPageRepository>()
        val idMap = get<IdMapRepository>()
        val retirements = get<RetirementRepository>()
        val primary = inputs.registry.primary.name
        return openObject(
            config,
            inputs.ignoreRules,
            // Object mode is always a synthesized main, so every dirty row IS main's; the factory
            // wants bare TreePaths of the main mirror.
            { dirtyPages.all().map { it.path.path }.toSet() },
            // Indexed single-row EXISTS for the poll hot-path guard.
            { dirtyPages.isDirty(RootedPath(RootName.PRIMARY, it)) },
            // C3: the pagination boundary. Read FRESH before each LIST (never captured here), so a page created while
            // a LIST paginates is not in the generation's rows and can never be covered by its proof. The binding_epoch
            // is co-read HERE (revoke-before-stamp, C5), and FIRST: a bind landing between it and the row read advances
            // the epoch past this value, so the OBJECT_LIST proof stamped from this snapshot fails the two-token compare
            // rather than reaping a binding a restore re-created between this poll and the reap.
            {
                val bindingEpoch = retirements.bindingEpoch(primary)
                val rows = idMap.bindings().filter { it.path.root == primary }.mapTo(mutableSetOf()) { BindingRef(it.path.path, it.id) }
                RowsAtStart(rows, bindingEpoch)
            },
        )
    }
    single<ObjectContentStore> {
        val scope = this
        resourceOwner.construct("object store") {
            scope.buildObject().also { store ->
                resourceOwner.own(ServerResourcePhase.OBJECT_TRANSPORT, store, closeObject)
            }
        }
    } onClose {
        resourceOwner.drainServices()
    }
    // Backend selection aliases the selected concrete adapter; the other backend remains unconstructed.
    single<ContentStore> {
        RootStoreFactory.primary(
            backend = config.storage.backend,
            local = {
                requireNotNull(inputs.localStores[primary.name]) {
                    "no prepared LOCAL store for root '${primary.name}': the required LOCAL input was omitted"
                }
                get<LocalContentStore>()
            },
            objectStore = { get<ObjectContentStore>() },
        )
    }
}
