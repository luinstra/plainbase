package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
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
    single<LocalContentStore> {
        val config = get<PlainbaseConfig>()
        contentDirStoreConstructions.incrementAndGet() // R9: object boot must never run this lambda
        val main = get<RootRegistry>().main
        // DATA_DIR is excluded from the scan AND the watch (§B1): nested inside main's content root,
        // the app's own search.db/plainbase.db would otherwise be indexed (and served as /assets/...)
        // and its writes would re-trigger every rebuild.
        LocalContentStore(
            root = requireNotNull(main.localPath),
            ignoreRules = get(),
            exclusions = listOf(config.dataDir),
            rootName = main.name,
            onRootUnavailable = { get<RootAvailability>().markUnavailable(main.name, UnavailableCause.VANISHED) },
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
        RootStores(
            registry.roots.associate { root ->
                root.name to if (root.name == registry.main.name) {
                    get<ContentStore>() // main rides the backend-selected store, object mode included
                } else {
                    // Extras are LOCAL-only in v1 (D10 keeps object mode single-root), and they inherit main's
                    // DATA_DIR exclusion so a legally-nested data dir is never walked as content.
                    LocalContentStore(
                        root = requireNotNull(root.localPath) { "extra root '${root.name}' must be local-backed" },
                        ignoreRules = get(),
                        exclusions = listOf(config.dataDir),
                        rootName = root.name,
                        onRootUnavailable = { availability.markUnavailable(root.name, UnavailableCause.VANISHED) },
                    )
                }
            },
        )
    }
    single<ObjectContentStore> {
        val config = get<PlainbaseConfig>()
        val dirtyPages = get<DirtyPageRepository>()
        ObjectContentStoreFactory.build(
            config,
            ignoreRules = get(),
            // Object mode is always a synthesized main, so every dirty row IS main's; the factory
            // wants bare TreePaths of the main mirror.
            dirtyPaths = { dirtyPages.all().map { it.path.path }.toSet() },
            // MINOR-1: indexed single-row EXISTS for the poll hot-path guard.
            isDirty = { dirtyPages.isDirty(RootedPath(RootName.MAIN, it)) },
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
}
