package com.plainbase.frameworks.koin

import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.buildRouteContext
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Wires the chunk-6 REST read path (+ the S4 search read path + the W1 write pipeline) and the A3 choke point:
 * the [PolicyService] + the guarded facades + the [RouteContext] the routing layer receives. Constructor DSL
 * only — no reflection (native-image gate).
 */
val restModule = module {
    single { PageService(indexBuilder = get(), aliasRegistry = get(), citations = get()) }
    single { SearchService(provider = get(), indexBuilder = get()) }
    single {
        WritePipeline(
            contentStore = get(),
            indexBuilder = get(),
            citations = get(),
            frontmatterParser = get(),
            dirtyPages = get(),
            idMap = get(),
            aliasRegistry = get(),
            historyHook = get(),
        )
    }
    single {
        // No `Clock` Koin single exists (ApiTokenService inlines Clock.System — SecurityModule.kt:18); inline it
        // here too, the least-surprising choice. `enforced` is auth-mode-derived: OFF (loopback-dev) opens the
        // choke point; builtin/proxy enforce the role×action matrix.
        PolicyService(
            roles = get(),
            apiTokens = get(),
            audit = get(),
            idProvider = get(),
            clock = Clock.System,
            enforced = get<PlainbaseConfig>().auth.mode != AuthMode.OFF,
        )
    }
    single<RouteContext> {
        val config = get<PlainbaseConfig>()
        buildRouteContext(
            policy = get(),
            indexBuilder = get(),
            pageService = get(),
            searchService = get(),
            aliasRegistry = get(),
            contentStore = get(),
            writePipeline = get(),
            history = get(),
            idProvider = get(),
            tokens = get(),
            trustedProxyCidrs = config.auth.trustedProxyCidrs,
            maxWriteBodyBytes = config.maxWriteBodyBytes,
            maxAssetBytes = config.maxAssetBytes,
        )
    }
}
