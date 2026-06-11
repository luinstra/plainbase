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
    single<ContentStore> { LocalContentStore(root = get<PlainbaseConfig>().contentDir, ignoreRules = get()) }
}
