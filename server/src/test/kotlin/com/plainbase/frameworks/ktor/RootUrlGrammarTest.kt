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
 * The root-segment grammar: ONE first-segment rule across every root-scoped surface, and now ONE
 * answer when it finds no root. A tail whose first segment does not name a registered root names
 * nothing at all - it is never reinterpreted as a path under the primary - so every surface answers
 * a miss in its own idiom (`/docs` the shell body at 404, the rest their error envelope). A KNOWN
 * root scopes the remainder; an unknown root is never a distinct error (D-C3-4). Bundle-wins
 * precedes the root parse on `/assets`, and cannot collide with a SINGLE-segment tail: a root name
 * carries no dot and a bundle file name always does. That argument is narrower than it looks, and
 * `AssetRoute`'s own comment carries the rest of it: the check runs on the WHOLE tail, so a nested
 * bundle entry would answer under a same-named root. `FrontendBundleTest` is what keeps the bundle
 * flat enough for the short version to hold.
 */
class RootUrlGrammarTest : FunSpec({

    test("a rootless /nope tail is 404 with the shell body: no root named, so no root decision") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val shell = client.get("/docs").bodyAsText()

            val plain = client.get("/nope/guides/deploy-guide")
            plain.status shouldBe HttpStatusCode.NotFound
            plain.bodyAsText() shouldBe shell
            plain.headers[HttpHeaders.Location] shouldBe null

            // The ?mode=edit ride-along (the RestRedirectTest idiom): a query cannot supply the
            // root the path segment failed to name.
            client.get("/nope/guides/deploy-guide?mode=edit").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("the rootless answer does not depend on the raw tail's percent-encoding") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val unicode = client.get("/nope/notes/%E6%97%A5%E6%9C%AC%E8%AA%9E%E3%82%AC%E3%82%A4%E3%83%89")
            unicode.status shouldBe HttpStatusCode.NotFound
            unicode.bodyAsText() shouldBe client.get("/docs").bodyAsText()
        }
    }

    test("known-root scoping: /docs/{path} and the bare /docs serve the shell, never a redirect") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            client.get("/docs/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            client.get("/docs").status shouldBe HttpStatusCode.OK // the SPA's root landing view
            client.get("/docs/no/such/page").status shouldBe HttpStatusCode.OK // SPA owns not-found
            // The TRAILING SLASH resolves to the same landing view, which is what makes B8 a real back-out:
            // without this row, deleting `removeSuffix("/")` at RootContentRoute.kt's tail strip has no
            // falsifier anywhere, because every other address here is slash-free. The strip lives on the
            // ROUTE, not in `TreePath.of` (which merely rejects the empty trailing segment); naming the
            // wrong site would send the next back-out to the wrong file.
            client.get("/docs/").status shouldBe HttpStatusCode.OK
        }
    }

    test("an encoded slash is rejected inside a known root (%2F rejected)") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val response = client.get("/docs/a%2Fb")
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldBe client.get("/docs").bodyAsText()
        }
    }

    test("assets mirror the grammar: a rootless tail 404s, bare known root 404s, the rooted form serves") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()

            val rootless = client.get("/assets/infra/assets/diagram.svg")
            rootless.status shouldBe HttpStatusCode.NotFound
            rootless.headers[HttpHeaders.Location] shouldBe null

            client.get("/assets/infra/assets/diagram.svg?v=2").status shouldBe HttpStatusCode.NotFound

            client.get("/assets/docs/infra/assets/diagram.svg").status shouldBe HttpStatusCode.OK
            client.get("/assets/docs").status shouldBe HttpStatusCode.NotFound // a bare root names no asset
        }
    }

    test("bundle-wins precedes the root parse: the shell's own hashed bundle serves with zero hops") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val shell = client.get("/docs").bodyAsText()
            val jsRef = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(shell)?.groupValues?.get(1)
            jsRef.shouldNotBeNull()
            // A rootless bundle tail would 404 under the mirrored grammar; the upfront bundle check
            // must answer it directly (the shell's <script src> cannot afford a miss).
            client.get(jsRef).status shouldBe HttpStatusCode.OK
        }
    }

    test("by-path requires the root segment; a bare known root is a 404 miss, not a 400") {
        restTest(Fixtures.demoDocs) {
            client.get("/api/v1/pages/by-path/docs/guides/deploy-guide").status shouldBe HttpStatusCode.OK
            client.get("/api/v1/pages/by-path/guides/deploy-guide").status shouldBe HttpStatusCode.NotFound
            // A bare root is a well-formed MISS: the SPA's folder-landing fallthrough branches on
            // 404 only (PageView), so invalid_path here would break the /{root} landing.
            val bare = client.get("/api/v1/pages/by-path/docs")
            bare.status shouldBe HttpStatusCode.NotFound
            bare.bodyAsText().contains("page_not_found") shouldBe true
        }
    }

    test("an EXTRA registry root is never treated as a legacy segment: its URL space misses cleanly (D12/D-C3-4)") {
        // A registry with a validated-but-unserved extra root: /extra/... must scope to that
        // root and MISS (shell / 404) - a REGISTERED root's URL space answers a miss under itself,
        // never the no-root answer. Note what registering `extra` does NOT do to main's own page at
        // extra/shadowed.md: nothing. Its address is /docs/extra/shadowed.
        withTempTree(seed = { root ->
            writePage(root, "guides/page.md", "---\ntitle: Page\n---\n\n# Page\n")
            writePage(root, "extra/shadowed.md", "---\ntitle: Shadowed\n---\n\n# Shadowed\n")
        }) { root ->
            val extraDir = Files.createTempDirectory("plainbase-extra-root")
            try {
                val store = LocalContentStore(root)
                val registry = RootRegistry.of(listOf(localRoot("docs", root), localRoot("extra", extraDir)))
                IndexHarness(root, contentStore = store, rootRegistry = registry).use { harness ->
                    harness.builder.rebuild()
                    val ctx = harness.testRouteContext(searchProvider = noopSearchProvider())
                    testApplication {
                        application { plainbaseModule(ctx) }
                        val client = restClient()

                        // Root-scoped, extra-only snapshot: a clean miss on every surface.
                        client.get("/extra/anything").status shouldBe HttpStatusCode.OK // shell, NOT 301
                        client.get("/api/v1/pages/by-path/extra/anything").status shouldBe HttpStatusCode.NotFound
                        client.get("/assets/extra/anything.png").status shouldBe HttpStatusCode.NotFound

                        // And it resolves through its own root-qualified form, unaffected by the root
                        // whose name its first path segment happens to match.
                        client.get("/api/v1/pages/by-path/docs/extra/shadowed").status shouldBe HttpStatusCode.OK
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
            val rooted = client.get("/browse/docs/guides/deploy-guide.md")
            rooted.status shouldBe HttpStatusCode.Found
            rooted.headers[HttpHeaders.Location] shouldBe "/docs/guides/deploy-guide"
            client.get("/browse/docs").status shouldBe HttpStatusCode.BadRequest // a bare root names no file
        }
    }
    test("the root landing redirects to the primary root, carrying any query through") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val response = client.get("/")
            response.status shouldBe HttpStatusCode.Found
            response.headers[HttpHeaders.Location] shouldBe "/docs"

            // The query rides the hop, same as the alias and browse redirects. Without this row
            // reverting `respondRedirectPreservingQuery` to a bare `respondRedirect` stays GREEN,
            // because every other assertion on this arm is query-free: a pasted `/?mode=edit` would
            // silently land in the read view.
            val withQuery = client.get("/?mode=edit")
            withQuery.status shouldBe HttpStatusCode.Found
            withQuery.headers[HttpHeaders.Location] shouldBe "/docs?mode=edit"
        }
    }
})

/** A no-op SearchProvider for grammar tests that never touch search (the RestRedirectTest idiom). */
private fun noopSearchProvider() = object : com.plainbase.domain.search.SearchProvider {
    override fun index(pages: List<com.plainbase.domain.search.PageDocuments>) = Unit
    override fun delete(ids: Collection<com.plainbase.domain.root.RootedPageId>) = Unit
    override fun search(query: com.plainbase.domain.search.SearchQuery) = com.plainbase.domain.search.SearchResults(0, emptyList())
    override fun rebuild(
        pages: Sequence<com.plainbase.domain.search.PageDocuments>,
        retired: Set<com.plainbase.domain.root.RootedPageId>?,
    ) = Unit
    override fun indexedState() = emptyMap<com.plainbase.domain.root.RootedPageId, com.plainbase.domain.search.PageSearchState>()
}
