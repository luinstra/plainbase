package com.plainbase.frameworks.koin

import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.service.IndexBuilder
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Wires the §B3 checkpoint replace as an [IndexBuilder.PublicationListener] (collected by
 * `indexModule`'s `getAll()`, like `searchModule`'s sync listener): every published snapshot
 * persists its `id → url_path` map so the NEXT startup's first rebuild can record aliases for
 * moves made while the server was down. Its own module — qualified, separately loadable — so each
 * listener stays an explicit opt-in of the composition (`serve` loads all of them).
 */
val checkpointModule = module {
    single<IndexBuilder.PublicationListener>(named("checkpointReplace")) {
        val checkpoint = get<PageCheckpointRepository>()
        IndexBuilder.PublicationListener(checkpoint::replaceFrom)
    }
}
