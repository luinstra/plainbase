package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentStore
import com.plainbase.frameworks.config.PlainbaseConfig
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
    single<ContentStore> {
        val config = get<PlainbaseConfig>()
        // DATA_DIR is excluded from the scan AND the watch (§B1): nested inside CONTENT_DIR, the
        // app's own search.db/plainbase.db would otherwise be indexed (and served as /assets/...)
        // and its writes would re-trigger every rebuild.
        LocalContentStore(root = config.contentDir, ignoreRules = get(), exclusions = listOf(config.dataDir))
    }
}
