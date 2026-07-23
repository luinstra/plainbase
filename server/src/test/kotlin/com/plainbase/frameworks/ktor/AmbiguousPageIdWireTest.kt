package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.PageRootResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * One id-addressed surface whose root pin rides `?root=`, for the table above: [path] is the request the row issues AND
 * the endpoint its 409 candidates must name. [pinSeparator] is `&` when [path] already carries a query string, so the
 * expected retry url is the caller's own request with `root` APPENDED rather than a rebuilt one.
 */
private class QueryPinSurface(
    val label: String,
    val path: String,
    val issue: suspend ApplicationTestBuilder.(String) -> HttpResponse,
) {
    val pinSeparator: String get() = if ('?' in path) "&" else "?"
}

/**
 * The full One/Ambiguous/None contract's AMBIGUOUS arm, FAKE-driven through the REAL Ktor routes (C4, 6a). Ambiguity
 * is FAKE-only under `UNIQUE(id)`, so an [AmbiguousIdMap] reporting two live (or two retired) roots for one id is the
 * only way to reach it. REST answers 409 `ambiguous_page_id` + candidate URLs; the permalink answers 300 + one
 * `Link: rel="alternate"` per candidate. RED: without the resolver/route/funnel wiring these do not fire.
 */
class AmbiguousPageIdWireTest : FunSpec({

    val dupId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val id = PageId.require(dupId)
    val main = RootName.MAIN
    val notes = RootName.require("notes")

    fun twoRoots(block: (Path, Path) -> Unit) {
        val parent = Files.createTempDirectory("pb-ambig")
        try {
            block(Files.createDirectory(parent.resolve("main-root")), Files.createDirectory(parent.resolve("notes-root")))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    fun resolverFor(live: List<RootName>, retired: List<RootName>): (com.plainbase.domain.service.IndexHarness) -> PageRootResolver =
        { idx -> PageRootResolver(AmbiguousIdMap(idx.idMap, id, liveRoots = live, retiredRoots = retired), idx.rootRegistry) }

    fun absenceFor(live: List<RootName>): (com.plainbase.domain.service.IndexHarness) -> AbsenceClassifier =
        { idx -> AbsenceClassifier(AmbiguousIdMap(idx.idMap, id, liveRoots = live)) }

    /** Every ambiguity body nests under `error` like the rest of the REST error shapes, so unwrap once here. */
    suspend fun io.ktor.client.statement.HttpResponse.errorBody() =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject

    // ---- the QUERY-PIN surfaces: every id-addressed route that threads `?root=` through its OWN route code ----
    //
    // Each of these resolves the id independently, so each can regress on its own - which is why the contract is pinned
    // per surface rather than on one representative route. The `url` assertion is the load-bearing half: a candidate
    // must name the endpoint the CALLER hit, so a /history 409 hands back a /history url. A constant
    // /api/v1/pages/{id}?root= would satisfy only the first row here and mis-direct every other one.
    val bodyOf = """---
id: $dupId
title: X
---

# X
"""
    val zeroHash = "sha256:${"0".repeat(64)}"

    listOf(
        QueryPinSurface("GET page (json)", "/api/v1/pages/$dupId") { client.get(it) },
        QueryPinSurface("GET page html", "/api/v1/pages/$dupId/html") { client.get(it) },
        QueryPinSurface("GET page metadata", "/api/v1/pages/$dupId/metadata") { client.get(it) },
        QueryPinSurface("GET validate-links", "/api/v1/pages/$dupId/validate-links") { client.get(it) },
        QueryPinSurface("GET history", "/api/v1/pages/$dupId/history") { client.get(it) },
        // The diff row carries a query string of its OWN, so it also pins that the retry url PRESERVES it and appends
        // `root` rather than replacing the whole query.
        // The refs are hex because `commitRef` demands 7-64 hex BEFORE the resolve; they are never dereferenced, since
        // the ambiguity throws first.
        QueryPinSurface("GET diff", "/api/v1/pages/$dupId/diff?from=abc1234&to=def5678") { client.get(it) },
        QueryPinSurface("PUT page (human)", "/api/v1/pages/$dupId") { url ->
            client.put(url) {
                contentType(ContentType.parse("text/markdown"))
                header(HttpHeaders.IfMatch, "\"$zeroHash\"")
                setBody(bodyOf)
            }
        },
        QueryPinSurface("POST asset upload", "/api/v1/pages/$dupId/assets?filename=diagram.png") { url ->
            client.post(url) { setBody(byteArrayOf(1, 2, 3)) }
        },
    ).forEach { surface ->
        test("${surface.label}: an ambiguous id -> 409 ambiguous_page_id naming THIS endpoint's retry urls") {
            twoRoots { mainDir, notesDir ->
                multiRootTest(
                    listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                    resolverFactory = resolverFor(listOf(notes, main), emptyList()),
                    absenceFactory = absenceFor(listOf(notes, main)),
                ) { _ ->
                    val res = surface.issue(this, surface.path)
                    res.status shouldBe HttpStatusCode.Conflict
                    withClue("ambiguity is transient state - it must not be cached anywhere") {
                        res.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    }
                    val body = res.errorBody()
                    body.getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
                    // The id is in the MESSAGE, not a key of its own - the caller sent it, and the candidate urls carry it.
                    body.getValue("message").jsonPrimitive.content shouldContain dupId
                    body.getValue("candidates").jsonArray
                        .map { it.jsonObject.getValue("root").jsonPrimitive.content } shouldContainExactly
                        listOf("main", "notes") // D7 rank order
                    withClue("the retry url must name the endpoint the caller actually hit") {
                        body.getValue("candidates").jsonArray
                            .map { it.jsonObject.getValue("url").jsonPrimitive.content } shouldContainExactly
                            listOf("${surface.path}${surface.pinSeparator}root=main", "${surface.path}${surface.pinSeparator}root=notes")
                    }
                }
            }
        }
    }

    test("the AGENT PUT of an ambiguous id -> 409 - a separate facade arm from the human PUT, so it is pinned separately") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = resolverFor(listOf(notes, main), emptyList()),
                absenceFactory = absenceFor(listOf(notes, main)),
                // `save` branches on Principal.Agent BEFORE directSave: the agent arm has its own Ambiguous handling
                // (audit the bare resource, then throw), so the human row above does not cover this code at all.
                extract = { PrincipalExtraction.Resolved(Principal.Agent("pb-test-token")) },
            ) { _ ->
                val res = client.put("/api/v1/pages/$dupId") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"$zeroHash\"")
                    setBody(bodyOf)
                }
                res.status shouldBe HttpStatusCode.Conflict
                res.errorBody().getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
            }
        }
    }

    test("POST /changes edit of an ambiguous id -> 409 naming the BODY field, with NO retry url") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = resolverFor(listOf(notes, main), emptyList()),
                absenceFactory = absenceFor(listOf(notes, main)),
            ) { _ ->
                val res = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"operation":"edit","page_id":"$dupId","base_hash":"$zeroHash",""" +
                            """"proposed_content":"# X","rationale":"r"}""",
                    )
                }
                res.status shouldBe HttpStatusCode.Conflict
                val body = res.errorBody()
                body.getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
                // This surface pins the root in the BODY, so there is no url to retry and the message says which field
                // to set. A fabricated `?root=` url would send an agent after a query string it never sent.
                withClue("a body-pin surface hands back candidates with a null url") {
                    body.getValue("candidates").jsonArray.forEach { it.jsonObject.getValue("url") shouldBe JsonNull }
                }
                body.getValue("message").jsonPrimitive.content shouldContain "\"root\" field in your request body"
            }
        }
    }

    test("bare permalink /p/{id} of an ambiguous LIVE id -> 300 + one Link per candidate (rank order)") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = resolverFor(listOf(notes, main), emptyList()),
                absenceFactory = absenceFor(listOf(notes, main)),
            ) { _ ->
                val res = createClient { followRedirects = false }.get("/p/$dupId")
                res.status shouldBe HttpStatusCode.MultipleChoices
                res.headers.getAll(HttpHeaders.Link) shouldContainExactly listOf(
                    "</p/main/$dupId>; rel=\"alternate\"",
                    "</p/notes/$dupId>; rel=\"alternate\"",
                )
                withClue("ambiguity is transient, and 300 is heuristically cacheable - no intermediary may keep it") {
                    res.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
                // The 300 body is the SAME wrapped envelope the REST 409 sends, only with permalink candidate urls.
                val body = res.errorBody()
                body.getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
                body.getValue("candidates").jsonArray.map { it.jsonObject.getValue("url").jsonPrimitive.content } shouldContainExactly
                    listOf("/p/main/$dupId", "/p/notes/$dupId")
            }
        }
    }

    test("bare permalink /p/{id} of an ambiguous RETIRED id -> 300 (the tombstone disambiguation)") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                // no live claimant -> resolveRetired sees two -> Ambiguous
                resolverFactory = resolverFor(emptyList(), listOf(notes, main)),
                absenceFactory = absenceFor(emptyList()),
            ) { _ ->
                val res = createClient { followRedirects = false }.get("/p/$dupId")
                withClue("a retired-Ambiguous id disambiguates WHICH tombstone via 300") {
                    res.status shouldBe HttpStatusCode.MultipleChoices
                }
            }
        }
    }
})
