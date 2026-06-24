package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.IndexHarness
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.time.Clock

/**
 * PB-PROPOSE-1 ENFORCED-MODE + off-mode authz (closes hole #4 — CI/smoke run `auth.mode=off`, where the matrix is
 * INVISIBLE). These tests construct `enforced=true` route contexts so a propose/reject gating regression is caught.
 * The off-mode anonymous-propose test (E1) is explicitly the off-mode path (it COMPLEMENTS the enforced ones).
 */
class ProposalAuthzRouteTest : FunSpec({

    val json = ContentType.Application.Json

    fun withApp(
        enforced: Boolean,
        principal: Principal?,
        role: Role? = null,
        seedAgentMode: AgentMode? = null,
        block: suspend (io.ktor.server.testing.ApplicationTestBuilder, IndexHarness, Principal) -> Unit,
    ) {
        val root = Files.createTempDirectory("plainbase-proposal-authz")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nbody.\n")
            // ONE store the builder scans AND the proposal reader reads through (LocalContentStore.read needs the
            // scan-retained raw name; a fresh unscanned store would return null and every base-hash check stale).
            val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)
            IndexHarness(root, contentStore = store).use { harness ->
                harness.builder.rebuild()
                val resolved: Principal = when {
                    seedAgentMode != null -> Principal.Agent(harness.apiTokens.mint(label = "ci", mode = seedAgentMode).id)
                    principal is Principal.Human -> {
                        harness.roleRepository.upsert(principal.issuer, principal.externalId, role!!, Clock.System.now())
                        principal
                    }
                    else -> principal ?: Principal.Anonymous
                }
                val ctx = harness.testRouteContext(
                    contentStore = store,
                    searchProvider = harness.fts(root),
                    enforced = enforced,
                    extract = fixedPrincipal(resolved),
                )
                testApplication {
                    application { plainbaseModule(ctx) }
                    block(this, harness, resolved)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    suspend fun io.ktor.server.testing.ApplicationTestBuilder.pageIdAndHash(): Pair<String, String> {
        val body = Json.parseToJsonElement(client.get("/api/v1/pages/by-path/doc").bodyAsText()).jsonObject
        return body.getValue("id").jsonPrimitive.content to body.getValue("content_hash").jsonPrimitive.content
    }

    test("enforced: a READ_ONLY agent is DENIED propose (403)") {
        withApp(enforced = true, principal = null, seedAgentMode = AgentMode.READ_ONLY) { app, _, _ ->
            val (id, hash) = app.pageIdAndHash()
            app.client.post("/api/v1/changes") {
                contentType(json)
                setBody("""{"operation":"edit","page_id":"$id","base_hash":"$hash","proposed_content":"x","rationale":"r"}""")
            }.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("enforced: a DENIED propose does NO author resolution before the deny is audited+thrown (check-first)") {
        // Build the facade DIRECTLY over a labeler whose token repo COUNTS label lookups: a denied propose must
        // mint-the-grant FIRST, so the labeler never touches the repo (no token/user load) before the deny is
        // audited and thrown. The deny audit row is the FIRST observable effect.
        val root = Files.createTempDirectory("plainbase-propose-check-first")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nbody.\n")
            val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)
            IndexHarness(root, contentStore = store).use { harness ->
                harness.builder.rebuild()
                val labelLookups = java.util.concurrent.atomic.AtomicInteger(0)
                val countingTokens = object : com.plainbase.domain.repository.ApiTokenRepository by harness.apiTokenRepository {
                    override fun agentLabelById(id: String): String? {
                        labelLookups.incrementAndGet()
                        return harness.apiTokenRepository.agentLabelById(id)
                    }
                }
                val policy = com.plainbase.domain.service.PolicyService(
                    roles = harness.roleRepository,
                    apiTokens = harness.apiTokenRepository,
                    audit = harness.auditRepository,
                    idProvider = com.plainbase.domain.service.UuidV7IdProvider(),
                    clock = Clock.System,
                    enforced = true,
                )
                val facade = GuardedProposalFacade(
                    policy = policy,
                    proposals = com.plainbase.domain.service.ProposalService(
                        repository = harness.proposalRepository,
                        citations = com.plainbase.domain.service.CitationFactory(),
                        baseReader = com.plainbase.frameworks.ktor.IndexProposalBaseReader(harness.builder, store),
                        proposalIdProvider = com.plainbase.domain.service.UuidV7ProposalIdProvider(),
                        clock = Clock.System,
                    ),
                    labeler = com.plainbase.domain.service.ProposalAuthorLabeler(tokens = countingTokens, users = harness.userRepository),
                    // This test only drives propose (a denied EDIT) — the apply seam is never consulted.
                    mutate = UnusedMutatingFacade,
                )
                val readOnly = Principal.Agent(harness.apiTokens.mint(label = "ci", mode = AgentMode.READ_ONLY).id)
                val page = harness.builder.current.pages.single()

                io.kotest.assertions.throwables.shouldThrow<com.plainbase.domain.service.AccessDenied> {
                    facade.propose(
                        readOnly,
                        com.plainbase.domain.service.ProposeCommand.Edit(
                            pageId = page.id,
                            baseHash = page.contentHash,
                            clientTargetPath = null,
                            proposedContent = "x".encodeToByteArray(),
                            rationale = "r",
                        ),
                    )
                }
                // No author resolution happened before the deny — the labeler never touched the token repo.
                labelLookups.get() shouldBe 0
                // The deny IS audited (the first effect): exactly one denied EDIT row, no proposal persisted.
                val rows = harness.auditRepository.recent(10)
                rows.single().decision shouldBe "denied"
                harness.proposalRepository.all().shouldBeEmpty()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("enforced: a PROPOSE agent is ALLOWED propose; the stored author snapshot is the token's agentLabel (C4)") {
        withApp(enforced = true, principal = null, seedAgentMode = AgentMode.PROPOSE) { app, harness, principal ->
            val (id, hash) = app.pageIdAndHash()
            val created = app.client.post("/api/v1/changes") {
                contentType(json)
                setBody(
                    """{"operation":"edit","page_id":"$id","base_hash":"$hash","proposed_content":"---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n","rationale":"r"}""",
                )
            }
            io.kotest.assertions.withClue(created.bodyAsText()) { created.status shouldBe HttpStatusCode.Created }
            // The stored author snapshot is the token's agentLabel ("ci"), resolved at propose time (survives revocation).
            val row = harness.proposalRepository.all().single()
            row.authorIssuer shouldBe "agent"
            row.authorExternalId shouldBe (principal as Principal.Agent).tokenId
            row.authorLabel shouldBe "ci"
            // The label survives the token's later revocation (it's a snapshot, not a JOIN).
            harness.apiTokens.revoke(principal.tokenId)
            harness.proposalRepository.all().single().authorLabel shouldBe "ci"
        }
    }

    test("enforced: reject from a non-ADMIN (EDITOR) is DENIED; from an ADMIN is ALLOWED") {
        // First, an EDITOR proposes (allowed), then is denied reject; an ADMIN context rejects it.
        withApp(enforced = true, principal = Principal.Human("builtin", "editor"), role = Role.EDITOR) { app, harness, _ ->
            val (id, hash) = app.pageIdAndHash()
            val proposalId = Json.parseToJsonElement(
                app.client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(
                        """{"operation":"edit","page_id":"$id","base_hash":"$hash","proposed_content":"---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n","rationale":"r"}""",
                    )
                }.bodyAsText(),
            ).jsonObject.getValue("id").jsonPrimitive.content
            // An EDITOR cannot approve/reject (ADMIN-only).
            app.client.post("/api/v1/changes/$proposalId/reject") {
                contentType(json)
                setBody("{}")
            }.status shouldBe
                HttpStatusCode.Forbidden
        }
        // An ADMIN context can reject any pending proposal.
        withApp(enforced = true, principal = Principal.Human("builtin", "admin"), role = Role.ADMIN) { app, harness, _ ->
            val (id, hash) = app.pageIdAndHash()
            harness.builder.rebuild()
            val proposalId = Json.parseToJsonElement(
                app.client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(
                        """{"operation":"edit","page_id":"$id","base_hash":"$hash","proposed_content":"---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n","rationale":"r"}""",
                    )
                }.bodyAsText(),
            ).jsonObject.getValue("id").jsonPrimitive.content
            app.client.post("/api/v1/changes/$proposalId/reject") {
                contentType(json)
                setBody("""{"comment":"no"}""")
            }.status shouldBe
                HttpStatusCode.OK
        }
    }

    test("OFF mode (E1): a no-credential Anonymous propose SUCCEEDS and stores the synthetic anonymous author snapshot") {
        withApp(enforced = false, principal = Principal.Anonymous) { app, harness, _ ->
            val (id, hash) = app.pageIdAndHash()
            val created = app.client.post("/api/v1/changes") {
                contentType(json)
                setBody(
                    """{"operation":"edit","page_id":"$id","base_hash":"$hash","proposed_content":"---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n","rationale":"r"}""",
                )
            }
            created.status shouldBe HttpStatusCode.Created
            val row = harness.proposalRepository.all().single()
            // The NOT-NULL author columns are populated with the synthetic anonymous triple (never NULL).
            row.authorIssuer shouldBe "anonymous"
            row.authorExternalId shouldBe "local"
            row.authorLabel shouldBe "anonymous"
        }
    }
})

/** A real FTS provider over a temp search.db so the read path resolves; closed with the harness. */
private fun IndexHarness.fts(@Suppress("UNUSED_PARAMETER") root: java.nio.file.Path): com.plainbase.domain.search.SearchProvider {
    val searchDb = com.plainbase.frameworks.search.SearchDb(Files.createTempDirectory("proposal-authz-search").resolve("search.db"))
    return com.plainbase.frameworks.search.Fts5SearchProvider(searchDb)
}

/** A MutatingFacade for propose-only facade tests (the apply seam is never consulted); every method errors. */
private object UnusedMutatingFacade : com.plainbase.domain.service.MutatingFacade {
    override fun save(principal: Principal, request: com.plainbase.domain.service.SaveRequest) = error("unused")
    override fun create(principal: Principal, intent: com.plainbase.domain.service.CreateIntent) = error("unused")
    override fun writeAsset(
        principal: Principal,
        pageId: com.plainbase.domain.page.PageId,
        filename: String,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
    ) = error("unused")
    override fun rescan(principal: Principal) = error("unused")
    override fun reindex(principal: Principal) = error("unused")
}
