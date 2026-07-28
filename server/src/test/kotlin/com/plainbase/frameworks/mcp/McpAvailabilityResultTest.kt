package com.plainbase.frameworks.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Agent-facing outage semantics: a temporary unreadable page must never look deleted. */
class McpAvailabilityResultTest : FunSpec({

    test("read_page reports root_unavailable and tells the agent to keep citations when the root is down") {
        McpHarness().use { harness ->
            harness.markMainUnavailable()

            harness.session(harness.readOnlyBearer) { client ->
                val result = client.call("read_page", mapOf("id" to harness.seedPageId))

                result.isErr() shouldBe true
                result.text() shouldContain "root_unavailable"
                result.text() shouldContain "keep your citations"
            }
        }
    }

    test("read_page reports absence_unverified when a live binding is temporarily missing from the snapshot") {
        McpHarness().use { harness ->
            harness.moveSeedPageToLimbo()

            harness.session(harness.readOnlyBearer) { client ->
                val result = client.call("read_page", mapOf("id" to harness.seedPageId))

                result.isErr() shouldBe true
                result.text() shouldContain "absence_unverified"
                result.text() shouldContain "KEEP your citations"
                result.text() shouldContain "do not re-create it"
            }
        }
    }

    test("propose_change maps a root outage inside the outer MCP error boundary") {
        McpHarness().use { harness ->
            harness.markMainUnavailable()

            harness.session(harness.proposeBearer) { client ->
                val result = client.call(
                    "propose_change",
                    mapOf(
                        "operation" to "edit",
                        "page_id" to harness.seedPageId,
                        "root" to "docs",
                        "base_hash" to harness.seedBaseHash,
                        "proposed_content" to "---\ntitle: Doc\n---\n\n# Doc\n\nedited\n",
                        "rationale" to "availability contract",
                    ),
                )

                result.isErr() shouldBe true
                result.text() shouldContain "root_unavailable"
                result.text() shouldContain "Nothing was written"
            }
        }
    }
})
