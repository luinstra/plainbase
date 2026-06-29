package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * WI-9 (C1, MANDATORY): the byte-identical differential — a direct `POST /api/v1/pages` create and an
 * approve(create-proposal) of the SAME `{folder, title, slug?, body?}` inputs (with the SAME deterministic minted id)
 * must produce BYTE-IDENTICAL on-disk bytes, because both flow through the ONE shared `composeDocument` seam (the
 * direct path composes-and-writes; the degrade path composes-and-stores, then apply writes the stored bytes verbatim).
 *
 * TWO ISOLATED content roots (one per flow): a single shared root is unrunnable — both flows create the SAME id/path,
 * so the second create would hit `AlreadyExists`. Each root gets its OWN deterministic [TestIdProvider] seeded to the
 * same first id, so the baked `id:` line is identical and only the composer governs the rest.
 */
class CreateApplyDifferentialTest : FunSpec({

    val json = ContentType.Application.Json
    val createBody = """{"folder":"guides","title":"Differential: A/B","slug":"diff-page","body":"# Hello\n\nbody & text > here\n"}"""
    val createdPath = TreePath.require("guides/diff-page.md")

    fun IndexHarness.contextFor(store: LocalContentStore, principal: Principal, enforced: Boolean) = testRouteContext(
        contentStore = store,
        writePipeline = writePipeline(store = store),
        searchProvider = fts(),
        enforced = enforced,
        idProvider = TestIdProvider(), // a FRESH deterministic provider → the SAME first id in each flow
        agentDirectCommitGlobs = emptyList(), // every agent create degrades
        extract = fixedPrincipal(principal),
    )

    test("a direct POST-create and a degrade->approve create produce byte-identical on-disk bytes (the shared composer)") {
        // ---- Flow A (direct), root A: a Human EDITOR POST /pages, then read the on-disk bytes. ----
        val rootA = Files.createTempDirectory("plainbase-diff-a")
        val bytesA: ByteArray
        try {
            val storeA = LocalContentStore(rootA)
            IndexHarness(rootA, contentStore = storeA).use { harness ->
                harness.builder.rebuild()
                harness.roleRepository.upsert("builtin", "editor", Role.EDITOR, Clock.System.now())
                val ctx = harness.contextFor(storeA, Principal.Human("builtin", "editor"), enforced = true)
                testApplication {
                    application { plainbaseModule(ctx) }
                    val resp = client.post("/api/v1/pages") {
                        contentType(json)
                        setBody(createBody)
                    }
                    withClue(resp.bodyAsText()) { resp.status shouldBe HttpStatusCode.Created }
                }
                bytesA = storeA.read(createdPath)!!
            }
        } finally {
            rootA.toFile().deleteRecursively()
        }

        // ---- Flow B (degrade -> apply), root B: an out-of-glob COMMIT agent POST /pages (202), then ADMIN approve. ----
        val rootB = Files.createTempDirectory("plainbase-diff-b")
        var bytesB: ByteArray? = null
        try {
            val storeB = LocalContentStore(rootB)
            IndexHarness(rootB, contentStore = storeB).use { harness ->
                harness.builder.rebuild()
                harness.roleRepository.upsert("builtin", "admin", Role.ADMIN, Clock.System.now())
                val agent = Principal.Agent(harness.apiTokens.mint(label = "ci", mode = AgentMode.COMMIT).id)
                var proposalId = ""
                // 1) agent POST /pages → 202 degrade (the create-proposal's blob == composeDocument(id, ...)).
                val agentCtx = harness.contextFor(storeB, agent, enforced = true)
                testApplication {
                    application { plainbaseModule(agentCtx) }
                    val resp = client.post("/api/v1/pages") {
                        contentType(json)
                        setBody(createBody)
                    }
                    withClue(resp.bodyAsText()) { resp.status shouldBe HttpStatusCode.Accepted }
                    proposalId = Json.parseToJsonElement(resp.bodyAsText()).jsonObject.getValue("proposal_id").jsonPrimitive.content
                }
                // 2) ADMIN approve over the SAME harness → the apply writes the stored bytes verbatim.
                val adminCtx = harness.contextFor(storeB, Principal.Human("builtin", "admin"), enforced = true)
                testApplication {
                    application { plainbaseModule(adminCtx) }
                    val resp = client.post("/api/v1/changes/$proposalId/approve")
                    withClue(resp.bodyAsText()) { resp.status shouldBe HttpStatusCode.OK }
                }
                bytesB = storeB.read(createdPath)!!
            }
        } finally {
            rootB.toFile().deleteRecursively()
        }

        // The deterministic id makes the `id:` line identical; composeDocument makes the rest identical.
        val finalB = bytesB!!
        withClue("direct=${bytesA.decodeToString()}\napply=${finalB.decodeToString()}") {
            bytesA.contentEquals(finalB) shouldBe true
        }
    }
})

/** A real FTS provider over a temp search.db so the read path resolves; closed with the harness. */
private fun IndexHarness.fts(): com.plainbase.domain.search.SearchProvider {
    val searchDb = com.plainbase.frameworks.search.SearchDb(Files.createTempDirectory("diff-search").resolve("search.db"))
    return com.plainbase.frameworks.search.Fts5SearchProvider(searchDb)
}
