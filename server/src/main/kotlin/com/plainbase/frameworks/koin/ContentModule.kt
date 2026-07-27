package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.NoRetirements
import com.plainbase.domain.repository.NoTopology
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.ObjectManifestProvider
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/** R9 test hook: counts constructions of the contentDir [LocalContentStore] provider below. */
internal val contentDirStoreConstructions = AtomicInteger()

/**
 * Wires the content tree adapter. Constructor DSL only - no reflection (native-image gate).
 *
 * `content.ignore` globs are a future config surface (Phase 2+); for now the [IgnoreRules]
 * always-ignore set (`.git`, dotfiles) is sufficient, so the glob list is empty.
 *
 * Both concrete providers are declared UNCONDITIONALLY but are LAZY: the lambda runs only when the
 * provider is RESOLVED, and the port alias resolves exactly one of them per `storage.backend` - the
 * other stays a dead provider, never constructed (R9, counter-proven by
 * `LocalBootNoObjectConstructionTest`). In object mode nothing resolves the contentDir store (the
 * `historyModule` `repoPath` lambda is lazy and backend-conditional), so CONTENT_DIR is never touched.
 */
val contentModule = module {
    single { IgnoreRules() }
    single<RootRegistry> { RootRegistry.of(get<PlainbaseConfig>().roots.list) }
    single { RootAvailability(Clock.System) }
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
    single<LocalContentStore> {
        val config = get<PlainbaseConfig>()
        contentDirStoreConstructions.incrementAndGet() // R9: object boot must never run this lambda
        val main = get<RootRegistry>().primary
        // DATA_DIR is excluded from the scan AND the watch (§B1): nested inside main's content root,
        // the app's own search.db/plainbase.db would otherwise be indexed (and served as /assets/...)
        // and its writes would re-trigger every rebuild.
        LocalContentStore(
            root = requireNotNull(main.localPath),
            ignoreRules = get(),
            exclusions = listOf(config.dataDir),
            rootName = main.name,
            onRootUnavailable = { get<RootAvailability>().markUnavailable(main.name, UnavailableCause.VANISHED) },
            // A deploy that swaps the tree at this path REBINDS the probe (the root is healthy, and it keeps serving)
            // - and it is a new universe. Everything the epoch witnessed, it witnessed against the old inodes.
            onIdentityRebind = { get<ObservationEpoch>().broke(main.name, BreakCause.IDENTITY_REBIND) },
        )
    }
    // The per-root content trees. Construction for a configured root is ALWAYS allowed and is INERT for a missing
    // path (the store's init only normalizes paths - it touches no disk); what availability suppresses is OPERATION:
    // a root that is not there is never scanned and never watched. So there is no pre-probing before construction
    // anywhere, which is what keeps the wiring straightforward.
    single {
        val config = get<PlainbaseConfig>()
        val registry = get<RootRegistry>()
        val availability = get<RootAvailability>()
        val ignoreRules = get<IgnoreRules>()
        // Main rides the backend-selected store (object mode included), taken EXPLICITLY - the fold sees ONLY extras,
        // never re-selecting primary by name (the C4 HistoryModule bug shape; `RootWiringArchitectureTest` pins it out).
        // Extras are LOCAL-only in v1 (D10 keeps object mode single-root), and they inherit main's DATA_DIR exclusion
        // so a legally-nested data dir is never walked as content.
            RootStores(
                mapOf(registry.primary.name to get<ContentStore>()) +
                    registry.extras.associate { root ->
                        root.name to LocalContentStore(
                        root = requireNotNull(root.localPath) { "extra root '${root.name}' must be local-backed" },
                        ignoreRules = ignoreRules,
                        exclusions = listOf(config.dataDir),
                        rootName = root.name,
                        onRootUnavailable = { availability.markUnavailable(root.name, UnavailableCause.VANISHED) },
                        // Resolved INSIDE the callback, like `onRootUnavailable` above: it fires on a rebind, not on a
                        // construction, so the boot gate's graph never has to hold an epoch it has no business holding.
                        onIdentityRebind = { get<ObservationEpoch>().broke(root.name, BreakCause.IDENTITY_REBIND) },
                    )
                },
        )
    }
    single<ObjectContentStore> {
        val config = get<PlainbaseConfig>()
        val dirtyPages = get<DirtyPageRepository>()
        val idMap = get<IdMapRepository>()
        val retirements = get<RetirementRepository>()
        val main = get<RootRegistry>().primary.name
        ObjectContentStoreFactory.build(
            config,
            ignoreRules = get(),
            // Object mode is always a synthesized main, so every dirty row IS main's; the factory
            // wants bare TreePaths of the main mirror.
            dirtyPaths = { dirtyPages.all().map { it.path.path }.toSet() },
            // MINOR-1: indexed single-row EXISTS for the poll hot-path guard.
            isDirty = { dirtyPages.isDirty(RootedPath(RootName.PRIMARY, it)) },
            // C3: the pagination boundary. Read FRESH before each LIST (never captured here), so a page created while
            // a LIST paginates is not in the generation's rows and can never be covered by its proof. The binding_epoch
            // is co-read HERE (revoke-before-stamp, C5), and FIRST: a bind landing between it and the row read advances
            // the epoch past this value, so the OBJECT_LIST proof stamped from this snapshot fails the two-token compare
            // rather than reaping a binding a restore re-created between this poll and the reap.
            rowsAtStart = {
                val bindingEpoch = retirements.bindingEpoch(main)
                val rows = idMap.bindings().filter { it.path.root == main }.mapTo(mutableSetOf()) { BindingRef(it.path.path, it.id) }
                RowsAtStart(rows, bindingEpoch)
            },
        )
    }
    // Backend selection (Q9): the port ALIASES the selected backend's concrete adapter (one instance,
    // two keys). Consumers depend on the backend-neutral ContentStore; the git-history wiring binds to
    // the concrete local store's resolveRepoRelativePath surface lazily (historyModule).
    single<ContentStore> {
        when (get<PlainbaseConfig>().storage.backend) {
            StorageBackend.LOCAL -> get<LocalContentStore>()
            StorageBackend.OBJECT -> get<ObjectContentStore>()
        }
    }
}

