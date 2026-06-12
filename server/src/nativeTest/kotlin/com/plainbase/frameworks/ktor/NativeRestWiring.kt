package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.UuidV7
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import java.nio.file.Files

/**
 * The full production wiring of the REST read path over a runtime temp tree, for the native-smoke
 * tests: real [LocalContentStore], real renderer, real in-memory SQLite repos — `restModule`'s
 * graph minus Koin. kotlin.test-compatible (no Kotest/MockK; this source set feeds the native test
 * image).
 */
fun withRestServices(pages: Map<String, String> = emptyMap(), block: (RestServices) -> Unit) {
    val content = Files.createTempDirectory("pb-native-rest")
    try {
        for ((relativePath, body) in pages) {
            val target = content.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(target, body)
        }
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val database = DatabaseFactory.createDatabase(driver)
            val store = LocalContentStore(content)
            val registry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
            val builder = IndexBuilder(
                contentStore = store,
                frontmatterParser = FrontmatterReader(),
                rendererFactory = { view -> FlexmarkRenderer(view) },
                identity = PageIdentityService(UuidV7()),
                patcher = FrontmatterPatcher(),
                idMap = SqlDelightIdMapRepository(database),
                aliasRegistry = registry,
                citations = CitationFactory(),
            )
            builder.rebuild()
            val services = RestServices(
                indexBuilder = builder,
                pageService = PageService(builder, registry, CitationFactory()),
                aliasRegistry = registry,
                contentStore = store,
            )
            block(services)
        }
    } finally {
        Files.walk(content).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
