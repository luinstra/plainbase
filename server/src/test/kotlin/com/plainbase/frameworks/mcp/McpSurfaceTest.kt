package com.plainbase.frameworks.mcp

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.frameworks.protocol.CANONICAL_PROPOSAL_ID
import com.plainbase.frameworks.protocol.ProposeChangeRequest
import com.plainbase.frameworks.protocol.ProposeChangeResponse
import com.plainbase.frameworks.protocol.RestJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.isSuccess
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MCP surface + REST↔MCP parity (WI-7, extended in C4). The original seven tools use the guarded facades +
 * frozen DTOs as REST, so their six read/list/get tools are byte-identical to their REST endpoints for the same
 * fixture/input, and `propose_change` is structural-parity excluding the freshly minted id. A divergence here means
 * the MCP path drifted from REST (an `explicitNulls` slip, a wrong serializer, a wrong surface).
 */
class McpSurfaceTest : FunSpec({
    fun frozenSchema(properties: String, vararg required: String) = ToolSchema(
        properties = Json.parseToJsonElement(properties).jsonObject,
        required = required.toList(),
    )

    // Literal HEAD/P3 input schemas. Compare them with the decoded real listTools wire result, not McpTools constants.
    val pageSchema = frozenSchema(
        """{
            "id":{"type":"string","description":"The canonical-shape page UUID."},
            "root":{"type":"string","description":"Optional root name to disambiguate an id held by more than one root; omit unless a read returned ambiguous_page_id, which lists the candidate roots. Use the names `list` shows on the tree."}
        }""",
        "id",
    )
    val oldSchemas = mapOf(
        "search" to frozenSchema(
            """{
                "q":{"type":"string","description":"The search query (the PB-SEARCH-1 §A1 grammar)."},
                "limit":{"type":"string","description":"Maximum number of hits to return (optional; the server validates the range)."},
                "offset":{"type":"string","description":"Result offset for pagination (optional)."}
            }""",
            "q",
        ),
        "read_page" to pageSchema,
        "get_page_metadata" to pageSchema,
        "validate_links" to pageSchema,
        "propose_change" to frozenSchema(
            """{
                "operation":{"type":"string","enum":["edit","create"],"description":"edit an existing page or create a new one."},
                "root":{"type":"string","description":"Which document directory a CREATE lands in - REQUIRED for a create, there is no default. Optional for an edit as a disambiguation pin: name it when the tool answers ambiguous_page_id, otherwise omit it. Use the names `list` shows on the tree."},
                "page_id":{"type":"string","description":"The page to edit (an edit requires it; a create omits it)."},
                "base_hash":{"type":"string","description":"The sha256:<64-hex> content hash you edited against (an edit requires it)."},
                "target_path":{"type":"string","description":"A create's content-relative path (required for create); optional for an edit."},
                "proposed_content":{"type":"string","description":"The full UTF-8 markdown source of the page after your edit (frontmatter header + body)."},
                "rationale":{"type":"string","description":"A short human-readable reason for the change."}
            }""",
            "operation", "proposed_content", "rationale",
        ),
        "list_changes" to frozenSchema("{}"),
        "get_change" to frozenSchema(
            """{"id":{"type":"string","description":"The canonical-shape proposal UUID."}}""",
            "id",
        ),
    )

    fun proposeRequest(pageId: String, baseHash: String, content: String = "---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n") =
        ProposeChangeRequest("edit", pageId = pageId, baseHash = baseHash, proposedContent = content, rationale = "improve")

    fun proposeArgs(pageId: String, baseHash: String, content: String = "---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n") = mapOf(
        "operation" to "edit",
        "page_id" to pageId,
        "base_hash" to baseHash,
        "proposed_content" to content,
        "rationale" to "improve",
    )

    test("the tool surface has the seven existing and four discussion tools") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val tools = client.listTools().tools.associateBy { it.name }
                val names = tools.keys
                names shouldBe setOf(
                    "search", "read_page", "get_page_metadata", "validate_links", "propose_change", "list_changes", "get_change",
                    "list_discussions", "get_discussion", "start_discussion", "add_comment",
                )
                for (absent in listOf(
                    "read_section", "read_file", "approve", "reject", "rebase", "anchor_preview", "edit_comment",
                    "retract_comment", "resolve_discussion", "reopen_discussion", "reattach_discussion", "purge_comment",
                )) {
                    names shouldNotContain absent
                }
                oldSchemas.forEach { (name, expected) ->
                    RestJson.encodeToJsonElement(ToolSchema.serializer(), tools.getValue(name).inputSchema) shouldBe
                        RestJson.encodeToJsonElement(ToolSchema.serializer(), expected)
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

    test("propose parser precedence stays identical through real REST and MCP adapters") {
        val cases = listOf(
            Triple(
                mapOf<String, Any?>(
                    "operation" to "edit",
                    "page_id" to "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
                    "base_hash" to "sha256:${"a".repeat(64)}",
                    "proposed_content" to " ",
                    "rationale" to " ",
                ),
                """
                    {"operation":"edit","page_id":"0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a","base_hash":"sha256:${"a".repeat(64)}","proposed_content":" ","rationale":" "}
                """.trimIndent(),
                "{\"error\":{\"code\":\"invalid_propose_request\",\"message\":\"proposed_content must not be empty\"}}",
            ),
            Triple(
                mapOf<String, Any?>(
                    "operation" to "create",
                    "root" to "ghost",
                    "target_path" to "../escape.md",
                    "proposed_content" to "# New",
                    "rationale" to "because",
                ),
                """
                    {"operation":"create","root":"ghost","target_path":"../escape.md","proposed_content":"# New","rationale":"because"}
                """.trimIndent(),
                "{\"error\":{\"code\":\"invalid_root\",\"message\":\"Unknown root: 'ghost'\"}}",
            ),
        )

        McpHarness().use { harness ->
            for ((mcpArgs, restJson, expected) in cases) {
                val mcp = harness.session(harness.proposeBearer) { client ->
                    client.call("propose_change", mcpArgs)
                }
                val rest = harness.restPost("/api/v1/changes", harness.proposeBearer, restJson)
                mcp.isErr() shouldBe true
                mcp.text() shouldBe expected
                rest shouldBe expected
            }
        }
    }

    test("excluded create proposals keep the invalid-request code with an accurate REST and MCP message") {
        val policy = ContentPathPolicy.create(
            fileEligibility = { it.value == "doc.md" },
            traversalEligibility = { true },
            metadataEligibility = { true },
        )
        val expected =
            "{\"error\":{\"code\":\"invalid_propose_request\",\"message\":\"target_path is excluded by the root content policy.\"}}"
        val mcpArgs = mapOf<String, Any?>(
            "operation" to "create",
            "root" to "docs",
            "target_path" to "private/new.md",
            "proposed_content" to "# New",
            "rationale" to "add",
        )
        val restJson =
            """{"operation":"create","root":"docs","target_path":"private/new.md","proposed_content":"# New","rationale":"add"}"""

        McpHarness(primaryPolicy = policy).use { harness ->
            val mcp = harness.session(harness.proposeBearer) { client -> client.call("propose_change", mcpArgs) }
            val rest = harness.restPost("/api/v1/changes", harness.proposeBearer, restJson)

            mcp.isErr() shouldBe true
            mcp.text() shouldBe expected
            rest shouldBe expected
            harness.proposalRows().isEmpty() shouldBe true
        }
    }

    test("DNS-rebinding: an SSE GET with an Origin outside the allowlist does NOT succeed") {
        McpHarness().use { harness ->
            harness.rawMcpGet(bearer = harness.proposeBearer, origin = "http://evil.example.com").status.isSuccess() shouldBe false
        }
    }
})