/**
 * The per-root [ContentStore] map, built from the registry - so a name it does not hold is a PROGRAMMING error, not
 * a runtime condition (every root name that arrives from a durable ROW is routed through
 * `PageRootResolver.statusOf` first, which answers DETACHED for exactly those). Declared here, beside the wiring that
 * is its only construction site.
 */
class RootStores(private val byRoot: Map<RootName, ContentStore>) {

    /**
     * [root]'s tree. Fails LOUD and NAMED rather than with a bare `NoSuchElementException`: a per-root lookup that
     * runs on an unregistered root means a guard was missed somewhere upstream, and the message should say which
     * invariant broke - not leave an operator with a mystery 500.
     */
    operator fun get(root: RootName): ContentStore = requireNotNull(byRoot[root]) {
        "no store for root '$root': a per-root lookup ran on an unregistered root - resolve PageRootResolver.statusOf first"
    }

    /**
     * [root]'s tree as the CONCRETE local adapter, or null when it is not one (an object-backed main). The git
     * wiring needs it for `resolveRepoRelativePath`: staging git paths is a local-filesystem concern the
     * backend-neutral port deliberately does not carry, and a git path re-derived from the NFC `TreePath` would be a
     * phantom that does not match the real file on a normalization-preserving filesystem.
     */
    fun localOrNull(root: RootName): LocalContentStore? = byRoot[root] as? LocalContentStore

    /**
     * [root]'s bucket listings, or null when it is not object-backed (C3). The rebuild's OBJECT_LIST proof source:
     * a local root has no bucket to list, and its absence authority is an observation epoch instead - so "no manifest
     * provider" is the honest shape of "this root does not answer that question", not a missing wire.
     */
    fun manifestsOrNull(root: RootName): ObjectManifestProvider? = byRoot[root] as? ObjectManifestProvider
}
