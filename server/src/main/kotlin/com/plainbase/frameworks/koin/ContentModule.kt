package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger

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
    single<LocalContentStore> {
        val config = get<PlainbaseConfig>()
        contentDirStoreConstructions.incrementAndGet() // R9: object boot must never run this lambda
        // DATA_DIR is excluded from the scan AND the watch (§B1): nested inside CONTENT_DIR, the
        // app's own search.db/plainbase.db would otherwise be indexed (and served as /assets/...)
        // and its writes would re-trigger every rebuild.
        LocalContentStore(root = config.contentDir, ignoreRules = get(), exclusions = listOf(config.dataDir))
    }
    single<ObjectContentStore> {
        val config = get<PlainbaseConfig>()
        val dirtyPages = get<DirtyPageRepository>()
        ObjectContentStoreFactory.build(config, ignoreRules = get(), dirtyPaths = { dirtyPages.all().map { it.path }.toSet() })
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
