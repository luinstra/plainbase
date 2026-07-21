package com.plainbase.frameworks.mcp

import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest
import com.plainbase.frameworks.ktor.dto.ProposeChangeResponse
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.routes.CANONICAL_PROPOSAL_ID
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MCP surface + REST↔MCP parity (WI-7). The seven tools are a THIN transport over the SAME guarded facades +
 * frozen DTOs as REST, so the six read/list/get tools are BYTE-identical to their REST endpoints for the same
 * fixture/input, and `propose_change` is structural-parity excluding the freshly minted id. A divergence here means
 * the MCP path drifted from REST (an `explicitNulls` slip, a wrong serializer, a wrong surface).
 */
class McpSurfaceTest : FunSpec({

    fun proposeRequest(pageId: String, baseHash: String, content: String = "---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n") =
        ProposeChangeRequest("edit", pageId = pageId, baseHash = baseHash, proposedContent = content, rationale = "improve")

    fun proposeArgs(pageId: String, baseHash: String, content: String = "---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n") = mapOf(
        "operation" to "edit",
        "page_id" to pageId,
        "base_hash" to baseHash,
        "proposed_content" to content,
        "rationale" to "improve",
    )

    test("the tool surface is EXACTLY the seven §2.6 names — no read_section/read_file/approve/reject/rebase") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val names = client.listTools().tools.map { it.name }.toSet()
                names shouldBe McpTools.ALL
                for (absent in listOf("read_section", "read_file", "approve", "reject", "rebase")) {
                    names shouldNotContain absent
                }
            }
        }
    }

    test("the six read/list/get tools are BYTE-identical to their REST endpoints (same fixture + input)") {
        McpHarness().use { harness ->
            val id = harness.seedPageId
            val bearer = harness.proposeBearer
            harness.session(bearer) { client ->
                // Seed ONE proposal so list_changes/get_change have data; do it BEFORE the paired reads (no mutation between a pair).
                val proposalId = Json.parseToJsonElement(client.call("propose_change", proposeArgs(id, harness.seedBaseHash)).text())
                    .jsonObject.getValue("id").jsonPrimitive.content

                client.call("search", mapOf("q" to "Doc")).text() shouldBe harness.restGet("/api/v1/search?q=Doc", bearer)
                client.call("read_page", mapOf("id" to id)).text() shouldBe harness.restGet("/api/v1/pages/$id", bearer)
                client.call("get_page_metadata", mapOf("id" to id)).text() shouldBe harness.restGet("/api/v1/pages/$id/metadata", bearer)
                client.call("validate_links", mapOf("id" to id)).text() shouldBe harness.restGet("/api/v1/pages/$id/validate-links", bearer)
                client.call("list_changes").text() shouldBe harness.restGet("/api/v1/changes", bearer)
                client.call("get_change", mapOf("id" to proposalId)).text() shouldBe harness.restGet("/api/v1/changes/$proposalId", bearer)
            }
        }
    }

    test("propose_change is STRUCTURAL parity with POST /api/v1/changes (all fields except the freshly minted id)") {
        McpHarness().use { harness ->
            val id = harness.seedPageId
            val bearer = harness.proposeBearer
            val mcpBody = harness.session(bearer) { client -> client.call("propose_change", proposeArgs(id, harness.seedBaseHash)).text() }
            val restBody = harness.restPost(
                "/api/v1/changes",
                bearer,
                RestJson.encodeToString(ProposeChangeRequest.serializer(), proposeRequest(id, harness.seedBaseHash)),
            )
            val mcp = RestJson.decodeFromString(ProposeChangeResponse.serializer(), mcpBody)
            val rest = RestJson.decodeFromString(ProposeChangeResponse.serializer(), restBody)
            mcp.status shouldBe rest.status // "PENDING"
            mcp.unifiedDiff shouldBe rest.unifiedDiff // deterministic given the same base + content
            mcp.id shouldNotBe rest.id // two distinct inserts mint two distinct ids by design
            CANONICAL_PROPOSAL_ID.matches(mcp.id) shouldBe true // the MCP id is a well-formed ProposalId
        }
    }

    test("proposed_content round-trips as UTF-8 bytes (no base64): multi-byte content stores verbatim") {
        McpHarness().use { harness ->
            val content = "---\ntitle: Doc\n---\n\n# Doc\n\nemoji 🚀 and CJK 日本語 round-trip.\n"
            harness.session(harness.proposeBearer) { client ->
                client.call("propose_change", proposeArgs(harness.seedPageId, harness.seedBaseHash, content)).isErr() shouldBe false
            }
            harness.proposalContentBytes().toList() shouldBe content.encodeToByteArray().toList()
        }
    }

    test("page tools reject missing, non-string, and non-canonical ids with the frozen invalid_page_id envelope") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                for (tool in listOf("read_page", "get_page_metadata", "validate_links")) {
                    for (args in listOf(emptyMap(), mapOf("id" to 42), mapOf("id" to "not-a-uuid"))) {
                        val result = client.call(tool, args)
                        result.isErr() shouldBe true
                        result.text() shouldContain "invalid_page_id"
                    }
                }
            }
        }
    }

    test("get_change distinguishes malformed ids from well-formed unknown ids") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                for (args in listOf(emptyMap(), mapOf("id" to 42), mapOf("id" to "not-a-uuid"))) {
                    val malformed = client.call("get_change", args)
                    malformed.isErr() shouldBe true
                    malformed.text() shouldContain "invalid_propose_request"
                }

                val unknown = client.call("get_change", mapOf("id" to "01900000-0000-7000-9000-000000000099"))
                unknown.isErr() shouldBe true
                unknown.text() shouldContain "not_found"
            }
        }
    }

    test("propose_change maps malformed argument shapes and stale bases to stable client errors") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                for (args in listOf(emptyMap(), mapOf("operation" to 42), mapOf("operation" to "delete"))) {
                    val malformed = client.call("propose_change", args)
                    malformed.isErr() shouldBe true
                    malformed.text() shouldContain "invalid_propose_request"
                }

                val stale = client.call(
                    "propose_change",
                    proposeArgs(harness.seedPageId, "sha256:${"0".repeat(64)}"),
                )
                stale.isErr() shouldBe true
                stale.text() shouldContain "stale_base"
            }
        }
    }

    test("DNS-rebinding: an SSE GET with an Origin outside the allowlist does NOT succeed") {
        McpHarness().use { harness ->
            harness.rawMcpGet(bearer = harness.proposeBearer, origin = "http://evil.example.com").status.isSuccess() shouldBe false
        }
    }
})
