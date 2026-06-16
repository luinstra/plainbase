package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import java.nio.file.Files
import java.nio.file.Path

/**
 * The full production wiring of the REST read path over a runtime temp tree, for the native-smoke
 * tests: real [LocalContentStore], real renderer, real in-memory SQLite repos, and (since S4) the
 * real search stack — a file-backed [SearchDb] synced through the same publication listener
 * `searchModule` registers — `restModule`'s graph minus Koin. kotlin.test-compatible (no
 * Kotest/MockK; this source set feeds the native test image).
 */
fun withRestServices(pages: Map<String, String> = emptyMap(), block: (RestServices) -> Unit) {
    val content = Files.createTempDirectory("pb-native-rest")
    val data = Files.createTempDirectory("pb-native-rest-data")
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
            SearchDb(data.resolve("search.db")).use { searchDb ->
                val searchProvider = Fts5SearchProvider(searchDb)
                val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())
                val builder = IndexBuilder(
                    contentStore = store,
                    frontmatterParser = FrontmatterReader(),
                    rendererFactory = { view -> FlexmarkRenderer(view) },
                    identity = PageIdentityService(UuidV7IdProvider()),
                    patcher = FrontmatterPatcher(),
                    idMap = SqlDelightIdMapRepository(database),
                    aliasRegistry = registry,
                    checkpoint = SqlDelightPageCheckpointRepository(database),
                    citations = CitationFactory(),
                    listeners = listOf(IndexBuilder.PublicationListener(searchIndexer::sync)),
                    searchIndexer = searchIndexer,
                )
                builder.rebuild()
                val writeCitations = CitationFactory()
                val services = RestServices(
                    indexBuilder = builder,
                    pageService = PageService(builder, registry, CitationFactory()),
                    searchService = SearchService(provider = searchProvider, indexBuilder = builder),
                    aliasRegistry = registry,
                    contentStore = store,
                    writePipeline = WritePipeline(
                        contentStore = store,
                        indexBuilder = builder,
                        citations = writeCitations,
                        frontmatterParser = FrontmatterReader(),
                        dirtyPages = SqlDelightDirtyPageRepository(database),
                    ),
                    citations = CitationFactory(),
                    maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
                )
                block(services)
            }
        }
    } finally {
        deleteRecursively(content)
        deleteRecursively(data)
    }
}

private fun deleteRecursively(root: Path) {
    Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
