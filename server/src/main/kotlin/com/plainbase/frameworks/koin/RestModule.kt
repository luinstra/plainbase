package com.plainbase.frameworks.koin

import com.plainbase.domain.service.PageService
import com.plainbase.frameworks.ktor.RestServices
import org.koin.dsl.module

/** Wires the chunk-6 REST read path. Constructor DSL only — no reflection (native-image gate). */
val restModule = module {
    single { PageService(indexBuilder = get(), aliasRegistry = get(), citations = get()) }
    single { RestServices(indexBuilder = get(), pageService = get(), aliasRegistry = get(), contentStore = get()) }
}
