package com.plainbase.frameworks.mcp

import com.plainbase.domain.root.RootName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MCP read-tool `root` arg (C4, 6h, 5.2a #8). The DELIBERATE MCP-read exception to the auth-defer sweep: an
 * unregistered OR malformed `root` answers `invalid_root` (MCP is connect-authenticated, reads mint no write-audit,
 * root names are public) - where REST `?root=ghost` DEFERS to a post-checkRead 404. A registered pin that holds the
 * id succeeds.
 */
class McpRootPinTest : FunSpec({

    test("read_page root=main (registered, holds it) succeeds; root=ghost|a/b -> invalid_root") {
        McpHarness().use { harness ->
            harness.session(harness.readOnlyBearer) { client ->
                client.call("read_page", mapOf("id" to harness.seedPageId, "root" to "main")).isErr() shouldBe false

                val ghost = client.call("read_page", mapOf("id" to harness.seedPageId, "root" to "ghost"))
                ghost.isErr() shouldBe true
                ghost.text() shouldContain "invalid_root"

                val malformed = client.call("read_page", mapOf("id" to harness.seedPageId, "root" to "a/b"))
                malformed.isErr() shouldBe true
                malformed.text() shouldContain "invalid_root"
            }
        }
    }

    test("get_page_metadata + validate_links honor the same root pin (root=ghost -> invalid_root)") {
        McpHarness().use { harness ->
            harness.session(harness.readOnlyBearer) { client ->
                client.call("get_page_metadata", mapOf("id" to harness.seedPageId, "root" to "ghost")).text() shouldContain "invalid_root"
                client.call("validate_links", mapOf("id" to harness.seedPageId, "root" to "ghost")).text() shouldContain "invalid_root"
            }
        }
    }

    test("a FAKE-ambiguous bare read answers the ambiguous_page_id body with one candidate per holding root") {
        val roots = listOf(RootName.MAIN, RootName.require("notes"))
        McpHarness(ambiguousRoots = roots).use { harness ->
            harness.session(harness.readOnlyBearer) { client ->
                val res = client.call("read_page", mapOf("id" to harness.seedPageId))
                res.isErr() shouldBe true
                // The MCP twin of the REST 409 - and unlike REST's, this body is UNWRAPPED (no `error` envelope: MCP
                // carries its error-ness in isError), keeps a top-level `id`, and repeats it per candidate, since an
                // agent retries by naming a root rather than by following a url. Its own DTO, so its own pin.
                val body = Json.parseToJsonElement(res.text()).jsonObject
                body.getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
                body.getValue("id").jsonPrimitive.content shouldBe harness.seedPageId
                body.getValue("candidates").jsonArray.map { it.jsonObject.getValue("root").jsonPrimitive.content } shouldBe
                    listOf("main", "notes")
                body.getValue("message").jsonPrimitive.content shouldContain "root"

                // Naming a root resolves it: the pin is the documented remedy the error tells the agent to use.
                client.call("read_page", mapOf("id" to harness.seedPageId, "root" to "main")).isErr() shouldBe false
            }
        }
    }

    test("a bare read with a LIVE claimant AND a foreign TOMBSTONE -> ambiguous_page_id with the STATUS-NEUTRAL retired note, no 410") {
        McpHarness(ambiguousRoots = listOf(RootName.MAIN), retiredRoots = listOf(RootName.require("notes"))).use { harness ->
            harness.session(harness.readOnlyBearer) { client ->
                val res = client.call("read_page", mapOf("id" to harness.seedPageId))
                res.isErr() shouldBe true
                val body = Json.parseToJsonElement(res.text()).jsonObject
                body.getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
                body.getValue("id").jsonPrimitive.content shouldBe harness.seedPageId
                // The candidate list is the RANKED UNION of live + tombstone roots (registry order), not just the live one.
                body.getValue("candidates").jsonArray.map { it.jsonObject.getValue("root").jsonPrimitive.content } shouldBe
                    listOf("main", "notes")
                val message = body.getValue("message").jsonPrimitive.content
                // STATUS-NEUTRAL: MCP mirrors REST's 404/stale_base, so the mixed note promises no cross-surface status.
                message shouldContain "some candidate roots have retired this id"
                message shouldNotContain "410"
                // No new JSON key: the body is exactly code/id/candidates/message, the same shape as the pure-ambiguous case.
                body.keys shouldBe setOf("code", "id", "candidates", "message")
            }
        }
    }

    // ---- propose_change's edit pin: grammar pre-auth, durable-validated AFTER checkEdit ----------------------

    test("propose_change edit pin: root=main (holds it) proceeds; root=ghost (non-owner) is stale_base, never a re-resolve") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                fun edit(root: String?) = buildMap<String, Any?> {
                    put("operation", "edit")
                    put("page_id", harness.seedPageId)
                    put("base_hash", harness.seedBaseHash)
                    put("proposed_content", "---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n")
                    put("rationale", "r")
                    if (root != null) put("root", root)
                }
                client.call("propose_change", edit("main")).isErr() shouldBe false
                // `ghost` is a legal slug naming no registered root, so the shared parser lets it through (an EDIT pin
                // is grammar-only pre-auth) and the facade fails it CLOSED: it reads as gone from the pinned root
                // rather than walking the edit into whichever root actually holds the id.
                client.call("propose_change", edit("ghost")).text() shouldContain "stale_base"
                // Malformed is the pre-auth syntax exception on this surface too.
                client.call("propose_change", edit("a/b")).text() shouldContain "invalid_root"
            }
        }
    }

    test("propose_change edit pin: a READ_ONLY agent is denied BEFORE the pin is durable-validated") {
        McpHarness().use { harness ->
            harness.session(harness.readOnlyBearer) { client ->
                // The pin names a root that DOES hold the id, so nothing about it is wrong - the authorization is.
                // A `stale_base`/`invalid_root` here would mean the pin was validated ahead of `checkEdit`, which is
                // the ordering C4 fixed: the auth gate answers first, and it never leaks whether the pin was good.
                val res = client.call(
                    "propose_change",
                    mapOf(
                        "operation" to "edit",
                        "page_id" to harness.seedPageId,
                        "root" to "main",
                        "base_hash" to harness.seedBaseHash,
                        "proposed_content" to "---\ntitle: Doc\n---\n\n# Doc\n\nedited.\n",
                        "rationale" to "r",
                    ),
                )
                res.isErr() shouldBe true
                res.text() shouldContain "forbidden"
            }
        }
    }

    test("REST contrast: ?root=ghost READ is 404 page_not_found (deferred), while MCP root=ghost is invalid_root") {
        McpHarness().use { harness ->
            // MCP: invalid_root (the connect-authed exception).
            harness.session(harness.readOnlyBearer) { client ->
                client.call("read_page", mapOf("id" to harness.seedPageId, "root" to "ghost")).text() shouldContain "invalid_root"
            }
            // REST over the SAME server: 404, the deferred-registration answer.
            harness.restGet("/api/v1/pages/${harness.seedPageId}?root=ghost", harness.readOnlyBearer) shouldContain "page_not_found"
        }
    }
})
