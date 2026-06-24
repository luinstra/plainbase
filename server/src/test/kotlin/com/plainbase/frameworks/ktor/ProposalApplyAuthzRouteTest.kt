package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.ApplyOutcome
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.domain.service.UuidV7ProposalIdProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.headCommits
import com.plainbase.frameworks.git.openOracle
import com.plainbase.frameworks.git.providerOver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.time.Clock

/**
 * PB-PROPOSE-1 P1b apply/rebase ENFORCED-MODE + correctness suite (closes hole #4 — CI/smoke run `auth.mode=off`,
 * where `checkApprove` is GATE-INVISIBLE). Every authz/correctness assertion constructs `PolicyService(enforced =
 * true)`, mirroring [ProposalAuthzRouteTest]'s `withApp` harness over [IndexHarness] + [fixedPrincipal].
 */
class ProposalApplyAuthzRouteTest : FunSpec({

    val json = ContentType.Application.Json
    val citations = CitationFactory()
    val docBody = "---\ntitle: Doc\n---\n\n# Doc\n\nbody.\n"
    val editedBody = "---\ntitle: Doc\n---\n\n# Doc\n\nedited body.\n"

    /**
     * Builds a real enforced route context over a temp tree with a seeded principal+role, runs [block]. [historyFactory]
     * lets a test wire a real Git provider; [mutateOverride] lets a test inject a fake MutatingFacade at the facade
     * boundary (the Unreadable injection, since the apply ProposalContentWriter is method-local).
     */
    fun withApp(
        principal: Principal,
        role: Role? = null,
        seedAgentMode: AgentMode? = null,
        historyFactory: (Path) -> HistoryProvider = { com.plainbase.frameworks.git.NoOpHistoryProvider },
        block: suspend (ApplicationTestBuilder, IndexHarness, LocalContentStore, RouteContext, Path) -> Unit,
    ) {
        val root = Files.createTempDirectory("plainbase-apply-authz")
        try {
            Files.writeString(root.resolve("doc.md"), docBody)
            val store = LocalContentStore(root)
            val history = historyFactory(root)
            IndexHarness(root, contentStore = store, history = history).use { harness ->
                history.prepare()
                harness.builder.rebuild()
                val resolved: Principal = when {
                    seedAgentMode != null -> Principal.Agent(harness.apiTokens.mint(label = "ci", mode = seedAgentMode).id)
                    principal is Principal.Human -> {
                        harness.roleRepository.upsert(principal.issuer, principal.externalId, role!!, Clock.System.now())
                        principal
                    }
                    else -> principal
                }
                val pipelineHook = com.plainbase.domain.service.WriteHistoryHook { path, bytes, author, committer ->
                    history.commit(path, bytes, author, committer)?.sha
                }
                val ctx = harness.testRouteContext(
                    contentStore = store,
                    writePipeline = harness.writePipeline(pipelineHook, store),
                    searchProvider = harness.fts(),
                    history = history,
                    enforced = true,
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

    suspend fun ApplicationTestBuilder.pageIdAndHash(): Pair<String, String> {
        val body = Json.parseToJsonElement(client.get("/api/v1/pages/by-path/doc").bodyAsText()).jsonObject
        return body.getValue("id").jsonPrimitive.content to body.getValue("content_hash").jsonPrimitive.content
    }

    suspend fun ApplicationTestBuilder.proposeEdit(id: String, hash: String, body: String = editedBody): String {
        val created = client.post("/api/v1/changes") {
            contentType(json)
            setBody(
                """{"operation":"edit","page_id":"$id","base_hash":"$hash","proposed_content":${Json.encodeToString(
                    body,
                )},"rationale":"r"}""",
            )
        }
        withClue(created.bodyAsText()) { created.status shouldBe HttpStatusCode.Created }
        return Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
    }

    // ---- Authz (enforced) ----------------------------------------------------------------------------

    test("enforced: a PROPOSE agent is DENIED approve (403); the proposal stays PENDING") {
        withApp(Principal.Anonymous, seedAgentMode = AgentMode.PROPOSE) { app, harness, _, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.Forbidden
            harness.proposalRepository.all().single().status shouldBe ProposalStatus.PENDING
        }
    }

    test("enforced: an EDITOR is DENIED approve (403)") {
        withApp(Principal.Human("builtin", "editor"), role = Role.EDITOR) { app, _, _, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("enforced: an ADMIN is ALLOWED approve (200); the live page now equals proposed_content; APPLIED") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            val resp = app.client.post("/api/v1/changes/$proposalId/approve")
            withClue(resp.bodyAsText()) { resp.status shouldBe HttpStatusCode.OK }
            store.read(TreePath.require("doc.md"))!!.decodeToString() shouldBe editedBody
            harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(proposalId))!!.status shouldBe
                ProposalStatus.APPLIED
        }
    }

    test("enforced: a PROPOSE agent + an EDITOR are DENIED rebase (403); an ADMIN is ALLOWED rebase") {
        // Build a CONFLICTED proposal as ADMIN, then assert the rebase authz matrix by re-binding the principal.
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            // Drift the live page so the apply conflicts.
            store.write(TreePath.require("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nDRIFTED.\n".toByteArray())
            harness.builder.rebuild()
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.Conflict
            // ADMIN rebase is allowed.
            app.client.post("/api/v1/changes/$proposalId/rebase").status shouldBe HttpStatusCode.OK
        }
        withApp(Principal.Human("builtin", "editor"), role = Role.EDITOR) { app, _, _, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            app.client.post("/api/v1/changes/$proposalId/rebase").status shouldBe HttpStatusCode.Forbidden
        }
    }

    // ---- Correctness (enforced) ----------------------------------------------------------------------

    test("double-approve race: exactly one wins (200 + single APPLIED + page written once); the loser is 409 not_pending") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, ctx, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            val pid = com.plainbase.domain.page.ProposalId.require(proposalId)
            val admin = Principal.Human("builtin", "admin")
            // Two concurrent approves over the ONE shared facade (single repo + pipeline) — the claimApplying CAS decides.
            val outcomes = mutableListOf<ApplyOutcome>()
            val a =
                thread {
                    synchronized(outcomes) { }
                    val o = ctx.proposals.approve(admin, pid)
                    synchronized(outcomes) { outcomes.add(o) }
                }
            val b = thread {
                val o = ctx.proposals.approve(admin, pid)
                synchronized(outcomes) { outcomes.add(o) }
            }
            a.join()
            b.join()
            val applied = outcomes.count { it is ApplyOutcome.Applied }
            val notPending = outcomes.count { it is ApplyOutcome.NotPending }
            applied shouldBe 1
            notPending shouldBe 1
            harness.proposalRepository.findById(pid)!!.status shouldBe ProposalStatus.APPLIED
            store.read(TreePath.require("doc.md"))!!.decodeToString() shouldBe editedBody
        }
    }

    test("apply-time drift -> 409 conflicted (code=conflicted + current_hash); the proposal is CONFLICTED (rebasable)") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            store.write(TreePath.require("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nDRIFTED underneath.\n".toByteArray())
            harness.builder.rebuild()
            val resp = app.client.post("/api/v1/changes/$proposalId/approve")
            resp.status shouldBe HttpStatusCode.Conflict
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            body.getValue("code").jsonPrimitive.content shouldBe "conflicted"
            body["current_hash"]?.jsonPrimitive?.content shouldNotBe null
            harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(proposalId))!!.status shouldBe
                ProposalStatus.CONFLICTED
        }
    }

    test("idempotent-replay -> 200 applied: the disk already equals proposed_content (a one-byte diff would conflict)") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            // Write the EXACT proposed bytes to disk (the disk already equals proposed) BUT keep the base_hash stale,
            // so the CAS reports Conflict with currentHash == hash(proposed) -> the idempotent-replay APPLIED branch.
            store.write(TreePath.require("doc.md"), editedBody.toByteArray())
            harness.builder.rebuild()
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.OK
            harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(proposalId))!!.status shouldBe
                ProposalStatus.APPLIED
        }
    }

    test("IdMismatch edit-apply -> 422 apply_failed; the proposal is FAILED + status_reason 'unsupported_edit: id'") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, _, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            // Propose a body whose frontmatter `id:` denotes a DIFFERENT identity than the page's on-disk id (a rename).
            val renamed = "---\ntitle: Doc\nid: 0190ffff-ffff-7fff-8fff-ffffffffffff\n---\n\n# Doc\n\nbody.\n"
            val proposalId = app.proposeEdit(id, hash, renamed)
            val resp = app.client.post("/api/v1/changes/$proposalId/approve")
            resp.status shouldBe HttpStatusCode.UnprocessableEntity
            Json.parseToJsonElement(
                resp.bodyAsText(),
            ).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                "apply_failed"
            val row = harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(proposalId))!!
            row.status shouldBe ProposalStatus.FAILED
            row.statusReason shouldBe "unsupported_edit: id"
            // get_change surfaces the durable reason.
            val detail = Json.parseToJsonElement(app.client.get("/api/v1/changes/$proposalId").bodyAsText()).jsonObject
            detail.getValue("status_reason").jsonPrimitive.content shouldBe "unsupported_edit: id"
        }
    }

    test("page_moved: the apply writes to the page's CURRENT pageId path, not the stale target_path") {
        // A MATERIALIZED-id page survives a move with its pageId intact (the id travels in the frontmatter), so the
        // proposal's pageId still resolves after the move — exercising the current-pageId-path apply resolution.
        val movableId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        val movableBody = "---\ntitle: Doc\nid: $movableId\n---\n\n# Doc\n\nbody.\n"
        val movableEdited = "---\ntitle: Doc\nid: $movableId\n---\n\n# Doc\n\nedited body.\n"
        val root = Files.createTempDirectory("plainbase-apply-moved")
        try {
            Files.writeString(root.resolve("doc.md"), movableBody)
            val store = LocalContentStore(root)
            IndexHarness(root, contentStore = store).use { harness ->
                harness.builder.rebuild()
                harness.roleRepository.upsert("builtin", "admin", Role.ADMIN, Clock.System.now())
                val ctx = harness.testRouteContext(
                    contentStore = store,
                    writePipeline = harness.writePipeline(store = store),
                    searchProvider = harness.fts(),
                    enforced = true,
                    extract = fixedPrincipal(Principal.Human("builtin", "admin")),
                )
                testApplication {
                    application { plainbaseModule(ctx) }
                    val (id, hash) = pageIdAndHash()
                    id shouldBe movableId
                    val proposalId = proposeEdit(id, hash, movableEdited)
                    // MOVE the page on disk (same materialized id, new path), rebuild so byId[pageId].path changes.
                    Files.createDirectories(root.resolve("guides"))
                    Files.write(root.resolve("guides/doc.md"), Files.readAllBytes(root.resolve("doc.md")))
                    Files.delete(root.resolve("doc.md"))
                    harness.builder.rebuild()
                    harness.builder.current.byId[PageId.require(movableId)]!!.path shouldBe TreePath.require("guides/doc.md")
                    val resp = client.post("/api/v1/changes/$proposalId/approve")
                    withClue(resp.bodyAsText()) { resp.status shouldBe HttpStatusCode.OK }
                    // The bytes land at the NEW path (resolved via pageId), NOT the stale target_path doc.md.
                    store.read(TreePath.require("guides/doc.md"))!!.decodeToString() shouldBe movableEdited
                    harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(proposalId))!!.status shouldBe
                        ProposalStatus.APPLIED
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("WrittenButUnindexed -> 200 applied + reindex_deferred; applied_commit null; dirty page heals at next reconcile") {
        // Inject a throwing post-write hook so the apply yields WrittenButUnindexed.
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            // Rebuild the route ctx with a pipeline whose history hook throws AFTER the disk write.
            val throwingHook = com.plainbase.domain.service.WriteHistoryHook { _, _, _, _ -> throw RuntimeException("post-write boom") }
            val pipeline = harness.writePipeline(throwingHook, store)
            val labeler = ProposalAuthorLabeler(harness.apiTokenRepository, harness.userRepository)
            val policy =
                PolicyService(
                    harness.roleRepository,
                    harness.apiTokenRepository,
                    harness.auditRepository,
                    com.plainbase.domain.service.UuidV7IdProvider(),
                    Clock.System,
                    enforced = true,
                )
            val mutate = GuardedMutatingFacade(policy, pipeline, store, harness.builder)
            val proposalService =
                ProposalService(
                    harness.proposalRepository,
                    citations,
                    IndexProposalBaseReader(harness.builder, store),
                    UuidV7ProposalIdProvider(),
                    Clock.System,
                )
            val facade = GuardedProposalFacade(policy, proposalService, labeler, mutate)
            val admin = Principal.Human("builtin", "admin")
            val proposalId = app.proposeEdit(id, hash)
            val pid = com.plainbase.domain.page.ProposalId.require(proposalId)
            val outcome = facade.approve(admin, pid)
            (outcome as ApplyOutcome.Applied).reindexDeferred shouldBe true
            val row = harness.proposalRepository.findById(pid)!!
            row.status shouldBe ProposalStatus.APPLIED
            row.appliedCommit shouldBe null
            row.statusReason shouldBe "reindex_deferred"
            harness.dirtyPages.all().isNotEmpty() shouldBe true
            // Next reconcile heals the dirty page.
            harness.writePipeline(store = store).reconcileDirtyPages()
        }
    }

    test("Unreadable -> Failed + status_reason 'unreadable', no FS leak (fake MutatingFacade at the facade boundary)") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            val pid = com.plainbase.domain.page.ProposalId.require(proposalId)
            val policy =
                PolicyService(
                    harness.roleRepository,
                    harness.apiTokenRepository,
                    harness.auditRepository,
                    com.plainbase.domain.service.UuidV7IdProvider(),
                    Clock.System,
                    enforced = true,
                )
            val proposalService =
                ProposalService(
                    harness.proposalRepository,
                    citations,
                    IndexProposalBaseReader(harness.builder, store),
                    UuidV7ProposalIdProvider(),
                    Clock.System,
                )
            // A fake MutatingFacade whose save returns Unreadable carrying a sensitive FS detail.
            val fakeMutate = object : com.plainbase.domain.service.MutatingFacade {
                override fun save(principal: Principal, request: SaveRequest): SaveResult =
                    SaveResult.Written(WriteOutcome.Unreadable(cause = "/var/lib/plainbase/secret/doc.md: permission denied"))
                override fun create(principal: Principal, intent: com.plainbase.domain.service.CreateIntent) = error("unused")
                override fun writeAsset(
                    principal: Principal,
                    pageId: PageId,
                    filename: String,
                    bytes: ByteArray,
                    hasher: (ByteArray) -> String,
                ) = error("unused")
                override fun rescan(principal: Principal) = error("unused")
                override fun reindex(principal: Principal) = error("unused")
            }
            val facade =
                GuardedProposalFacade(
                    policy,
                    proposalService,
                    ProposalAuthorLabeler(harness.apiTokenRepository, harness.userRepository),
                    fakeMutate,
                )
            val outcome = facade.approve(Principal.Human("builtin", "admin"), pid)
            (outcome as ApplyOutcome.Failed).reason shouldBe "unreadable" // the STABLE string, never the raw cause
            val row = harness.proposalRepository.findById(pid)!!
            row.status shouldBe ProposalStatus.FAILED
            row.statusReason shouldBe "unreadable"
            // No FS path leaked into the durable reason.
            row.statusReason!!.shouldNotContainFsPath()
        }
    }

    test("CREATE-apply unsupported -> 422 create_apply_unsupported; FAILED + status_reason; NO content write; never APPLYING") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val created = app.client.post("/api/v1/changes") {
                contentType(json)
                setBody("""{"operation":"create","target_path":"guides/brand-new.md","proposed_content":"# New\n","rationale":"r"}""")
            }
            created.status shouldBe HttpStatusCode.Created
            val proposalId = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
            val resp = app.client.post("/api/v1/changes/$proposalId/approve")
            resp.status shouldBe HttpStatusCode.UnprocessableEntity
            Json.parseToJsonElement(
                resp.bodyAsText(),
            ).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                "create_apply_unsupported"
            val row = harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(proposalId))!!
            row.status shouldBe ProposalStatus.FAILED
            row.statusReason shouldBe "create_apply_unsupported"
            // No new file was written at the target path.
            store.read(TreePath.require("guides/brand-new.md")) shouldBe null
        }
    }

    test("get_change exposes the durable status_reason (PENDING -> null; APPLIED -> null on a clean apply)") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, _, _, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            // While PENDING, status_reason is present-null.
            val pending = Json.parseToJsonElement(app.client.get("/api/v1/changes/$proposalId").bodyAsText()).jsonObject
            pending.containsKey("status_reason") shouldBe true
            pending.getValue("status_reason").let { it.jsonPrimitive.contentOrNull() } shouldBe null
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.OK
            val applied = Json.parseToJsonElement(app.client.get("/api/v1/changes/$proposalId").bodyAsText()).jsonObject
            applied.getValue("status").jsonPrimitive.content shouldBe "APPLIED"
        }
    }

    // ---- Rebase round-trip (enforced) ----------------------------------------------------------------

    test("rebase -> re-approve applies against the new base (last-writer-wins)") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            store.write(TreePath.require("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nintervening.\n".toByteArray())
            harness.builder.rebuild()
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.Conflict
            val rebase = app.client.post("/api/v1/changes/$proposalId/rebase")
            rebase.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(rebase.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content shouldBe "PENDING"
            // get_change on the rebased proposal shows it cleanly PENDING, NOT decided: the decision metadata the
            // CONFLICTED stamp wrote (approver_*, decided_at, status_reason) is cleared, contract-identical to a fresh row.
            val rebased = Json.parseToJsonElement(app.client.get("/api/v1/changes/$proposalId").bodyAsText()).jsonObject
            rebased.getValue("status").jsonPrimitive.content shouldBe "PENDING"
            rebased.getValue("approver_issuer").jsonPrimitive.contentOrNull() shouldBe null
            rebased.getValue("approver_external_id").jsonPrimitive.contentOrNull() shouldBe null
            rebased.getValue("decided_at").jsonPrimitive.contentOrNull() shouldBe null
            rebased.getValue("status_reason").jsonPrimitive.contentOrNull() shouldBe null
            // Re-approve against the new base applies cleanly.
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.OK
            store.read(TreePath.require("doc.md"))!!.decodeToString() shouldBe editedBody
        }
    }

    test("rebase Gone -> 422 apply_failed; the proposal is FAILED + status_reason 'rebase_target_gone'") {
        withApp(Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, store, _, root ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = app.proposeEdit(id, hash)
            store.write(TreePath.require("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nintervening.\n".toByteArray())
            harness.builder.rebuild()
            app.client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.Conflict
            // DELETE the target page so pathOf(pageId) returns null.
            Files.delete(root.resolve("doc.md"))
            harness.builder.rebuild()
            val rebase = app.client.post("/api/v1/changes/$proposalId/rebase")
            rebase.status shouldBe HttpStatusCode.UnprocessableEntity
            Json.parseToJsonElement(
                rebase.bodyAsText(),
            ).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                "apply_failed"
            val row = harness.proposalRepository.findById(com.plainbase.domain.page.ProposalId.require(proposalId))!!
            row.status shouldBe ProposalStatus.FAILED
            row.statusReason shouldBe "rebase_target_gone"
        }
    }

    // ---- git attribution (git-mode) ------------------------------------------------------------------

    test("git-attribution: the apply commit author == the snapshotted proposer, committer == the approving ADMIN") {
        val gitFactory: (Path) -> HistoryProvider = { root ->
            val home = Files.createTempDirectory("plainbase-apply-git-home")
            providerOver(GitExecutor(workTree = root, home = home), root, home)
        }
        // Propose as an AGENT (so the proposer attribution is the agent token), then approve as an ADMIN.
        // We need BOTH principals; build the proposal via the agent principal first, then re-bind ADMIN for approve.
        val root = Files.createTempDirectory("plainbase-apply-git")
        try {
            Files.writeString(root.resolve("doc.md"), docBody)
            val store = LocalContentStore(root)
            val history = gitFactory(root)
            IndexHarness(root, contentStore = store, history = history).use { harness ->
                history.prepare()
                harness.builder.rebuild()
                // Seed an ADMIN + a PROPOSE agent.
                harness.roleRepository.upsert("builtin", "admin", Role.ADMIN, Clock.System.now())
                val agentId = harness.apiTokens.mint(label = "ci-bot", mode = AgentMode.PROPOSE).id
                val agent = Principal.Agent(agentId)
                val admin = Principal.Human("builtin", "admin")
                val policy =
                    PolicyService(
                        harness.roleRepository,
                        harness.apiTokenRepository,
                        harness.auditRepository,
                        com.plainbase.domain.service.UuidV7IdProvider(),
                        Clock.System,
                        enforced = true,
                    )
                val pipelineHook = com.plainbase.domain.service.WriteHistoryHook { p, b, a, c -> history.commit(p, b, a, c)?.sha }
                val pipeline = harness.writePipeline(pipelineHook, store)
                val mutate = GuardedMutatingFacade(policy, pipeline, store, harness.builder)
                val labeler = ProposalAuthorLabeler(harness.apiTokenRepository, harness.userRepository)
                val proposalService =
                    ProposalService(
                        harness.proposalRepository,
                        citations,
                        IndexProposalBaseReader(harness.builder, store),
                        UuidV7ProposalIdProvider(),
                        Clock.System,
                    )
                val facade = GuardedProposalFacade(policy, proposalService, labeler, mutate)
                // Propose (agent) then approve (admin).
                val page = harness.builder.current.pages.single()
                facade.propose(
                    agent,
                    com.plainbase.domain.service.ProposeCommand.Edit(page.id, page.contentHash, null, editedBody.toByteArray(), "r"),
                )
                val pid = harness.proposalRepository.all().single().id
                facade.approve(admin, pid).shouldBeAppliedClean()
                // The HEAD commit's author is the proposer (agent), committer is the approving ADMIN.
                openOracle(root).use { repo ->
                    val head = repo.headCommits().first()
                    head.authorIdent.name shouldBe "ci-bot"
                    head.authorIdent.emailAddress shouldBe "$agentId@agent.plainbase.local"
                    head.committerIdent.emailAddress shouldBe "admin@builtin.plainbase.local"
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

private fun String.shouldNotContainFsPath() {
    this shouldNotContain "/"
}

private fun ApplyOutcome.shouldBeAppliedClean() {
    check(this is ApplyOutcome.Applied) { "expected Applied, got $this" }
}

/** A real FTS provider over a temp search.db so the read path resolves; closed with the harness. */
private fun IndexHarness.fts(): com.plainbase.domain.search.SearchProvider {
    val searchDb = com.plainbase.frameworks.search.SearchDb(Files.createTempDirectory("apply-authz-search").resolve("search.db"))
    return com.plainbase.frameworks.search.Fts5SearchProvider(searchDb)
}
