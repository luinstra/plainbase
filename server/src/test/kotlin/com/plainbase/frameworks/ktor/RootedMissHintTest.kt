package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.PageRootResolver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path

/** Rooted tombstones stay 410 while exposing registered live holders as uncached alternate links. */
class RootedMissHintTest : FunSpec({

    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val main = RootName.PRIMARY
    val notes = RootName.require("notes")
    val other = RootName.require("other")
    val gone = TreePath.require("gone.md")

    fun roots(names: List<String>, block: (Map<String, Path>) -> Unit) {
        val parent = Files.createTempDirectory("pb-rooted-miss")
        try {
            block(names.associateWith { Files.createDirectory(parent.resolve("$it-root")) })
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    fun resolverFor(liveRoots: List<RootName>): (com.plainbase.domain.service.IndexHarness) -> PageRootResolver =
        { harness ->
            PageRootResolver(
                RetiredElsewhereIdMap(
                    real = harness.idMap,
                    retiredId = id,
                    retiredRoot = notes,
                    retiredPath = gone,
                    liveRoots = liveRoots,
                ),
                harness.rootRegistry,
            )
        }

    test("a rooted miss returns one alternate link for its live holder") {
        roots(listOf("docs", "notes")) { dirs ->
            multiRootTest(
                listOf(testRoot("docs", dirs.getValue("docs")), testRoot("notes", dirs.getValue("notes"))),
                resolverFactory = resolverFor(listOf(main)),
            ) {
                val response = createClient { followRedirects = false }.get("/p/notes/${id.value}")
                response.status shouldBe HttpStatusCode.Gone
                response.headers.getAll(HttpHeaders.Link) shouldContainExactly
                    listOf("</p/docs/${id.value}>; rel=\"alternate\"")
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = response.bodyAsText()
                body shouldContain "notes"
                body shouldContain "gone.md"
                body shouldNotContain "/p/docs"
            }
        }
    }

    test("a rooted miss returns rank-ordered alternate links for every live holder") {
        roots(listOf("docs", "notes", "other")) { dirs ->
            multiRootTest(
                listOf(
                    testRoot("docs", dirs.getValue("docs")),
                    testRoot("notes", dirs.getValue("notes")),
                    testRoot("other", dirs.getValue("other")),
                ),
                resolverFactory = resolverFor(listOf(main, other)),
            ) {
                val response = createClient { followRedirects = false }.get("/p/notes/${id.value}")
                response.status shouldBe HttpStatusCode.Gone
                response.headers.getAll(HttpHeaders.Link) shouldContainExactly listOf(
                    "</p/docs/${id.value}>; rel=\"alternate\"",
                    "</p/other/${id.value}>; rel=\"alternate\"",
                )
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = response.bodyAsText()
                body shouldContain "notes"
                body shouldNotContain "/p/docs"
                body shouldNotContain "/p/other"
            }
        }
    }

    test("a hintless rooted miss has no Link header and remains uncacheable") {
        roots(listOf("docs", "notes")) { dirs ->
            multiRootTest(
                listOf(testRoot("docs", dirs.getValue("docs")), testRoot("notes", dirs.getValue("notes"))),
                resolverFactory = resolverFor(emptyList()),
            ) {
                val response = createClient { followRedirects = false }.get("/p/notes/${id.value}")
                response.status shouldBe HttpStatusCode.Gone
                response.headers.getAll(HttpHeaders.Link) shouldBe null
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            }
        }
    }
})
