package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.AuditEntry
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.ApplyOutcome
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.headCommits
import com.plainbase.frameworks.git.openOracle
import com.plainbase.frameworks.git.providerOver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * P5 ENFORCED-MODE agent direct-commit suite (the `ci-runs-auth-off-blind` rule: every authz assertion constructs
 * `PolicyService(enforced = true)` + a real `Principal.Agent`, mirroring [ProposalApplyAuthzRouteTest]'s `withApp`).
 * It proves the SAME PUT call splits two ways by the `agentDirectCommit.globs` gate (200 direct vs 202 degrade), the
 * mode gate precedes the glob gate, audit truthfulness (one row per branch, zero content mutation on a degrade), and
 * — the BLOCKING-1 guard — that the proposal-APPLY path NEVER re-enters the glob decision (the `WriteOrigin` bypass).
 */
class AgentDirectCommitAuthzRouteTest : FunSpec({

    val citations = CitationFactory()
    val markdown = ContentType.parse("text/markdown")
    val inId = "0190aaaa-bbbb-7ccc-8ddd-0000000000d1"
    val outId = "0190aaaa-bbbb-7ccc-8ddd-0000000000d2"
    val inDoc = "---\ntitle: In\n---\n\n# In\n\nbody.\n"
    val outDoc = "---\ntitle: Out\n---\n\n# Out\n\nbody.\n"
    val edited = "---\ntitle: Edit\n---\n\n# Edit\n\nedited body.\n"

    /**
     * Builds a real enforced route context over a temp tree holding an IN-glob page (`docs/in.md`) and an OUT-of-glob
     * page (`notes/out.md`), both with STABLE seeded ids, with [globs] threaded into the route context and [principal]
     * the resolved caller. [seedAgentMode] mints a real agent token; a Human gets [role] upserted.
     */
    fun withApp(
        principal: Principal,
        role: Role? = null,
        seedAgentMode: AgentMode? = null,
        globs: List<CommitGlob> = listOf(CommitGlob.parse("docs/**")),
        enforced: Boolean = true,
        // b1: a test wires a real Git provider to assert the agent direct commit's git author/committer attribution.
        historyFactory: (Path) -> com.plainbase.domain.history.HistoryProvider = { com.plainbase.frameworks.git.NoOpHistoryProvider },
        block: suspend (
            ApplicationTestBuilder,
            IndexHarness,
            com.plainbase.frameworks.filesystem.LocalContentStore,
            RouteContext,
            Path,
        ) -> Unit,
    ) {
        val root = Files.createTempDirectory("plainbase-direct-commit")
        try {
            Files.createDirectories(root.resolve("docs"))
            Files.createDirectories(root.resolve("notes"))
            Files.writeString(root.resolve("docs/in.md"), inDoc)
            Files.writeString(root.resolve("notes/out.md"), outDoc)
            val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)
            val history = historyFactory(root)
            IndexHarness(root, contentStore = store, history = history).use { harness ->
                history.prepare()
                harness.idMap.bind(TreePath.require("docs/in.md"), PageId.require(inId), materialized = false)
                harness.idMap.bind(TreePath.require("notes/out.md"), PageId.require(outId), materialized = false)
                harness.builder.rebuild()
                val resolved: Principal = when {
                    seedAgentMode != null -> Principal.Agent(harness.apiTokens.mint(label = "ci", mode = seedAgentMode).id)
                    principal is Principal.Human -> {
                        harness.roleRepository.upsert(principal.issuer, principal.externalId, role!!, Clock.System.now())
                        principal
                    }
                    else -> principal
                }
                val pipelineHook = com.plainbase.domain.service.WriteHistoryHook { p, b, a, c -> history.commit(p, b, a, c)?.sha }
                val ctx = harness.testRouteContext(
                    contentStore = store,
                    writePipeline = harness.writePipeline(pipelineHook, store),
                    searchProvider = harness.fts(),
                    history = history,
                    enforced = enforced,
                    agentDirectCommitGlobs = globs,
                    extract = fixedPrincipal(resolved),
                )
                testApplication {
                    application { plainbaseModule(ctx) }
                    block(this, harness, store, ctx, root)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    suspend fun ApplicationTestBuilder.hashOf(id: String): String =
        Json.parseToJsonElement(client.get("/api/v1/pages/$id").bodyAsText()).jsonObject.getValue("content_hash").jsonPrimitive.content

    suspend fun ApplicationTestBuilder.putEdit(id: String, baseHash: String, body: String = edited): HttpResponse =
        client.put("/api/v1/pages/$id") {
            header(HttpHeaders.IfMatch, "\"$baseHash\"")
            contentType(markdown)
            setBody(body)
        }

    suspend fun ApplicationTestBuilder.postCreate(folder: String, title: String = "newpage"): HttpResponse =
        client.post("/api/v1/pages") {
            contentType(ContentType.Application.Json)
            setBody("""{"folder":"$folder","title":"$title"}""")
        }

    fun List<AuditEntry>.edits() = filter { it.action == "EDIT" }
    fun List<AuditEntry>.creates() = filter { it.action == "CREATE" }

    // ---- MASTER: the same call splits two ways by the glob gate ---------------------------------------

    test("MASTER: a COMMIT agent's identical PUT is a 200 direct commit IN-glob and a 202 degrade OUT-of-glob") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT) { app, harness, store, _, _ ->
            // IN-glob (docs/**) → 200 Written, the file updates, ONE allowed EDIT@inId row, NO proposal row.
            val inDirect = app.putEdit(inId, app.hashOf(inId))
            withClue(inDirect.bodyAsText()) { inDirect.status shouldBe HttpStatusCode.OK }
            store.read(TreePath.require("docs/in.md"))!!.decodeToString() shouldBe edited
            harness.proposalRepository.all().shouldBeEmpty()
            harness.auditRepository.recent(50).edits().single { it.resource == inId }.decision shouldBe "allowed"

            // The SAME call shape to the OUT-of-glob page → 202 degrade, a proposal row, disk byte-UNCHANGED,
            // an allowed EDIT@"proposal" row.
            val outDegrade = app.putEdit(outId, app.hashOf(outId))
            withClue(outDegrade.bodyAsText()) { outDegrade.status shouldBe HttpStatusCode.Accepted }
            val body = Json.parseToJsonElement(outDegrade.bodyAsText()).jsonObject
            body.getValue("degraded").jsonPrimitive.content shouldBe "true"
            body.getValue("status").jsonPrimitive.content shouldBe "PENDING"
            body.getValue("proposal_id").jsonPrimitive.content // present
            // The degrade ALWAYS carries a non-empty server-rendered diff (ProposalService); a bug nulling/emptying
            // it must fail THIS HTTP-path assertion, not only the golden/native ones.
            body.getValue("unified_diff").jsonPrimitive.content.shouldNotBeEmpty()
            store.read(TreePath.require("notes/out.md"))!!.decodeToString() shouldBe outDoc // UNCHANGED
            harness.proposalRepository.all().shouldHaveSize(1)
            harness.auditRepository.recent(50).edits().single { it.resource == "proposal" }.decision shouldBe "allowed"
        }
    }

    // ---- the mode gate precedes the glob gate ---------------------------------------------------------

    test("mode gate first: a PROPOSE agent on an IN-glob path STILL degrades (the glob is a COMMIT capability)") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.PROPOSE) { app, harness, store, _, _ ->
            val resp = app.putEdit(inId, app.hashOf(inId))
            resp.status shouldBe HttpStatusCode.Accepted
            store.read(TreePath.require("docs/in.md"))!!.decodeToString() shouldBe inDoc // unchanged
            harness.proposalRepository.all().shouldHaveSize(1)
        }
    }

    test("READ_ONLY agent → 403 + a DENIED EDIT@\"proposal\" audit row; no proposal, disk unchanged") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.READ_ONLY) { app, harness, store, _, _ ->
            val resp = app.putEdit(inId, app.hashOf(inId))
            resp.status shouldBe HttpStatusCode.Forbidden
            harness.proposalRepository.all().shouldBeEmpty()
            store.read(TreePath.require("docs/in.md"))!!.decodeToString() shouldBe inDoc
            harness.auditRepository.recent(50).edits().single { it.resource == "proposal" }.decision shouldBe "denied"
        }
    }

    test("default [] globs: EVERY agent write — even a COMMIT — degrades (202); disk unchanged") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT, globs = emptyList()) { app, harness, store, _, _ ->
            app.putEdit(inId, app.hashOf(inId)).status shouldBe HttpStatusCode.Accepted
            store.read(TreePath.require("docs/in.md"))!!.decodeToString() shouldBe inDoc
            harness.proposalRepository.all().shouldHaveSize(1)
        }
    }

    test("a Human EDITOR PUT is still a 200 direct write (a Human is NEVER glob-checked)") {
        // The EDITOR edits the OUT-of-glob page; the glob gate is agent-only, so a Human writes directly regardless.
        withApp(Principal.Human("builtin", "editor"), role = Role.EDITOR) { app, harness, store, _, _ ->
            app.putEdit(outId, app.hashOf(outId)).status shouldBe HttpStatusCode.OK
            store.read(TreePath.require("notes/out.md"))!!.decodeToString() shouldBe edited
            harness.proposalRepository.all().shouldBeEmpty()
        }
    }

    test("revoked COMMIT token in-glob: agentModeFor → null fail-safe DEGRADES, the propose-gate then denies → 403") {
        // The mode==null fail-safe arm (GuardedMutatingFacade: a revoked/expired token at clock.now()). Seed a real
        // COMMIT token (the route fixes its Agent principal), then REVOKE it via the same api-token repo the existing
        // tests use — so agentModeFor returns null at query time on an IN-glob path. The decision degrades; the
        // degrade's inner propose re-checks EDIT for the now-revoked agent → AccessDenied → 403 (mirroring READ_ONLY).
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT) { app, harness, store, _, _ ->
            val baseHash = app.hashOf(inId) // read while the token is still live
            harness.apiTokens.revoke(harness.apiTokens.list().single().id)
            val resp = app.putEdit(inId, baseHash)
            resp.status shouldBe HttpStatusCode.Forbidden
            store.read(TreePath.require("docs/in.md"))!!.decodeToString() shouldBe inDoc // disk byte-unchanged
            harness.proposalRepository.all().shouldBeEmpty() // the degrade's propose was denied before any row
            harness.auditRepository.recent(50).edits().single { it.resource == "proposal" }.decision shouldBe "denied"
        }
    }

    // ---- (a) agent-create: the glob gate splits DIRECT page creation (C1: degrade, not 403) -----------

    test(
        "agent-create: a PROPOSE agent POST /pages DEGRADES to a create-proposal (202); NO page created; an allowed CREATE@\"proposal\" audit",
    ) {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.PROPOSE) { app, harness, store, _, _ ->
            val resp = app.postCreate("docs")
            withClue(resp.bodyAsText()) { resp.status shouldBe HttpStatusCode.Accepted }
            Json.parseToJsonElement(resp.bodyAsText()).jsonObject.getValue("proposal_id").jsonPrimitive.content.shouldNotBeEmpty()
            store.read(TreePath.require("docs/newpage.md")) shouldBe null // nothing written — it is a proposal now
            harness.proposalRepository.all().shouldHaveSize(1)
            harness.proposalRepository.all().single().operation shouldBe com.plainbase.domain.repository.ProposalOperation.CREATE
            harness.auditRepository.recent(50).creates().single { it.resource == "proposal" }.decision shouldBe "allowed"
        }
    }

    test("agent-create: a COMMIT agent OUT-of-glob POST /pages DEGRADES (202); NO page created; a PENDING create-proposal") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT) { app, harness, store, _, _ ->
            app.postCreate("notes").status shouldBe HttpStatusCode.Accepted
            store.read(TreePath.require("notes/newpage.md")) shouldBe null
            harness.proposalRepository.all().single().status shouldBe ProposalStatus.PENDING
        }
    }

    test("agent-create: a COMMIT agent IN-glob (docs/**) POST /pages is a 201 direct create") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT) { app, harness, store, _, _ ->
            app.postCreate("docs").status shouldBe HttpStatusCode.Created
            store.read(TreePath.require("docs/newpage.md")).shouldBeInstanceOf<ByteArray>()
            harness.auditRepository.recent(50).creates().single().decision shouldBe "allowed"
        }
    }

    test("agent-create: a COMMIT agent under the DEFAULT empty globs DEGRADES (202); NO page created") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT, globs = emptyList()) { app, harness, store, _, _ ->
            app.postCreate("docs").status shouldBe HttpStatusCode.Accepted
            store.read(TreePath.require("docs/newpage.md")) shouldBe null
            harness.proposalRepository.all().shouldHaveSize(1)
        }
    }

    test("agent-create: a READ_ONLY agent POST /pages is 403 (the degrade's inner checkCreate denies); NO page, NO proposal") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.READ_ONLY) { app, harness, store, _, _ ->
            app.postCreate("docs").status shouldBe HttpStatusCode.Forbidden
            store.read(TreePath.require("docs/newpage.md")) shouldBe null
            harness.proposalRepository.all().shouldBeEmpty()
            harness.auditRepository.recent(50).creates().single { it.resource == "proposal" }.decision shouldBe "denied"
        }
    }

    test("agent-create: a Human EDITOR POST /pages is STILL a 201 (a Human is NEVER glob-checked)") {
        withApp(Principal.Human("builtin", "editor"), role = Role.EDITOR) { app, _, store, _, _ ->
            app.postCreate("docs").status shouldBe HttpStatusCode.Created
            store.read(TreePath.require("docs/newpage.md")).shouldBeInstanceOf<ByteArray>()
        }
    }

    // ---- (b1) git attribution: an in-glob direct commit is agent-attributed in git -------------------

    test("git-attribution: an in-glob COMMIT agent's direct commit is git-attributed to the AGENT (author == committer), not 'Plainbase'") {
        val gitFactory: (Path) -> com.plainbase.domain.history.HistoryProvider = { root ->
            val home = Files.createTempDirectory("plainbase-direct-commit-git-home")
            providerOver(GitExecutor(workTree = root, home = home), root, home)
        }
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT, historyFactory = gitFactory) { app, harness, _, _, root ->
            val agentId = harness.apiTokens.list().single().id
            app.putEdit(inId, app.hashOf(inId)).status shouldBe HttpStatusCode.OK
            openOracle(root).use { repo ->
                val head = repo.headCommits().first()
                // The labeler snapshots the token label ("ci") + the PINNED synthetic email — NOT the server identity.
                head.authorIdent.name shouldBe "ci"
                head.authorIdent.emailAddress shouldBe "$agentId@agent.plainbase.local"
                head.committerIdent.name shouldBe "ci"
                head.committerIdent.emailAddress shouldBe "$agentId@agent.plainbase.local"
            }
        }
    }

    // ---- edge / contract ------------------------------------------------------------------------------

    test("missing-page COMMIT → 404 page_not_found DIRECT (not a StaleBase degrade); no proposal; an EDIT@id audit row") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT) { app, harness, _, _, _ ->
            val unknown = "0190aaaa-bbbb-7ccc-8ddd-0000000000ff"
            val resp = app.client.put("/api/v1/pages/$unknown") {
                header(HttpHeaders.IfMatch, "\"sha256:${"0".repeat(64)}\"")
                contentType(markdown)
                setBody(edited)
            }
            resp.status shouldBe HttpStatusCode.NotFound
            Json.parseToJsonElement(resp.bodyAsText()).jsonObject.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content shouldBe "page_not_found"
            harness.proposalRepository.all().shouldBeEmpty()
            harness.auditRepository.recent(50).edits().single { it.resource == unknown }.decision shouldBe "allowed"
        }
    }

    test("PUT-path stale_base 400: a COMMIT agent out-of-glob whose base_hash no longer matches disk → 400 stale_base; no proposal") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.COMMIT) { app, harness, store, _, _ ->
            val staleHash = app.hashOf(outId)
            // Drift the OUT-of-glob page underneath so the degrade's proposeEdit sees a stale base.
            store.write(TreePath.require("notes/out.md"), "---\ntitle: Out\n---\n\n# Out\n\nDRIFTED.\n".toByteArray())
            harness.builder.rebuild()
            val resp = app.putEdit(outId, staleHash)
            resp.status shouldBe HttpStatusCode.BadRequest
            Json.parseToJsonElement(resp.bodyAsText()).jsonObject shouldBe RestGolden.load("error-stale-base.json")
            harness.proposalRepository.all().shouldBeEmpty()
        }
    }

    // ---- apply-contamination guard (the WriteOrigin.PROPOSAL_APPLY bypass, BLOCKING-1) ----------------

    test("6a — ADMIN-Human approving an OUT-of-glob proposal applies cleanly (the apply is never glob-checked)") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val outHash = app.hashOf(outId)
            // Propose an OUT-of-glob edit, then approve as ADMIN — the apply's inner save takes the non-agent path.
            val created = app.client.post("/api/v1/changes") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"operation":"edit","page_id":"$outId","base_hash":"$outHash","proposed_content":${
                        Json.encodeToString(edited)
                    },"rationale":"r"}""",
                )
            }
            created.status shouldBe HttpStatusCode.Created
            val pid = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
            app.client.post("/api/v1/changes/$pid/approve").status shouldBe HttpStatusCode.OK
            store.read(TreePath.require("notes/out.md"))!!.decodeToString() shouldBe edited
            harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(pid))!!.status shouldBe ProposalStatus.APPLIED
        }
    }

    test("6b — an off-mode AGENT approver direct-applies an OUT-of-glob proposal: page updates, APPLIED, no phantom, no 500") {
        // auth.mode=off (enforced=false): checkApprove permits everyone, so an AGENT bearer can drive approve on a
        // PENDING out-of-glob proposal under the default globs=[]. The apply MUST direct-write (origin=PROPOSAL_APPLY),
        // NOT re-enter the glob decision (which would mint a phantom proposal, 500, and leave the original APPLYING).
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.PROPOSE, globs = emptyList(), enforced = false) {
                _,
                harness,
                store,
                ctx,
                _,
            ->
            val agent = Principal.Agent(harness.apiTokens.list().single().id)
            val outHash = citations.contentHash(outDoc.toByteArray())
            ctx.proposals.propose(
                agent,
                ProposeCommand.Edit(PageId.require(outId), outHash, null, edited.toByteArray(), "r"),
            )
            val pid = harness.proposalRepository.all().single().id
            val outcome = ctx.proposals.approve(agent, pid)
            outcome.shouldBeInstanceOf<ApplyOutcome.Applied>()
            store.read(TreePath.require("notes/out.md"))!!.decodeToString() shouldBe edited
            // Exactly ONE proposal (no phantom child minted), terminal APPLIED (never stuck APPLYING).
            harness.proposalRepository.all().shouldHaveSize(1)
            harness.proposalRepository.findById(pid)!!.status shouldBe ProposalStatus.APPLIED
        }
    }
})

/** A real FTS provider over a temp search.db so the read path resolves; closed with the harness. */
private fun IndexHarness.fts(): com.plainbase.domain.search.SearchProvider {
    val searchDb = com.plainbase.frameworks.search.SearchDb(Files.createTempDirectory("direct-commit-search").resolve("search.db"))
    return com.plainbase.frameworks.search.Fts5SearchProvider(searchDb)
}
