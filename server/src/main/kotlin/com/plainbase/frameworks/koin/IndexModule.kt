package com.plainbase.frameworks.koin

import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.UuidV7
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import org.koin.dsl.module

/**
 * Wires the chunk-5 index pass. Constructor DSL only — no reflection (native-image gate).
 *
 * The renderer factory is passed inline rather than registered: a per-rebuild [FlexmarkRenderer]
 * is bound to that rebuild's URL-complete skeleton snapshot, so a `single` would be wrong and a
 * bare function type would collide on Koin's erased `Function1` key.
 */
val indexModule = module {
    single<FrontmatterParser> { FrontmatterReader() }
    single { PageIdentityService(UuidV7()) }
    single { FrontmatterPatcher() }
    single { UrlAliasRegistry(get()) }
    single { CitationFactory() }
    single {
        IndexBuilder(
            contentStore = get(),
            frontmatterParser = get(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = get(),
            patcher = get(),
            idMap = get(),
            aliasRegistry = get(),
            citations = get(),
        )
    }
}
