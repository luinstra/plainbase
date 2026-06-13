package com.plainbase.frameworks.koin

import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.SearchService
import com.plainbase.frameworks.ktor.RestServices
import org.koin.dsl.module

/** Wires the chunk-6 REST read path (+ the S4 search read path). Constructor DSL only — no reflection (native-image gate). */
val restModule = module {
    single { PageService(indexBuilder = get(), aliasRegistry = get(), citations = get()) }
    single { SearchService(provider = get(), indexBuilder = get()) }
    single { RestServices(indexBuilder = get(), pageService = get(), searchService = get(), aliasRegistry = get(), contentStore = get()) }
}
