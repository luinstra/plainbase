package com.plainbase.frameworks.runtime

import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.RootName

/** The per-root history providers, selected before server module registration. */
class HistoryProviders(private val byRoot: Map<RootName, HistoryProvider>) {

    /** Primary provider used by primary-only consumers. */
    val primary: HistoryProvider get() = get(RootName.PRIMARY)

    operator fun get(root: RootName): HistoryProvider = requireNotNull(byRoot[root]) {
        "no history provider for root '$root': a per-root lookup ran on an unregistered root"
    }
}
