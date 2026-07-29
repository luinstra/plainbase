package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.Role
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.CommitGlob
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.time.Clock

/**
 * The ENFORCED-MODE per-root authorization matrix — and it exists because CI and the smoke both boot `auth.mode =
 * off`, where `PolicyService` short-circuits the role grid entirely. Every denial path this chunk INVENTS is
 * therefore GATE-INVISIBLE to the rest of the suite, and would ship blind without these rows.
 *
 * Two orderings are the subject, and both are decisions rather than accidents:
 *  - **authn precedes topology.** An anonymous caller sees 401 on every one of these surfaces - never a 403 naming
 *    a root's `editable` bit, never a 503 naming its availability, never a 404 revealing whether an id exists. A
 *    prober learns exactly what they learned before this chunk: nothing.
 *  - **the write gate precedes availability.** A non-editable root that is ALSO down answers 403, not 503: a write
 *    that could never be authorized is not a transient outage, and reporting it as one invites a retry loop.
 *
 * `editable` is deliberately NOT gated behind enforcement (it is topology, not authorization), which is why one row
 * here runs auth-OFF: without that, the flag would be unexercised in the loopback-dev default and in CI both.
 */
class RootPolicyAuthzMatrixTest : FunSpec({

    val editorBody = "---\ntitle: Rollback\n---\n\n# Rollback\n\nedited by a human.\n"

    /**
     * `docs` (the PRIMARY, editable) + `open` (an editable extra) + `locked` (a NON-editable extra). One fixture, because the
     * whole point is the matrix: the same request, varied only by principal and target root.
     */
    fun withRoots(
        principal: Principal,
        role: Role? = null,
        agentMode: AgentMode? = null,
        enforced: Boolean = true,
        globs: List<CommitGlob> = emptyList(),
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(MultiRootRestHarness) -> Unit,
    ) {
        val main = Files.createTempDirectory("pb-authz-main")
        val open = Files.createTempDirectory("pb-authz-open")
        val locked = Files.createTempDirectory("pb-authz-locked")
        try {
            seedPage(main, "guides/deploy.md", "Deploy")
            seedPage(open, "notes/rollback.md", "Rollback")
            seedPage(locked, "notes/frozen.md", "Frozen")
            val roots = listOf(
                testRoot("docs", main),
                testRoot("open", open, editable = true),
                testRoot("locked", locked, editable = false),
            )
            MultiRootRestHarness(roots, globs = globs, enforced = enforced).use { harness ->
                harness.boot()
                val resolved: Principal = when {
                    agentMode != null -> Principal.Agent(harness.index.apiTokens.mint(label = "ci", mode = agentMode).id)
                    principal is Principal.Human -> {
                        harness.index.roleRepository.upsert(principal.issuer, principal.externalId, role!!, Clock.System.now())
                        principal
                    }
                    else -> principal
                }
                // Re-seat the context with the resolved principal (the token id is only known after the mint).
                val ctx = harness.index.testRouteContext(
                    searchProvider = harness.searchProvider,
                    enforced = enforced,
                    agentDirectCommitGlobs = globs,
                    extract = fixedPrincipal(resolved),
                )
                io.ktor.server.testing.testApplication {
                    application { plainbaseModule(ctx) }
                    block(harness)
                }
            }
        } finally {
            listOf(main, open, locked).forEach { it.toFile().deleteRecursively() }
        }
    }

    fun page(harness: MultiRootRestHarness, root: String, path: String) =
        harness.builder.current.byPath.getValue(RootedPath(RootName.require(root), TreePath.require(path)))

    suspend fun io.ktor.server.testing.ApplicationTestBuilder.edit(id: PageId, hash: String): HttpResponse =
        client.put("/api/v1/pages/${id.value}") {
            contentType(ContentType.parse("text/markdown"))
            header(HttpHeaders.IfMatch, "\"$hash\"")
            setBody(editorBody)
        }

    suspend fun io.ktor.server.testing.ApplicationTestBuilder.create(root: String, folder: String = "notes"): HttpResponse =
        client.post("/api/v1/pages") {
            contentType(ContentType.Application.Json)
            setBody("""{"root":"$root","folder":"$folder","title":"Fresh"}""")
        }

    suspend fun io.ktor.server.testing.ApplicationTestBuilder.asset(id: PageId): HttpResponse =
        client.post("/api/v1/pages/${id.value}/assets?filename=x.png") { setBody(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }

    /** A PENDING edit proposal pinned to [target]'s root — the row a reviewer walks into (see row 10). */
    fun pendingEdit(id: ProposalId, target: IndexedPage) = ProposalRow(
        id = id,
        operation = ProposalOperation.EDIT,
        pageId = target.id,
        root = target.root,
        baseHash = target.contentHash,
        targetPath = target.path,
        proposedContent = editorBody.toByteArray(),
        rationale = "raised while the root was still writable",
        diffArtifact = "",
        status = ProposalStatus.PENDING,
        authorIssuer = "builtin",
        authorExternalId = "someone",
        authorLabel = "someone",
        approverIssuer = null,
        approverExternalId = null,
        decisionComment = null,
        createdAt = Clock.System.now(),
        decidedAt = null,
        appliedCommit = null,
        statusReason = null,
    )

    val human = Principal.Human("builtin", "someone")

    // ---- 1-4: the two 403s are DIFFERENT answers, and anonymous learns neither -------------------

    test("1. anonymous edit on a NON-editable root is 401 - never root_not_editable (the bit must not leak pre-authn)") {
        withRoots(Principal.Anonymous) { harness ->
            val frozen = page(harness, "locked", "notes/frozen.md")
            val response = edit(frozen.id, frozen.contentHash)
            response.status shouldBe HttpStatusCode.Unauthorized
            withClue("a 403 root_not_editable here would tell an unauthenticated prober how the topology is configured") {
                response.errorCode() shouldBe "unauthorized"
            }
        }
    }

    test("2. EDITOR edit on a NON-editable extra is 403 root_not_editable") {
        withRoots(human, role = Role.EDITOR) { harness ->
            val frozen = page(harness, "locked", "notes/frozen.md")
            val response = edit(frozen.id, frozen.contentHash)
            response.status shouldBe HttpStatusCode.Forbidden
            response.errorCode() shouldBe "root_not_editable"
        }
    }

    test("3. EDITOR edit on an EDITABLE extra is 200 and the bytes land in THAT root's tree") {
        withRoots(human, role = Role.EDITOR) { harness ->
            val rollback = page(harness, "open", "notes/rollback.md")
            edit(rollback.id, rollback.contentHash).status shouldBe HttpStatusCode.OK
            withClue("the wrong-store hazard: a multi-root write must land on the right DISK, not just return 200") {
                harness.store("open").read(TreePath.require("notes/rollback.md"))!!.decodeToString() shouldBe editorBody
            }
        }
    }

    test("4. VIEWER edit on an EDITABLE extra is 403 FORBIDDEN (policy) - the two 403 codes are distinguished") {
        withRoots(human, role = Role.VIEWER) { harness ->
            val rollback = page(harness, "open", "notes/rollback.md")
            val response = edit(rollback.id, rollback.contentHash)
            response.status shouldBe HttpStatusCode.Forbidden
            withClue("a role deny and a topology deny are different facts and an operator needs to tell them apart") {
                response.errorCode() shouldBe "forbidden"
            }
        }
    }

    // ---- 5-6: create + asset carry the same rule -------------------------------------------------

    test("5. EDITOR create: root=<non-editable> is 403 root_not_editable; root=<unknown> is 400 invalid_root") {
        withRoots(human, role = Role.EDITOR) { _ ->
            create("locked").let {
                it.status shouldBe HttpStatusCode.Forbidden
                it.errorCode() shouldBe "root_not_editable"
            }
            create("nope").let {
                it.status shouldBe HttpStatusCode.BadRequest
                withClue("a root that does not EXIST must not answer `root_not_editable` - that would be a lie about a real root") {
                    it.errorCode() shouldBe "invalid_root"
                }
            }
        }
    }

    test("6. asset write on a NON-editable extra is 403 root_not_editable") {
        withRoots(human, role = Role.EDITOR) { harness ->
            val frozen = page(harness, "locked", "notes/frozen.md")
            val response = asset(frozen.id)
            response.status shouldBe HttpStatusCode.Forbidden
            response.errorCode() shouldBe "root_not_editable"
        }
    }

    // ---- 7: the glob block is an AUTHORIZATION boundary, not a naming convention ------------------

    test("7. a COMMIT agent: the PRIMARY's glob does NOT authorize an extra root; the same pattern under the extra's key DOES") {
        // `notes/**` declared for the PRIMARY only. The agent's target is an EXTRA root's `notes/rollback.md` - the same
        // relative path. If the glob's root were ignored, this would direct-commit, and an operator who wrote one
        // pattern for the primary would have silently granted unreviewed agent writes in every root sharing the layout.
        withRoots(
            Principal.Anonymous,
            agentMode = AgentMode.COMMIT,
            globs = listOf(CommitGlob.parse("notes/**", RootName.PRIMARY)),
        ) { harness ->
            val rollback = page(harness, "open", "notes/rollback.md")
            withClue("a glob declared for the primary authorizes NOTHING in another root") {
                edit(rollback.id, rollback.contentHash).status shouldBe HttpStatusCode.Accepted // degraded to a proposal
            }
        }

        withRoots(
            Principal.Anonymous,
            agentMode = AgentMode.COMMIT,
            globs = listOf(CommitGlob.parse("notes/**", RootName.require("open"))),
        ) { harness ->
            val rollback = page(harness, "open", "notes/rollback.md")
            withClue("declared under the extra's OWN key, it grants - which is the only way to grant an extra root") {
                edit(rollback.id, rollback.contentHash).status shouldBe HttpStatusCode.OK
            }
        }
    }

    // ---- 8: propose denies at PROPOSE time, not at apply ------------------------------------------

    test("8. propose-edit against a NON-editable root is 403 root_not_editable AT PROPOSE TIME") {
        withRoots(human, role = Role.EDITOR) { harness ->
            val frozen = page(harness, "locked", "notes/frozen.md")
            val response = client.post("/api/v1/changes") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"operation":"edit","page_id":"${frozen.id.value}","base_hash":"${frozen.contentHash}",""" +
                        """"proposed_content":"# x\n","rationale":"r"}""",
                )
            }
            withClue("letting it through would file a proposal nobody could ever apply - a review queue full of dead rows") {
                response.status shouldBe HttpStatusCode.Forbidden
                response.errorCode() shouldBe "root_not_editable"
            }
        }
    }

    test("10. approve on a root that is BOTH read-only and DOWN is 403 root_not_editable, never a retryable 503") {
        withRoots(human, role = Role.ADMIN) { harness ->
            // A PENDING proposal against a root that is now non-editable. Propose-time denies this (row 8), so the
            // only way to hold one is the way an operator actually gets one: the proposal was raised while the root
            // was writable, and `editable = false` arrived later with a config change and a restart. Seeded straight
            // into the repository because that history is precisely what the API will not let you re-enact.
            val frozen = page(harness, "locked", "notes/frozen.md")
            val id = ProposalId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b61")
            harness.proposals.insert(pendingEdit(id, frozen))
            harness.availability.markUnavailable(RootName.require("locked"), UnavailableCause.VANISHED)

            val response = client.post("/api/v1/changes/${id.value}/approve")

            // TERMINAL beats RETRYABLE. Checking availability first would answer "the disk is down, try again later"
            // to an admin whose proposal is refused just as hard once it comes back - an invitation to retry forever
            // against a wall. The editable bit is topology and is readable with the root offline, so it answers first.
            withClue("a 503 root_unavailable here promises a retry that can never succeed") {
                response.status shouldBe HttpStatusCode.Forbidden
                response.errorCode() shouldBe "root_not_editable"
            }
        }
    }

    // ---- 9-11: anonymous sees NOTHING new on any surface ------------------------------------------

    test("9. anonymous read against an unavailable root is 401, NOT 503 (checkRead precedes availability)") {
        withRoots(Principal.Anonymous) { harness ->
            val rollback = page(harness, "open", "notes/rollback.md")
            harness.availability.markUnavailable(RootName.require("open"), com.plainbase.domain.root.UnavailableCause.VANISHED)

            client.get("/api/v1/pages/${rollback.id.value}").let {
                it.status shouldBe HttpStatusCode.Unauthorized
                withClue("a 503 would tell an unauthenticated prober which of the operator's disks is unmounted") {
                    it.errorCode() shouldBe "unauthorized"
                }
            }
            client.get("/browse/open/notes/rollback.md").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/pages/by-path/open/notes/rollback").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("11. anonymous /{unavailable-root}/... still serves the SHELL, never a 503") {
        withRoots(Principal.Anonymous) { harness ->
            harness.availability.markUnavailable(RootName.require("open"), com.plainbase.domain.root.UnavailableCause.VANISHED)
            withClue("the shell arm is PUBLIC; a 503 here would leak topology, and the deny->null->shell contract must survive") {
                client.get("/open/notes/rollback").status shouldBe HttpStatusCode.OK
            }
        }
    }

    // Row 11 held for the ONE principal whose read `checkRead` denies FIRST - the facade returns null and the
    // route falls through to the shell before availability is ever consulted. For every OTHER principal the gate
    // passed and the facade's `requireAvailable` fired, so a canonical page URL in a down root answered 503
    // application/json: a bookmark or a refresh rendered `{"error":…}` as literal text in the browser, and the
    // SPA's own full-page outage view (which it renders from the tree's `available:false`, needing no 503 at all)
    // was unreachable on a cold load - while the bare `/{root}` landing URL of the SAME root served the shell
    // correctly. The root-content surface `/{root}/...` is the BROWSER surface: its answer is the shell. The honest 503 stays on the API surfaces
    // the SPA and the agents consume, which rows 9 and 13-17 pin.

    test("11b. an AUTHENTICATED reader gets the SHELL on /{unavailable-root}/{path}, never raw 503 JSON") {
        withRoots(human, role = Role.VIEWER) { harness ->
            harness.availability.markUnavailable(RootName.require("open"), UnavailableCause.VANISHED)

            val shell = client.get("/open/notes/rollback")

            shell.status shouldBe HttpStatusCode.OK
            withClue("a browser navigating to a page URL must get HTML - the SPA renders the outage from the tree") {
                shell.headers[HttpHeaders.ContentType] shouldContain "text/html"
            }
            withClue("the API surface the SPA fetches from is where the honest 503 belongs, and it is untouched") {
                client.get("/api/v1/pages/by-path/open/notes/rollback").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    }

    test("11c. the SAME under auth.mode = off (what CI boots) - the shell, at the landing URL and one level deeper alike") {
        withRoots(Principal.Anonymous, enforced = false) { harness ->
            harness.availability.markUnavailable(RootName.require("open"), UnavailableCause.VANISHED)

            withClue("auth-off Anonymous passes checkRead, so this is the arm the enforced-anonymous row cannot reach") {
                client.get("/open/notes/rollback").status shouldBe HttpStatusCode.OK
            }
            withClue("the same root must not show an outage page at one URL and raw JSON one segment deeper") {
                client.get("/open").status shouldBe HttpStatusCode.OK
            }
            client.get("/api/v1/pages/by-path/open/notes/rollback").status shouldBe HttpStatusCode.ServiceUnavailable
        }
    }

    test("12. anonymous edit of an UNKNOWN page id is 401, never 404 - the write surface is not an existence oracle") {
        withRoots(Principal.Anonymous) { _ ->
            val unknown = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5f")
            val response = edit(unknown, "sha256:" + "0".repeat(64))
            withClue("401-for-real-id vs 404-for-bogus-id would let an anonymous prober enumerate the corpus") {
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    }

    // ---- 13-17: the BOOT arm, where only the id_map can tell 503 from 404 -------------------------

    test("13/14/16/17. the boot arm: anonymous 401; VIEWER READ 503; EDITOR EDIT 503 - never 404, never a leak") {
        val main = Files.createTempDirectory("pb-authz-boot-main")
        val gone = Files.createTempDirectory("pb-authz-boot-gone")
        try {
            seedPage(main, "guides/deploy.md", "Deploy")
            val missing = gone.resolve("never-there")
            val orphan = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b60")

            fun boot(principal: Principal, role: Role?, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
                MultiRootRestHarness(listOf(testRoot("docs", main), testRoot("away", missing)), enforced = true).use { harness ->
                    harness.idMapOnly("away", "notes/orphan.md", orphan)
                    harness.boot()
                    if (principal is Principal.Human) {
                        harness.index.roleRepository.upsert(principal.issuer, principal.externalId, role!!, Clock.System.now())
                    }
                    val ctx = harness.index.testRouteContext(
                        searchProvider = harness.searchProvider,
                        enforced = true,
                        extract = fixedPrincipal(principal),
                    )
                    io.ktor.server.testing.testApplication {
                        application { plainbaseModule(ctx) }
                        block()
                    }
                }
            }

            boot(Principal.Anonymous, null) {
                withClue("13/16: the id_map resolution runs AFTER checkRead, so it can leak neither existence nor topology") {
                    client.get("/api/v1/pages/${orphan.value}").status shouldBe HttpStatusCode.Unauthorized
                    edit(orphan, "sha256:" + "0".repeat(64)).status shouldBe HttpStatusCode.Unauthorized
                }
            }

            boot(human, Role.VIEWER) {
                val response = client.get("/api/v1/pages/${orphan.value}")
                withClue("17: the page is in NO section (never scanned), so ONLY the persisted binding can tell 503 from 404") {
                    response.status shouldBe HttpStatusCode.ServiceUnavailable
                    response.errorCode() shouldBe "root_unavailable"
                }
            }

            boot(human, Role.EDITOR) {
                val response = edit(orphan, "sha256:" + "0".repeat(64))
                withClue("14: the D5 promise on the WRITE side - an agent must not be told its page is gone") {
                    response.status shouldBe HttpStatusCode.ServiceUnavailable
                    response.errorCode() shouldBe "root_unavailable"
                }
            }
        } finally {
            listOf(main, gone).forEach { it.toFile().deleteRecursively() }
        }
    }

    // ---- 10/15: the audit row shape ---------------------------------------------------------------

    test("10/15. a denied write audits the ROOTED resource; an UNROOTED target keeps the BARE id (no golden churn)") {
        withRoots(human, role = Role.VIEWER) { harness ->
            val rollback = page(harness, "open", "notes/rollback.md")
            edit(rollback.id, rollback.contentHash).status shouldBe HttpStatusCode.Forbidden

            val unknown = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b61")
            edit(unknown, "sha256:" + "0".repeat(64)).status shouldBe HttpStatusCode.Forbidden

            val rows = harness.audit.recent(50).filter { it.action == "EDIT" }
            withClue("a rooted target audits {root}:{id} - an auditor needs to know WHICH tree the write was bound for") {
                rows.single { it.resource == "open:${rollback.id.value}" }.decision shouldBe "denied"
            }
            withClue("an UNKNOWN id has no registered owner, so its row stays the bare id - byte-identical to pre-C4") {
                rows.single { it.resource == unknown.value }.decision shouldBe "denied"
            }
        }
    }

    // ---- the ordering that could silently invert ---------------------------------------------------

    test("the write GATE precedes availability: a non-editable AND unavailable root answers 403, not 503") {
        withRoots(human, role = Role.EDITOR) { harness ->
            val frozen = page(harness, "locked", "notes/frozen.md")
            harness.availability.markUnavailable(RootName.require("locked"), com.plainbase.domain.root.UnavailableCause.VANISHED)

            val response = edit(frozen.id, frozen.contentHash)
            withClue("a write that could NEVER be authorized is not a transient outage - reporting 503 invites a retry loop") {
                response.status shouldBe HttpStatusCode.Forbidden
                response.errorCode() shouldBe "root_not_editable"
            }
        }
    }

    test("editable is TOPOLOGY, not authorization: it denies even under auth.mode = off") {
        withRoots(Principal.Anonymous, enforced = false) { harness ->
            val frozen = page(harness, "locked", "notes/frozen.md")
            val response = edit(frozen.id, frozen.contentHash)
            withClue(
                "gating this behind `enforced` would leave the flag UNEXERCISED in the loopback-dev default and in CI, " +
                    "which is exactly how a read-only root quietly becomes writable",
            ) {
                response.status shouldBe HttpStatusCode.Forbidden
                response.errorCode() shouldBe "root_not_editable"
            }
        }
    }

    test("... and it is ON THE WIRE, per root: without it the SPA offers Edit/New on a root whose every write 403s") {
        // The 403 above is the AUTHORITY, and it stays the backstop. But a client that can only learn the bit by
        // trying the write has to walk the reader into the editor, take their keystrokes, and fail at save - and
        // `plainbase root add` defaults an extra to `editable = false`, so that is the DEFAULT experience of a
        // CLI-added root, not an exotic one. The tree is where the SPA learns the topology; the flag belongs there,
        // beside `available`, for the same reason.
        withRoots(Principal.Anonymous, enforced = false) {
            val entries = Json.parseToJsonElement(client.get("/api/v1/tree").bodyAsText()).jsonObject.getValue("roots").jsonArray
            val editableByRoot = entries.associate { entry ->
                fun field(name: String) = entry.jsonObject.getValue(name).jsonPrimitive.content
                field("root") to field("editable")
            }
            editableByRoot shouldBe mapOf("docs" to "true", "open" to "true", "locked" to "false")
        }
    }
})

private suspend fun HttpResponse.errorCode(): String =
    Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
