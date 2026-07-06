package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import org.koin.dsl.module

/**
 * Wires the content tree adapter. Constructor DSL only — no reflection (native-image gate).
 *
 * `content.ignore` globs are a future config surface (Phase 2+); for now the [IgnoreRules]
 * always-ignore set (`.git`, dotfiles) is sufficient, so the glob list is empty.
 */
val contentModule = module {
    single { IgnoreRules() }
    single<LocalContentStore> {
        val config = get<PlainbaseConfig>()
        // DATA_DIR is excluded from the scan AND the watch (§B1): nested inside CONTENT_DIR, the
        // app's own search.db/plainbase.db would otherwise be indexed (and served as /assets/...)
        // and its writes would re-trigger every rebuild.
        LocalContentStore(root = config.contentDir, ignoreRules = get(), exclusions = listOf(config.dataDir))
    }
    // Backend selection (Q9): the port ALIASES the selected backend's concrete adapter (one instance,
    // two keys). Consumers depend on the backend-neutral ContentStore; the git-history wiring binds to
    // LocalContentStore's local-only resolveRepoRelativePath surface (historyModule). The object arm's
    // hybrid adapter is not built yet — serve()/the offline CLIs refuse object mode EARLY
    // (PlainbaseConfig.objectBackendUnavailableRefusal), so this error() is a never-reached backstop that
    // shares the SAME message rather than serving the wrong authority.
    single<ContentStore> {
        when (get<PlainbaseConfig>().storage.backend) {
            StorageBackend.LOCAL -> get<LocalContentStore>()
            StorageBackend.OBJECT -> error(PlainbaseConfig.OBJECT_BACKEND_UNAVAILABLE_MESSAGE)
        }
    }
}
