package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.UuidV7IdProvider
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * The §6.0 FAIL-CLOSED bare arms, driven on REAL registered tombstones (not the [AmbiguousIdMap] FAKE) for the first
 * time - the shapes that were FAKE-only through C4 now fire on data the flip makes reachable. `resolution()` is
 * fail-closed on the READ: a bare id resolves [IdResolution.None] when NO registered root holds it live (unknown or
 * PURELY-retired), and [IdResolution.Ambiguous] the moment a live root holds it ALONGSIDE a registered tombstone (the
 * MIXED case). This is the OPPOSITE of the root-scoped WRITE: a foreign tombstone never blocks a bind, but it DOES make
 * the read fail closed rather than serve the surviving root blind.
 *
 * The two roots are seated NON-LEXICALLY (`notes` rank 0, `main` rank 1, but `main` < `notes` by name) so a candidate
 * list emitted in raw/lexical order would fail the D7-registry-rank assertions.
 *
 * REGISTERED, not any: a tombstone under a DETACHED root is filtered by `claimants` and must NOT flip a thing
 * (`RootedPermalinkMatrixTest` pins that). Every fixture here uses REGISTERED roots.
 */
class BareArmFailClosedTest : FunSpec({

    val x = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val id = PageId.require(x)
    val zeroHash = "sha256:${"0".repeat(64)}"
    val editBody = "---\nid: $x\ntitle: X\n---\n\n# X\n"

    // R31 (purely-retired: NO live claimant anywhere) --------------------------------------------------------------

    test("R31: a SINGLE registered tombstone (no live) -> bare /p 410, bare REST read 404, bare propose StaleBase") {
        failClosed { harness ->
            registeredTombstone(harness, "docs", "guides/gone.md", id)

            withClue("purely-retired resolves None; the permalink splits it to 410 naming the last-known path") {
                val res = noRedirect.get("/p/$x")
                res.status shouldBe HttpStatusCode.Gone
                res.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                res.bodyText() shouldContain "guides/gone.md"
            }
            withClue("the bare REST read of a purely-retired id is a plain 404 - None returns null, never Ambiguous") {
                val res = client.get("/api/v1/pages/$x")
                res.status shouldBe HttpStatusCode.NotFound
                res.errorCode() shouldBe "page_not_found"
            }
            withClue("a bare propose against a purely-retired id stays StaleBase (the None arm), NOT 409 ambiguous_page_id") {
                val res = proposeEdit(client, x, zeroHash)
                res.status shouldBe HttpStatusCode.BadRequest
                res.errorCode() shouldBe "stale_base"
            }
        }
    }

    test("R31: TWO registered tombstones (no live) -> bare /p 300 disambiguating WHICH tombstone, D7 rank order") {
        failClosed { harness ->
            // notes is rank 0 but sorts AFTER main. Seed main FIRST so raw insertion order [main, notes] differs from
            // BOTH the D7 rank order AND lexical - the candidate list must follow rank, not insertion or name.
            registeredTombstone(harness, "docs", "m/gone.md", id)
            registeredTombstone(harness, "notes", "n/gone.md", id)

            val res = noRedirect.get("/p/$x")
            res.status shouldBe HttpStatusCode.MultipleChoices
            res.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            res.candidateRoots() shouldContainExactly listOf("notes", "docs") // D7 registry rank, NOT lexical
            res.candidateUrls() shouldContainExactly listOf("/p/notes/$x", "/p/docs/$x")
        }
    }

    // R20 + R21/R22/R22b (MIXED: a live claimant ALONGSIDE a foreign registered tombstone) --------------------------

    test("R20/R21/R22/R22b: live in notes + a registered tombstone in main -> the fail-closed MIXED arms") {
        failClosed(seedNotesLive = true) { harness ->
            registeredTombstone(harness, "docs", "m/gone.md", id)

            withClue("R20 - bare /p is 300 (both candidates, D7 rank), STATUS-NEUTRAL mixed message, NO 410 claim, no-store") {
                val res = noRedirect.get("/p/$x")
                res.status shouldBe HttpStatusCode.MultipleChoices
                res.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                res.candidateRoots() shouldContainExactly listOf("notes", "docs")
                val message = res.errorMessage()
                message shouldContain "some candidate roots have retired this id"
                message shouldNotContain "410" // status-neutral: the permalink 300 makes no cross-surface status claim
            }
            withClue("R21 - bare REST id read is 409 ambiguous_page_id, never notes' document bytes") {
                val res = client.get("/api/v1/pages/$x")
                res.status shouldBe HttpStatusCode.Conflict
                res.errorCode() shouldBe "ambiguous_page_id"
                res.candidateRoots() shouldContainExactly listOf("notes", "docs")
            }
            withClue("R22 - a bare WRITE is 409 ambiguous_page_id; the SAME PUT pinned to notes still resolves its root") {
                val bare = client.put("/api/v1/pages/$x") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"$zeroHash\"")
                    setBody(editBody)
                }
                bare.status shouldBe HttpStatusCode.Conflict
                bare.errorCode() shouldBe "ambiguous_page_id"
                // Pinned path untouched: ?root=notes resolves to notes (NOT the ambiguity arm) and CASes there - a
                // plain content-changed conflict against notes' real page, never `ambiguous_page_id`.
                val pinned = client.put("/api/v1/pages/$x?root=notes") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"$zeroHash\"")
                    setBody(editBody)
                }
                pinned.errorCode() shouldBe "conflict"
            }
            withClue("R22b - a bare PROPOSE is 409 ambiguous_page_id naming the BODY field; body-pinned to notes is not") {
                val bare = proposeEdit(client, x, zeroHash)
                bare.status shouldBe HttpStatusCode.Conflict
                bare.errorCode() shouldBe "ambiguous_page_id"
                bare.errorMessage() shouldContain "\"root\" field in your request body"

                val pinned = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"operation":"edit","page_id":"$x","base_hash":"$zeroHash","root":"notes",""" +
                            """"proposed_content":"# X","rationale":"r"}""",
                    )
                }
                // Body-pinned to notes resolves there and is NOT the ambiguity 409 (its own base-mismatch is a 400).
                pinned.status shouldNotBe HttpStatusCode.Conflict
            }
        }
    }
})

/** Two REGISTERED roots seated NON-LEXICALLY: `notes` at rank 0 (sorts LATER), `main` at rank 1 (sorts FIRST). */
private fun failClosed(
    seedNotesLive: Boolean = false,
    block: suspend io.ktor.server.testing.ApplicationTestBuilder.(MultiRootRestHarness) -> Unit,
) {
    val parent = Files.createTempDirectory("pb-fail-closed")
    try {
        val notesDir = Files.createDirectory(parent.resolve("notes-root"))
        val mainDir = Files.createDirectory(parent.resolve("main-root"))
        if (seedNotesLive) {
            val page = notesDir.resolve("live.md")
            Files.writeString(page, "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\ntitle: Live\n---\n\n# Live\n\nbody.\n")
        }
        multiRootTest(listOf(testRoot("notes", notesDir), testRoot("docs", mainDir))) { harness -> block(harness) }
    } finally {
        parent.toFile().deleteRecursively()
    }
}

/** A REGISTERED tombstone for [id] at ([root], [path]), minted by the public displacing double-bind (no raw retire). */
private fun registeredTombstone(harness: MultiRootRestHarness, root: String, path: String, id: PageId) {
    val rn = RootName.require(root)
    require(harness.registry.byName(rn) != null) { "'$root' must be REGISTERED for a registered tombstone" }
    val p = RootedPath(rn, TreePath.require(path))
    harness.idMap.bind(p, id, materialized = false)
    harness.idMap.bind(p, UuidV7IdProvider().next(), materialized = false) // displaces -> retires [id] at (root, path)
    require(harness.idMap.retiredAt(rn, id) != null) { "expected '$id' tombstoned in '$root'" }
}

private val io.ktor.server.testing.ApplicationTestBuilder.noRedirect
    get() = createClient { followRedirects = false }

private suspend fun proposeEdit(client: io.ktor.client.HttpClient, pageId: String, baseHash: String): HttpResponse =
    client.post("/api/v1/changes") {
        contentType(ContentType.Application.Json)
        setBody("""{"operation":"edit","page_id":"$pageId","base_hash":"$baseHash","proposed_content":"# X","rationale":"r"}""")
    }

private suspend fun HttpResponse.bodyText(): String = bodyAsText()

private suspend fun HttpResponse.errorBody() =
    Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject

private suspend fun HttpResponse.errorCode(): String = errorBody().getValue("code").jsonPrimitive.content

private suspend fun HttpResponse.errorMessage(): String = errorBody().getValue("message").jsonPrimitive.content

private suspend fun HttpResponse.candidateRoots(): List<String> =
    errorBody().getValue("candidates").jsonArray.map { it.jsonObject.getValue("root").jsonPrimitive.content }

private suspend fun HttpResponse.candidateUrls(): List<String> =
    errorBody().getValue("candidates").jsonArray.map { it.jsonObject.getValue("url").jsonPrimitive.content }
