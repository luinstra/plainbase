package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.PageRootResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The C4 pinned/bare READ-WRITE split, posed with the [AmbiguousIdMap] FAKE (a cross-root MOVE window the real
 * adapter cannot make under `UNIQUE(id)`). The FAKE backs BOTH the resolver AND the classifier (FIX 1), so the 503
 * limbo path fires by construction.
 *
 *  - BARE read -> id_map-first (durable): fresh-fail-closed (503 in the window).
 *  - PINNED read -> coherent-stale (snapshot-first, hot): 200 on a hit, NO durable check.
 *  - PINNED write / propose -> fresh-fail-closed (durable-validate the pin AFTER checkEdit).
 */
class RootPinRestTest : FunSpec({

    val dupId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val id = PageId.require(dupId)
    val notes = RootName.require("notes")
    val main = RootName.MAIN

    fun twoRoots(block: (Path, Path) -> Unit) {
        val parent = Files.createTempDirectory("pb-rootpin")
        try {
            val mainDir = Files.createDirectory(parent.resolve("main-root"))
            val notesDir = Files.createDirectory(parent.resolve("notes-root"))
            block(mainDir, notesDir)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    fun seedWithId(root: Path, rel: String, title: String) {
        val target = root.resolve(rel)
        Files.createDirectories(target.parent)
        Files.writeString(target, "---\nid: $dupId\ntitle: $title\n---\n\n# $title\n\nbody.\n")
    }

    /** The fake that lies about which root holds [dupId] - injected into BOTH resolver and classifier. */
    fun fakeFactoryResolver(liveRoots: List<RootName>): (com.plainbase.domain.service.IndexHarness) -> PageRootResolver =
        { idx -> PageRootResolver(AmbiguousIdMap(idx.idMap, id, liveRoots = liveRoots), idx.rootRegistry) }

    fun fakeFactoryAbsence(liveRoots: List<RootName>): (com.plainbase.domain.service.IndexHarness) -> AbsenceClassifier =
        { idx -> AbsenceClassifier(AmbiguousIdMap(idx.idMap, id, liveRoots = liveRoots)) }

    suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    fun baseHashOf(harness: MultiRootRestHarness, root: String, rel: String): String =
        harness.builder.current.byPath.getValue(RootedPath(RootName.require(root), TreePath.require(rel))).contentHash

    // ---- WINDOW FIXTURE 1: page under `main` in the snapshot + FAKE rootsHoldingId=[notes] (displaced to notes) ----

    test("fixture 1 (a): BARE read is 503 - resolve id_map-first sees notes, the page is under main -> limbo") {
        twoRoots { mainDir, notesDir ->
            seedWithId(mainDir, "guides/a.md", "A")
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = fakeFactoryResolver(listOf(notes)),
                absenceFactory = fakeFactoryAbsence(listOf(notes)),
            ) { _ ->
                val noFollow = createClient { followRedirects = false }
                withClue("bare permalink /p/{id}") { noFollow.get("/p/$dupId").status shouldBe HttpStatusCode.ServiceUnavailable }
                withClue("bare REST /api/v1/pages/{id}") {
                    noFollow.get("/api/v1/pages/$dupId").status shouldBe HttpStatusCode.ServiceUnavailable
                }
            }
        }
    }

    test("fixture 1 (b): PINNED read ?root=main is 200 coherent-stale - snapshot HIT, no durable check") {
        twoRoots { mainDir, notesDir ->
            seedWithId(mainDir, "guides/a.md", "A")
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = fakeFactoryResolver(listOf(notes)),
                absenceFactory = fakeFactoryAbsence(listOf(notes)),
            ) { _ ->
                createClient { followRedirects = false }
                    .get("/api/v1/pages/$dupId?root=main").status shouldBe HttpStatusCode.OK
            }
        }
    }

    test("fixture 1 (c): PINNED write ?root=main (non-owner) is 404 - durable-validate fails, blind One(main) would 200") {
        twoRoots { mainDir, notesDir ->
            seedWithId(mainDir, "guides/a.md", "A")
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = fakeFactoryResolver(listOf(notes)),
                absenceFactory = fakeFactoryAbsence(listOf(notes)),
            ) { harness ->
                val baseHash = baseHashOf(harness, "main", "guides/a.md")
                val res = client.put("/api/v1/pages/$dupId?root=main") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"$baseHash\"")
                    setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n\nedited.\n")
                }
                // 404, NOT 200: bindsLive(main,id) over the fake is false ([notes]) -> None -> the audited 404. A blind
                // One(main) would hit the stale main page and write (200).
                res.status shouldBe HttpStatusCode.NotFound
            }
        }
    }

    test("fixture 1 (c-agent): a COMMIT agent's PINNED write also durable-validates the owner") {
        twoRoots { mainDir, notesDir ->
            seedWithId(mainDir, "guides/a.md", "A")
            var caller: Principal = Principal.Anonymous
            val extract: io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction = {
                PrincipalExtraction.Resolved(caller)
            }
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                globs = listOf(CommitGlob.parse("guides/**")),
                enforced = true,
                extract = extract,
                resolverFactory = fakeFactoryResolver(listOf(notes)),
                absenceFactory = fakeFactoryAbsence(listOf(notes)),
            ) { harness ->
                caller = Principal.Agent(harness.index.apiTokens.mint(label = "ci", mode = AgentMode.COMMIT).id)
                val baseHash = baseHashOf(harness, "main", "guides/a.md")
                val original = Files.readString(mainDir.resolve("guides/a.md"))
                val res = client.put("/api/v1/pages/$dupId?root=main") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"$baseHash\"")
                    setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n\nedited.\n")
                }

                res.status shouldBe HttpStatusCode.NotFound
                Files.readString(mainDir.resolve("guides/a.md")) shouldBe original
            }
        }
    }

    // ---- WINDOW FIXTURE 2: page under `notes` in the snapshot + FAKE rootsHoldingId=[main] (durable owner lag) ----

    test("fixture 2 (d): PINNED write ?root=main (durable owner, snapshot lags) is 503 absence_unverified") {
        twoRoots { mainDir, notesDir ->
            seedWithId(notesDir, "guides/a.md", "A") // the page is under NOTES in the snapshot
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = fakeFactoryResolver(listOf(main)),
                absenceFactory = fakeFactoryAbsence(listOf(main)),
            ) { harness ->
                val baseHash = baseHashOf(harness, "notes", "guides/a.md")
                val res = client.put("/api/v1/pages/$dupId?root=main") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"$baseHash\"")
                    setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n\nedited.\n")
                }
                // bindsLive(main,id) over the fake is true ([main]) -> One(main); pageAt(main,id) is null (page under
                // notes) -> requireVerifiedAbsence(main,id) over the fake throws -> 503. The GLOBAL requireVerifiedAbsence
                // body would see the id under notes in byId and NOT throw -> 404.
                res.status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    }

    // ---- WINDOW FIXTURE 3: the UNBIND RACE - resolve reads a claimant, the re-check finds it unbound + tombstoned ----

    test("fixture 3 (e): a BARE write that LOSES the unbind race is 409 page_deleted, not 404 - both arms agree") {
        twoRoots { mainDir, notesDir ->
            // Nothing seeded under main: the id resolves to main at T1 and is absent from the snapshot, which is the
            // state the race leaves behind. The tombstone is what makes 404 a lie.
            var shared: RacingUnbindIdMap? = null
            fun racing(idx: com.plainbase.domain.service.IndexHarness): RacingUnbindIdMap =
                shared ?: RacingUnbindIdMap(idx.idMap, id, main).also { shared = it }
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = { idx -> PageRootResolver(racing(idx), idx.rootRegistry) },
                absenceFactory = { idx -> AbsenceClassifier(racing(idx)) },
            ) { _ ->
                val res = client.put("/api/v1/pages/$dupId") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"sha256:${"0".repeat(64)}\"")
                    setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n\nedited.\n")
                }
                // resolve() reads [main] -> One(main); pageAt(main,id) is null; requireVerifiedAbsence re-reads and the
                // binding is GONE, so it does not throw (the absence IS verified - the page was really deleted). The
                // tombstone consult then answers the SAME frozen 409 the None arm answers for the same domain fact.
                withClue("a client that loses the race must not be told the page never existed") {
                    res.status shouldBe HttpStatusCode.Conflict
                }
                val error = Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("error").jsonObject
                error.getValue("reason").jsonPrimitive.content shouldBe "page_deleted"
                // current_* are null: there is no content to hand back for a page that is gone.
                listOf("current_content", "current_hash", "current_path").forEach { field ->
                    withClue(field) { error.getValue(field).jsonPrimitive.content shouldBe "null" }
                }
            }
        }
    }

    test("fixture 3 (e-agent): a COMMIT agent's BARE write rechecks a live claimant that became a tombstone") {
        twoRoots { mainDir, notesDir ->
            var shared: RacingUnbindIdMap? = null
            fun racing(idx: com.plainbase.domain.service.IndexHarness): RacingUnbindIdMap =
                shared ?: RacingUnbindIdMap(idx.idMap, id, main).also { shared = it }
            var caller: Principal = Principal.Anonymous
            val extract: io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction = {
                PrincipalExtraction.Resolved(caller)
            }
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                globs = listOf(CommitGlob.parse("**")),
                enforced = true,
                extract = extract,
                resolverFactory = { idx -> PageRootResolver(racing(idx), idx.rootRegistry) },
                absenceFactory = { idx -> AbsenceClassifier(racing(idx)) },
            ) { harness ->
                caller = Principal.Agent(harness.index.apiTokens.mint(label = "ci", mode = AgentMode.COMMIT).id)
                val res = client.put("/api/v1/pages/$dupId") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"sha256:${"0".repeat(64)}\"")
                    setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n\nedited.\n")
                }

                res.status shouldBe HttpStatusCode.Conflict
                val error = Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("error").jsonObject
                error.getValue("reason").jsonPrimitive.content shouldBe "page_deleted"
                listOf("current_content", "current_hash", "current_path").forEach { field ->
                    withClue(field) { error.getValue(field).jsonPrimitive.content shouldBe "null" }
                }
            }
        }
    }

    // ---- WINDOW FIXTURE 4: the ROOTED-permalink unbind race - bindsLive reads live, the recheck finds it gone ----

    test("fixture 4 (f): a ROOTED GET /p/main/X that LOSES the unbind race is 410 Retired, never 404") {
        twoRoots { mainDir, notesDir ->
            // Nothing seeded under main: bindsLive (permalinkAt:319) reads X live, the unbind commits, and the
            // requireVerifiedAbsence recheck (:321) finds it gone; the tombstone read (:326) then answers 410. The
            // SHARED fake advances on rootsHoldingId (the read order permalinkAt uses), unlike the bare RacingUnbindIdMap.
            var shared: RootedUnbindRaceIdMap? = null
            fun racing(idx: com.plainbase.domain.service.IndexHarness): RootedUnbindRaceIdMap =
                shared ?: RootedUnbindRaceIdMap(idx.idMap, id, main).also { shared = it }
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = { idx -> PageRootResolver(racing(idx), idx.rootRegistry) },
                absenceFactory = { idx -> AbsenceClassifier(racing(idx)) },
            ) { _ ->
                // 410 Retired naming the last-known path, NEVER 404. Back-out (collapsing the separate bindsLive gate
                // into a single first-taken claims snapshot) flips this to 404: verified empirically, see the addendum.
                createClient { followRedirects = false }.get("/p/main/$dupId").status shouldBe HttpStatusCode.Gone
            }
        }
    }

    // ---- deferred-registration reads: ?root=ghost/non-owner -> 404 AFTER checkRead; malformed -> 400 ----

    test("read ?root pins: main->200, notes(non-owner)->404, ghost->404, a/b(malformed)->400") {
        twoRoots { mainDir, notesDir ->
            seedWithId(mainDir, "guides/a.md", "A")
            multiRootTest(listOf(testRoot("main", mainDir), testRoot("notes", notesDir))) { _ ->
                val c = createClient { followRedirects = false }
                c.get("/api/v1/pages/$dupId?root=main").status shouldBe HttpStatusCode.OK
                c.get("/api/v1/pages/$dupId?root=notes").status shouldBe HttpStatusCode.NotFound
                c.get("/api/v1/pages/$dupId?root=ghost").status shouldBe HttpStatusCode.NotFound
                c.get("/api/v1/pages/$dupId?root=a/b").status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    test("a REPEATED ?root is 400 invalid_root - the disambiguation surface never silently picks one of two pins") {
        twoRoots { mainDir, notesDir ->
            seedWithId(mainDir, "guides/a.md", "A")
            multiRootTest(listOf(testRoot("main", mainDir), testRoot("notes", notesDir))) { _ ->
                val c = createClient { followRedirects = false }
                // Both values are legal slugs and `main` even OWNS the id, so a first-value read answers 200 - which is
                // exactly the failure: the caller asked for two different roots and we would pick one without saying so.
                val res = c.get("/api/v1/pages/$dupId?root=main&root=notes")
                res.status shouldBe HttpStatusCode.BadRequest
                res.errorCode() shouldBe "invalid_root"
            }
        }
    }
})
