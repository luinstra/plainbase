package com.plainbase.frameworks.koin

import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.RestServices
import org.koin.dsl.module

/** Wires the chunk-6 REST read path (+ the S4 search read path + the W1 write pipeline). Constructor DSL only — no reflection (native-image gate). */
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
        RestServices(
            indexBuilder = get(),
            pageService = get(),
            searchService = get(),
            aliasRegistry = get(),
            contentStore = get(),
            writePipeline = get(),
            citations = get(),
            idProvider = get(),
            maxWriteBodyBytes = get<PlainbaseConfig>().maxWriteBodyBytes,
            maxAssetBytes = get<PlainbaseConfig>().maxAssetBytes,
        )
    }
}
