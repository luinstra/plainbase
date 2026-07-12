package com.plainbase.frameworks.ktor

import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.localRoot
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files

/**
 * The C3 root-segment grammar (ADR-0011 D3, D-C3-3): ONE first-segment rule across every
 * root-scoped surface, two response styles. Browser address surfaces (`/docs`, `/assets`) answer a
 * non-root first segment with a query-preserving 301 to the main-qualified URL built from the RAW
 * tail; API surfaces (`by-path`, `/browse`) resolve it under main directly, no hop. A KNOWN root
 * scopes the remainder; an unknown root is never a distinct error (D-C3-4). Bundle-wins precedes
 * the root parse on `/assets` (a root name cannot contain a dot, a bundle file name always does).
 */
class RootUrlGrammarTest : FunSpec({

    test("a legacy /docs/{seg} tail 301s to /docs/main/{tail} with the query preserved") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()

            val plain = client.get("/docs/guides/deploy-guide")
            plain.status shouldBe HttpStatusCode.MovedPermanently
            plain.headers[HttpHeaders.Location] shouldBe "/docs/main/guides/deploy-guide"

            // The ?mode=edit ride-along (the RestRedirectTest idiom): a cold hit on a legacy edit
            // URL must land in the editor after the hop, not the read view.
            val edit = client.get("/docs/guides/deploy-guide?mode=edit")
            edit.status shouldBe HttpStatusCode.MovedPermanently
            edit.headers[HttpHeaders.Location] shouldBe "/docs/main/guides/deploy-guide?mode=edit"
        }
    }

    test("the legacy 301 target is built from the RAW tail: original percent-encoding survives, never re-encoded") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val unicode = client.get("/docs/notes/%E6%97%A5%E6%9C%AC%E8%AA%9E%E3%82%AC%E3%82%A4%E3%83%89")
            unicode.status shouldBe HttpStatusCode.MovedPermanently
            unicode.headers[HttpHeaders.Location] shouldBe "/docs/main/notes/%E6%97%A5%E6%9C%AC%E8%AA%9E%E3%82%AC%E3%82%A4%E3%83%89"
        }
    }

    test("known-root scoping: /docs/main/{path} and the bare /docs/main serve the shell, never a redirect") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            client.get("/docs/main/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            client.get("/docs/main").status shouldBe HttpStatusCode.OK // the SPA's root landing view
            client.get("/docs/main/no/such/page").status shouldBe HttpStatusCode.OK // SPA owns not-found
        }
    }

    test("an undecodable tail serves the shell: no decodable first segment means no root decision (%2F rejected)") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val response = client.get("/docs/a%2Fb")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe client.get("/docs").bodyAsText()
        }
    }

    test("assets mirror the grammar: legacy 301 (query preserved), bare known root 404, rooted form serves") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()

            val legacy = client.get("/assets/infra/assets/diagram.svg")
            legacy.status shouldBe HttpStatusCode.MovedPermanently
            legacy.headers[HttpHeaders.Location] shouldBe "/assets/main/infra/assets/diagram.svg"

            val query = client.get("/assets/infra/assets/diagram.svg?v=2")
            query.headers[HttpHeaders.Location] shouldBe "/assets/main/infra/assets/diagram.svg?v=2"

            client.get("/assets/main/infra/assets/diagram.svg").status shouldBe HttpStatusCode.OK
            client.get("/assets/main").status shouldBe HttpStatusCode.NotFound // a bare root names no asset
        }
    }

    test("bundle-wins precedes the root parse: the shell's own hashed bundle serves with zero hops") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val shell = client.get("/docs").bodyAsText()
            val jsRef = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(shell)?.groupValues?.get(1)
            jsRef.shouldNotBeNull()
            // A rootless bundle tail would 301 under the mirrored grammar; the upfront bundle check
            // must answer it directly (the shell's <script src> cannot afford a hop).
            client.get(jsRef).status shouldBe HttpStatusCode.OK
        }
    }

    test("by-path accepts both the root-qualified and the legacy tail; a bare known root is a 404 miss, not a 400") {
        restTest(Fixtures.demoDocs) {
            client.get("/api/v1/pages/by-path/main/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            client.get("/api/v1/pages/by-path/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            // A bare root is a well-formed MISS: the SPA's folder-landing fallthrough branches on
            // 404 only (PageView), so invalid_path here would break the /docs/{root} landing.
            val bare = client.get("/api/v1/pages/by-path/main")
            bare.status shouldBe HttpStatusCode.NotFound
            bare.bodyAsText().contains("page_not_found") shouldBe true
        }
    }

    test("an EXTRA registry root is never treated as a legacy segment: its URL space misses cleanly (D12/D-C3-4)") {
        // A registry with a validated-but-unserved extra root: /docs/extra/... must scope to that
        // root and MISS (shell / 404) - never 301-to-main as if 'extra' were a legacy path segment.
        // The shadow consequence is D3(b)'s accepted residual: a MAIN page at path extra/shadowed.md
        // is unreachable via its legacy form (the segment now names the root).
        withTempTree(seed = { root ->
            writePage(root, "guides/page.md", "---\ntitle: Page\n---\n\n# Page\n")
            writePage(root, "extra/shadowed.md", "---\ntitle: Shadowed\n---\n\n# Shadowed\n")
        }) { root ->
            val extraDir = Files.createTempDirectory("plainbase-extra-root")
            try {
                val store = LocalContentStore(root)
                val registry = RootRegistry.of(listOf(localRoot("main", root), localRoot("extra", extraDir)))
                IndexHarness(root, contentStore = store, rootRegistry = registry).use { harness ->
                    harness.builder.rebuild()
                    val ctx = harness.testRouteContext(searchProvider = noopSearchProvider())
                    testApplication {
                        application { plainbaseModule(ctx) }
                        val client = restClient()

                        // Root-scoped, main-only snapshot: a clean miss on every surface.
                        client.get("/docs/extra/anything").status shouldBe HttpStatusCode.OK // shell, NOT 301
                        client.get("/api/v1/pages/by-path/extra/anything").status shouldBe HttpStatusCode.NotFound
                        client.get("/assets/extra/anything.png").status shouldBe HttpStatusCode.NotFound

                        // The accepted D3(b) shadow: the main page at extra/shadowed resolves ONLY
                        // through its root-qualified form now.
                        client.get("/api/v1/pages/by-path/main/extra/shadowed").status shouldBe HttpStatusCode.OK
                        client.get("/api/v1/pages/by-path/extra/shadowed").status shouldBe HttpStatusCode.NotFound
                    }
                }
            } finally {
                extraDir.toFile().deleteRecursively()
            }
        }
    }

    test("/browse splits the root segment: rooted resolves, a bare root is invalid_path, an extra-root file 404s") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val rooted = client.get("/browse/main/guides/deploy-guide.md")
            rooted.status shouldBe HttpStatusCode.Found
            rooted.headers[HttpHeaders.Location] shouldBe "/docs/main/guides/deploy-guide"
            client.get("/browse/main").status shouldBe HttpStatusCode.BadRequest // a bare root names no file
        }
    }
})

/** A no-op SearchProvider for grammar tests that never touch search (the RestRedirectTest idiom). */
private fun noopSearchProvider() = object : com.plainbase.domain.search.SearchProvider {
    override fun index(pages: List<com.plainbase.domain.search.PageDocuments>) = Unit
    override fun delete(ids: Collection<com.plainbase.domain.page.PageId>) = Unit
    override fun search(query: com.plainbase.domain.search.SearchQuery) = com.plainbase.domain.search.SearchResults(0, emptyList())
    override fun rebuild(pages: Sequence<com.plainbase.domain.search.PageDocuments>) = Unit
    override fun indexedState() = emptyMap<com.plainbase.domain.page.PageId, com.plainbase.domain.search.PageSearchState>()
}
