package com.plainbase.frameworks.koin

import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * DI wiring for the S5 checkpoint listener through the FULL `serve` module set — the §B4 seam's
 * "declared but not collected" failure mode: a qualified listener Koin never hands to
 * `indexModule`'s `getAll()` would make every rebuild silently skip the checkpoint replace, and
 * every down-time move alias with it (the checkpoint tests wire the listener by hand, so only this
 * test would catch it). Both listeners must be collected — search sync (S2) and checkpoint replace
 * (S5) — and one rebuild through the production graph must leave the checkpoint persisted.
 * (`SearchModuleWiringTest` keeps its own size-1 assertion over its own, checkpoint-less set.)
 */
class CheckpointModuleWiringTest : FunSpec({

    test("the serve module graph collects both publication listeners; a rebuild persists the checkpoint") {
        withTempTree(seed = { root ->
            writePage(root, "docs/widget.md", "# Widget Catalog\n\nbody\n")
        }) { root ->
            withTempTree(seed = {}) { dataDir ->
                val env = mapOf("CONTENT_DIR" to root.toString(), "DATA_DIR" to dataDir.toString())
                val app = koinApplication {
                    // serve's exact module set, with configModule's env pinned to the temp dirs.
                    modules(
                        module { single { PlainbaseConfig.fromEnv(env) } },
                        contentModule,
                        repositoryModule,
                        securityModule,
                        historyModule,
                        indexModule,
                        checkpointModule,
                        searchModule,
                        restModule,
                    )
                }
                try {
                    app.koin.getAll<IndexBuilder.PublicationListener>() shouldHaveSize 2
                    val snapshot = app.koin.get<IndexBuilder>().rebuild()
                    app.koin.get<PageCheckpointRepository>().load() shouldContainExactly
                        snapshot.pages.associate { it.id to it.urlPath }
                } finally {
                    app.close()
                }
            }
        }
    }
})
