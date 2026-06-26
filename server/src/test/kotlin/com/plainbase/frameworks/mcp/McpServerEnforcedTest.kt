package com.plainbase.frameworks.mcp

import com.plainbase.domain.principal.Principal
import com.plainbase.frameworks.ktor.PrincipalExtraction
import com.plainbase.frameworks.ktor.decidePrincipalExtraction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode

/**
 * The enforced-mode MCP auth suite (WI-7, the `ci-runs-auth-off-blind` rule): CI/smoke boot `auth.mode=off`, under
 * which the WHOLE gate (connect auth + facade gate + existence-non-leak) is INVISIBLE. These drive a REAL SSE MCP
 * client against an `enforced = true` server so a gating regression is caught. The connect-reject checks read the
 * pre-upgrade status (no stream); the role/deny checks open an authed stream and exercise the seven tools.
 */
class McpServerEnforcedTest : FunSpec({

    fun proposeArgs(pageId: String, baseHash: String) = mapOf(
        "operation" to "edit",
        "page_id" to pageId,
        "base_hash" to baseHash,
        "proposed_content" to "---\ntitle: Doc\n---\n\n# Doc\n\nedited body.\n",
        "rationale" to "improve the doc",
    )

    test("no bearer → the SSE GET is 401 BEFORE the stream opens") {
        McpHarness().use { harness ->
            harness.rawMcpGet().status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("a pb_-shaped but unknown bearer → 401 (resolves to Anonymous upstream → non-Agent)") {
        McpHarness().use { harness ->
            harness.rawMcpGet(bearer = "pb_0123456789abcdef_${"a".repeat(43)}").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("insecure transport → the agent-only extraction refuses before the secret is touched (the gate maps it to 421)") {
        // A real loopback CIO socket is always a secure context, so the 421 path is asserted at the decidePrincipalExtraction
        // unit level with the SAME agent-only args the gate passes (sessions=null, builtin/proxy disabled): a pb_ bearer over
        // a non-loopback plaintext peer is InsecureTransportRefused, which the gate answers with respondTransportInsecure (421).
        val extraction = decidePrincipalExtraction(
            bearer = "pb_0123456789abcdef_${"a".repeat(43)}",
            cookie = null,
            remoteHost = "8.8.8.8",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = { null },
            builtinAuthEnabled = false,
            proxyAuthEnabled = false,
        )
        extraction shouldBe PrincipalExtraction.InsecureTransportRefused
    }

    test("READ_ONLY agent: the read tools SUCCEED but propose_change is FORBIDDEN") {
        McpHarness().use { harness ->
            harness.session(harness.readOnlyBearer) { client ->
                client.call("search", mapOf("q" to "Doc")).isErr() shouldBe false
                client.call("read_page", mapOf("id" to harness.seedPageId)).isErr() shouldBe false
                client.call("get_page_metadata", mapOf("id" to harness.seedPageId)).isErr() shouldBe false
                client.call("validate_links", mapOf("id" to harness.seedPageId)).isErr() shouldBe false
                client.call("list_changes").isErr() shouldBe false
                val denied = client.call("propose_change", proposeArgs(harness.seedPageId, harness.seedBaseHash))
                denied.isErr() shouldBe true
                denied.text() shouldContain "forbidden"
            }
        }
    }

    test("PROPOSE agent: propose_change SUCCEEDS (a PENDING proposal) and the reads succeed too") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                client.call("read_page", mapOf("id" to harness.seedPageId)).isErr() shouldBe false
                val created = client.call("propose_change", proposeArgs(harness.seedPageId, harness.seedBaseHash))
                created.isErr() shouldBe false
                created.text() shouldContain "PENDING"
            }
        }
    }

    test("existence-non-leak (via revocation): a known and an unknown read both return the IDENTICAL forbidden error") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                client.call("list_changes").isErr() shouldBe false // a successful call BEFORE revocation
                harness.revokeProposeToken()
                val known = client.call("read_page", mapOf("id" to harness.seedPageId))
                val unknown = client.call("read_page", mapOf("id" to "00000000-0000-7000-8000-000000000000"))
                known.isErr() shouldBe true
                // checkRead throws (AccessDenied) BEFORE the null/not_found path, so neither result reveals known-vs-unknown.
                known.text() shouldBe unknown.text()
                known.text() shouldContain "forbidden"
            }
        }
    }

    test("revocation mid-session → the next propose_change is denied (the facade re-reads modeOf live)") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                client.call("list_changes").isErr() shouldBe false
                harness.revokeProposeToken()
                // A live revoked Agent → AccessDenied with a non-Anonymous principal → "forbidden" (the 401-vs-403 nuance).
                val denied = client.call("propose_change", proposeArgs(harness.seedPageId, harness.seedBaseHash))
                denied.isErr() shouldBe true
                denied.text() shouldContain "forbidden"
            }
        }
    }
})
