package com.plainbase.frameworks.ktor

import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.localRoot
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files

class SearchRootScopeTest : FunSpec({
    test("root scope reaches the real engine before pagination and authorization precedes root disclosure") {
        val dir = Files.createTempDirectory("plainbase-search-scope")
        try {
            val docs = Files.createDirectory(dir.resolve("docs"))
            val extra = Files.createDirectory(dir.resolve("extra"))
            for (n in 1..24) writePage(docs, "page-$n.md", "# Needle\n\nNeedle needle needle.\n")
            writePage(extra, "target.md", "# Target\n\nNeedle.\n")
            val registry = RootRegistry.of(listOf(localRoot("docs", docs), localRoot("extra", extra)))
            IndexHarness(
                docs,
                rootRegistry = registry,
                sources = registry.roots.map { root ->
                    IndexBuilder.Source(root, LocalContentStore(if (root.name == RootName.PRIMARY) docs else extra), NoOpHistoryProvider)
                },
            ).use { harness ->
                harness.builder.rebuild()
                SearchDb(dir.resolve("search.db")).use { db ->
                    val provider = Fts5SearchProvider(db)
                    provider.rebuild(harness.builder.current.pages.asSequence().map(SectionSplitter()::split))
                    val context = harness.testRouteContext(searchProvider = provider)
                    testApplication {
                        application { plainbaseModule(context) }
                        val global = Json.parseToJsonElement(client.get("/api/v1/search?q=needle&limit=20").bodyAsText()).jsonObject
                        (global.getValue("total").jsonPrimitive.long > 20) shouldBe true
                        val scoped = client.get("/api/v1/search?q=needle&root=extra&limit=1")
                        scoped.status shouldBe HttpStatusCode.OK
                        val result = Json.parseToJsonElement(scoped.bodyAsText()).jsonObject
                        result.getValue("total").jsonPrimitive.long shouldBe 1L
                        result.getValue("hits").jsonArray.single().jsonObject.getValue("root").jsonPrimitive.content shouldBe "extra"
                        val pastResponse = client.get("/api/v1/search?q=needle&root=extra&limit=1&offset=1")
                        val past = Json.parseToJsonElement(pastResponse.bodyAsText()).jsonObject
                        past.getValue("total").jsonPrimitive.long shouldBe 1L
                        past.getValue("hits").jsonArray.size shouldBe 0
                        harness.availability.markUnavailable(RootName.require("extra"), UnavailableCause.VANISHED)
                        client.get("/api/v1/search?q=needle&root=extra").status shouldBe HttpStatusCode.ServiceUnavailable
                    }
                    val protectedContext = harness.testRouteContext(searchProvider = provider, enforced = true)
                    testApplication {
                        application { plainbaseModule(protectedContext) }
                        client.get("/api/v1/search?q=needle&root=missing-space").status shouldBe HttpStatusCode.Unauthorized
                        client.get("/api/v1/search?q=needle&root=extra").status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
