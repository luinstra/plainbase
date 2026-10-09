package com.plainbase.frameworks.mcp

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.frameworks.protocol.DiscussionRequestParser
import com.plainbase.frameworks.protocol.RestJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class MutableAuthClock(start: Instant) : Clock {
    private val current = AtomicReference(start)
    override fun now(): Instant = current.get()
    fun advance() {
        current.updateAndGet { it + 5.seconds }
    }
}

class McpDiscussionTest : FunSpec({
    fun anchor(hash: String) = mapOf("kind" to "page", "content_hash" to hash)
    fun start(page: String, hash: String, root: String? = null): Map<String, Any> =
        mapOf("page_id" to page, "anchor" to anchor(hash), "body" to "  first 😀  ") +
            (root?.let { mapOf("root" to it) } ?: emptyMap())
    fun id(result: String): String = Json.parseToJsonElement(result).jsonObject.getValue("id").jsonPrimitive.content

    test("all four tools honor configured disable without consulting an excluded sync root") {
        McpHarness(discussionsEnabled = false).use { harness ->
            val discussion = DiscussionId.require("01900000-0000-7000-8000-000000000099")
            val comment = CommentId.require("01900000-0000-7000-8000-000000000098")
            val before = harness.discussions.seedDisabledThread(discussion, comment)
            harness.session(harness.proposeBearer) { client ->
                val reads = listOf(
                    client.call("list_discussions", mapOf("root" to "docs")),
                    client.call("list_discussions", mapOf("page_id" to harness.seedPageId)),
                    client.call("get_discussion", mapOf("id" to discussion.value, "root" to "docs")),
                )
                reads.forEach {
                    it.isErr() shouldBe false
                    it.text() shouldContain "disabled_by_config"
                    it.text() shouldContain "\"discussions_available\":false"
                    it.text().contains("Preserved comment") shouldBe false
                }
                client.call("get_discussion", mapOf("id" to discussion.value)).text() shouldContain "discussion_not_found"
                harness.discussions.auditRows().size shouldBe 0
                listOf(
                    client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)),
                    client.call("add_comment", mapOf("id" to discussion.value, "root" to "docs", "body" to "reply")),
                ).forEach {
                    it.isErr() shouldBe true
                    it.text() shouldContain "discussions_disabled"
                }
                harness.discussions.auditRows().map { it.decision } shouldBe listOf("denied", "denied")
                client.call("add_comment", mapOf("id" to discussion.value, "body" to "reply"))
                    .text() shouldContain "discussion_not_found"
                harness.discussions.auditRows().first().decision shouldBe "allowed"
            }
            harness.discussions.storeCalls.get() shouldBe 0
            harness.discussions.threadBytes(discussion) shouldBe before
        }
    }

    test("should advertise bounded closed discussion schemas over listTools") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val tools = client.listTools().tools.associateBy { it.name }
                val list = tools.getValue("list_discussions").inputSchema
                val props = requireNotNull(list.properties)
                props.keys shouldBe setOf("page_id", "root", "state", "cursor", "limit")
                props.getValue("limit").jsonObject.getValue("minimum").jsonPrimitive.content shouldBe "1"
                props.getValue("limit").jsonObject.getValue("maximum").jsonPrimitive.content shouldBe "200"
                list.required shouldBe emptyList()
                tools.getValue("get_discussion").inputSchema.required shouldBe listOf("id")
                tools.getValue("start_discussion").inputSchema.required shouldBe listOf("page_id", "anchor", "body")
                tools.getValue("add_comment").inputSchema.required shouldBe listOf("id", "body")
                val startProps = requireNotNull(tools.getValue("start_discussion").inputSchema.properties)
                val alternatives = startProps.getValue("anchor").jsonObject.getValue("oneOf").jsonArray
                alternatives.size shouldBe 3
                alternatives.forEach { it.jsonObject.getValue("additionalProperties").jsonPrimitive.content shouldBe "false" }
                alternatives[2].jsonObject.getValue("properties").jsonObject.getValue("block_start")
                    .jsonObject.getValue("minimum").jsonPrimitive.content shouldBe "0"
            }
        }
    }

    test("should share list and detail bytes with REST and expose immediate MCP writes") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val started = client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash))
                started.isErr() shouldBe false
                val created = Json.parseToJsonElement(started.text()).jsonObject
                val discussionId = created.getValue("id").jsonPrimitive.content
                created.getValue("comment_id").jsonPrimitive.content shouldBe "01900000-0000-7000-8000-000000000065"
                created.containsKey("commit") shouldBe true

                val pageArgs = mapOf("page_id" to harness.seedPageId, "limit" to 1)
                client.call("list_discussions", pageArgs).text() shouldBe
                    harness.restGet("/api/v1/pages/${harness.seedPageId}/discussions?limit=1", harness.proposeBearer)
                client.call("list_discussions", mapOf("root" to "docs", "limit" to 1)).text() shouldBe
                    harness.restGet("/api/v1/discussions?root=docs&limit=1", harness.proposeBearer)
                client.call("get_discussion", mapOf("id" to discussionId, "limit" to 1)).text() shouldBe
                    harness.restGet("/api/v1/discussions/$discussionId?limit=1", harness.proposeBearer)

                val comment = client.call("add_comment", mapOf("id" to discussionId, "body" to "second 日本語"))
                comment.isErr() shouldBe false
                val commentBody = Json.parseToJsonElement(comment.text()).jsonObject
                commentBody.getValue("id").jsonPrimitive.content shouldBe discussionId
                commentBody.getValue("comment_id").jsonPrimitive.content shouldBe "01900000-0000-7000-8000-000000000066"
                val detail = client.call("get_discussion", mapOf("id" to discussionId, "limit" to 7)).text()
                detail shouldBe harness.restGet("/api/v1/discussions/$discussionId?limit=7", harness.proposeBearer)
                val comments = Json.parseToJsonElement(detail).jsonObject.getValue("comments").jsonArray
                comments.size shouldBe 2
                comments[0].jsonObject.getValue("markdown").jsonPrimitive.content shouldBe "  first 😀  "
                comments[1].jsonObject.getValue("markdown").jsonPrimitive.content shouldBe "second 日本語"
                comments[1].jsonObject.getValue("author").jsonObject.getValue("kind").jsonPrimitive.content shouldBe "agent"
                val firstWindow = client.call("get_discussion", mapOf("id" to discussionId, "limit" to 1)).text()
                val cursor = Json.parseToJsonElement(firstWindow).jsonObject.getValue("next").jsonPrimitive.content
                client.call("get_discussion", mapOf("id" to discussionId, "limit" to 1, "cursor" to cursor)).text() shouldBe
                    harness.restGet("/api/v1/discussions/$discussionId?limit=1&cursor=$cursor", harness.proposeBearer)
                harness.discussions.auditRows().count { it.action == "DISCUSS" } shouldBe 2
            }
        }
    }

    test("should store the last decoded duplicate body through the MCP parser and guarded facade") {
        McpHarness().use { harness ->
            val decoded = RestJson.parseToJsonElement(
                """{"page_id":"${harness.seedPageId}","anchor":{"kind":"page","content_hash":"${harness.seedBaseHash}"},"body":"old","body":"  final 😀  "}""",
            )
            val args = DiscussionRequestParser.mcpStart(DiscussionRequestParser.mcpPreflight(decoded, 524_288))
            val created = harness.discussions.facade.start(
                Principal.Agent(harness.proposeTokenId), args.pageId, args.root, args.anchor, args.body,
            ) as DiscussionWriteOutcome.Done
            harness.session(harness.proposeBearer) { client ->
                val detail = client.call("get_discussion", mapOf("id" to created.id.value)).text()
                Json.parseToJsonElement(detail).jsonObject.getValue("comments").jsonArray[0]
                    .jsonObject.getValue("markdown").jsonPrimitive.content shouldBe "  final 😀  "
            }
        }
    }

    listOf(true, false).forEach { enforced ->
        test("should recheck agent modes for discussion reads and writes with enforced=$enforced") {
            McpHarness(enforced = enforced).use { harness ->
                harness.session(harness.readOnlyBearer) { client ->
                    client.call("list_discussions", mapOf("page_id" to harness.seedPageId)).isErr() shouldBe false
                    client.call("list_discussions", mapOf("root" to "docs")).isErr() shouldBe false
                    client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)).text() shouldContain "forbidden"
                    client.call("add_comment", mapOf("id" to "01900000-0000-7000-8000-000000000099", "body" to "reply"))
                        .text() shouldContain "forbidden"
                }
                for (bearer in listOf(harness.proposeBearer, harness.commitBearer)) {
                    harness.session(bearer) { client ->
                        val started = client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash))
                        started.isErr() shouldBe false
                        val discussionId = id(started.text())
                        client.call("get_discussion", mapOf("id" to discussionId)).isErr() shouldBe false
                        client.call("add_comment", mapOf("id" to discussionId, "body" to "reply")).isErr() shouldBe false
                    }
                }
                harness.discussions.auditRows().count { it.action == "DISCUSS" } shouldBe 6
            }
        }
    }

    test("should refuse revoked sessions without candidates or existence details") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                client.call("list_discussions", mapOf("root" to "docs")).isErr() shouldBe false
                harness.revokeProposeToken()
                val reads = listOf(
                    client.call("list_discussions", mapOf("page_id" to harness.seedPageId)),
                    client.call("get_discussion", mapOf("id" to "01900000-0000-7000-8000-000000000099")),
                )
                val writes = listOf(
                    client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)),
                    client.call("add_comment", mapOf("id" to "01900000-0000-7000-8000-000000000099", "body" to "reply")),
                )
                (reads + writes).forEach {
                    it.isErr() shouldBe true
                    it.text() shouldContain "forbidden"
                    it.text().contains("candidates") shouldBe false
                }
                harness.discussions.auditRows().count { it.action == "DISCUSS" && it.decision == "denied" } shouldBe 2
            }
            harness.rawMcpGet(harness.proposeBearer).status.value shouldBe 401
        }
    }

    test("should cap all four decoded argument objects before facade entry") {
        McpHarness(writeBodyCap = 80).use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val padding = "😀".repeat(40)
                val cases = listOf(
                    "list_discussions" to mapOf("root" to "docs", "extra" to padding),
                    "get_discussion" to mapOf("id" to "bad", "extra" to padding),
                    "start_discussion" to mapOf("page_id" to "bad", "body" to padding),
                    "add_comment" to mapOf("id" to "bad", "body" to padding),
                )
                cases.forEach { (tool, args) ->
                    val result = client.call(tool, args)
                    result.isErr() shouldBe true
                    result.text() shouldContain "body_too_large"
                    result.text() shouldContain "80 byte"
                }
                harness.discussions.auditRows().size shouldBe 0
                harness.discussions.calls.get() shouldBe 0
            }
        }
    }

    test("should reject invalid optional fields and client commit before the facade") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val cases = listOf(
                    Triple("list_discussions", mapOf<String, Any?>("page_id" to null, "root" to "docs"), "invalid_request_body"),
                    Triple("list_discussions", mapOf<String, Any?>("root" to null), "invalid_root"),
                    Triple("get_discussion", mapOf<String, Any?>("id" to null), "invalid_request_body"),
                    Triple("get_discussion", mapOf<String, Any?>("id" to 3), "invalid_request_body"),
                    Triple("start_discussion", start(harness.seedPageId, harness.seedBaseHash) + ("body" to null), "invalid_request_body"),
                    Triple(
                        "start_discussion", start(harness.seedPageId, harness.seedBaseHash) + ("commit" to "bad"),
                        "invalid_request_body",
                    ),
                    Triple("add_comment", mapOf<String, Any?>("id" to "bad", "body" to null), "invalid_request_body"),
                    Triple("add_comment", mapOf<String, Any?>("id" to "bad", "body" to 3), "invalid_request_body"),
                )
                cases.forEach { (tool, args, code) ->
                    val result = client.call(tool, args)
                    result.isErr() shouldBe true
                    result.text() shouldContain code
                }
                harness.discussions.calls.get() shouldBe 0
                harness.discussions.auditRows().size shouldBe 0
            }
        }
    }

    test("should carry authorized page and discussion candidates and honor retry pins") {
        val other = RootName.require("other")
        McpHarness(ambiguousRoots = listOf(RootName.PRIMARY, other)).use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val pageArgs = mapOf("page_id" to harness.seedPageId)
                val ambiguousList = client.call("list_discussions", pageArgs)
                ambiguousList.isErr() shouldBe true
                ambiguousList.text() shouldContain "\"candidates\""
                val pageCandidates = Json.parseToJsonElement(ambiguousList.text()).jsonObject.getValue("candidates").jsonArray
                pageCandidates.map { it.jsonObject.getValue("root").jsonPrimitive.content } shouldBe listOf("docs", "other")
                ambiguousList.text() shouldContain "multiple root candidates"
                val ambiguousStart = client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash))
                ambiguousStart.isErr() shouldBe true
                Json.parseToJsonElement(ambiguousStart.text()).jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "ambiguous_page_id"
                harness.discussions.auditRows().first().resource shouldBe "${harness.seedPageId}/discussions"
                val started = client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash, "docs"))
                started.isErr() shouldBe false
                val discussionId = id(started.text())
                harness.discussions.duplicateTo(DiscussionId.require(discussionId), other)

                val ambiguousGet = client.call("get_discussion", mapOf("id" to discussionId))
                ambiguousGet.isErr() shouldBe true
                val discussionCandidates = Json.parseToJsonElement(ambiguousGet.text()).jsonObject.getValue("candidates").jsonArray
                discussionCandidates.map { it.jsonObject.getValue("root").jsonPrimitive.content } shouldBe listOf("docs", "other")
                val ambiguousComment = client.call("add_comment", mapOf("id" to discussionId, "body" to "reply"))
                ambiguousComment.isErr() shouldBe true
                Json.parseToJsonElement(ambiguousComment.text()).jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "ambiguous_discussion_id"
                harness.discussions.auditRows().first().resource shouldBe "discussion/$discussionId/comment"
                client.call("get_discussion", mapOf("id" to discussionId, "root" to "docs")).isErr() shouldBe false
                client.call("get_discussion", mapOf("id" to discussionId, "root" to "other")).isErr() shouldBe false
                client.call("add_comment", mapOf("id" to discussionId, "root" to "other", "body" to "reply")).isErr() shouldBe false
                harness.discussions.auditRows().first().resource shouldBe "other:discussion/$discussionId/comment"
                harness.revokeProposeToken()
                listOf(
                    client.call("list_discussions", pageArgs),
                    client.call("get_discussion", mapOf("id" to discussionId)),
                    client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)),
                    client.call("add_comment", mapOf("id" to discussionId, "body" to "reply")),
                ).forEach {
                    it.text() shouldContain "forbidden"
                    it.text().contains("candidates") shouldBe false
                }
            }
        }
    }

    test("should defer unregistered root errors until the write gate and hide them after revocation") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val active = client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash, "ghost"))
                active.isErr() shouldBe true
                active.text() shouldContain "invalid_root"
                harness.discussions.auditRows().single().decision shouldBe "allowed"
                harness.revokeProposeToken()
                val revoked = client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash, "ghost"))
                revoked.text() shouldContain "forbidden"
                revoked.text().contains("ghost") shouldBe false
                harness.discussions.auditRows().first().decision shouldBe "denied"
            }
        }
    }

    test("should not offer retry candidates when another claimant cannot be inspected") {
        val other = RootName.require("other")
        McpHarness(ambiguousRoots = listOf(RootName.PRIMARY, other)).use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val discussionId = id(client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash, "docs")).text())
                harness.markUnavailable(other)
                val read = client.call("get_discussion", mapOf("id" to discussionId))
                read.isErr() shouldBe true
                read.text() shouldContain "root_unavailable"
                read.text().contains("candidates") shouldBe false
                val write = client.call("add_comment", mapOf("id" to discussionId, "body" to "reply"))
                write.isErr() shouldBe true
                write.text() shouldContain "root_unavailable"
                write.text().contains("candidates") shouldBe false
                harness.discussions.auditRows().first().resource shouldBe "discussion/$discussionId/comment"
            }
        }
    }

    test("should match REST refusal codes for stale pages and resolved discussions") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val stale = start(harness.seedPageId, "sha256:${"0".repeat(64)}")
                val mcpStale = client.call("start_discussion", stale)
                mcpStale.text() shouldContain "page_changed"
                val restStale = harness.restPost(
                    "/api/v1/pages/${harness.seedPageId}/discussions", harness.proposeBearer,
                    """{"anchor":{"kind":"page","content_hash":"sha256:${"0".repeat(64)}"},"body":"  first 😀  "}""",
                )
                restStale shouldContain "page_changed"
                val quote = mapOf(
                    "kind" to "quote", "content_hash" to harness.seedBaseHash, "selected_text" to "absent quote",
                    "block_start" to 0, "block_end" to 1,
                )
                val mcpAnchor = client.call(
                    "start_discussion", mapOf("page_id" to harness.seedPageId, "anchor" to quote, "body" to "question"),
                )
                val restAnchor = harness.restPost(
                    "/api/v1/pages/${harness.seedPageId}/discussions", harness.proposeBearer,
                    """{"anchor":{"kind":"quote","content_hash":"${harness.seedBaseHash}","selected_text":"absent quote","block_start":0,"block_end":1},"body":"question"}""",
                )
                Json.parseToJsonElement(mcpAnchor.text()).jsonObject.getValue("error").jsonObject.getValue("code") shouldBe
                    Json.parseToJsonElement(restAnchor).jsonObject.getValue("error").jsonObject.getValue("code")
                mcpAnchor.text() shouldContain "invalid_anchor"
                val discussionId = id(client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)).text())
                harness.restPost("/api/v1/discussions/$discussionId/resolve", harness.proposeBearer, "{}")
                val mcpResolved = client.call("add_comment", mapOf("id" to discussionId, "body" to "reply"))
                mcpResolved.isErr() shouldBe true
                val restResolved = harness.restPost(
                    "/api/v1/discussions/$discussionId/comments", harness.proposeBearer, "{\"body\":\"reply\"}",
                )
                Json.parseToJsonElement(mcpResolved.text()).jsonObject.getValue("error").jsonObject.getValue("code") shouldBe
                    Json.parseToJsonElement(restResolved).jsonObject.getValue("error").jsonObject.getValue("code")
            }
        }
    }

    test("should deny all four grammar-valid calls after an open session expires") {
        val clock = MutableAuthClock(Instant.parse("2026-09-28T12:00:00Z"))
        val other = RootName.require("other")
        McpHarness(ambiguousRoots = listOf(RootName.PRIMARY, other), authClock = clock).use { harness ->
            val bearer = harness.mintExpiringBearer(4.seconds)
            harness.session(bearer) { client ->
                client.call("list_discussions", mapOf("root" to "docs")).isErr() shouldBe false
                val discussionId = id(
                    client.call(
                        "start_discussion", start(harness.seedPageId, harness.seedBaseHash, "docs"),
                    ).text(),
                )
                harness.discussions.duplicateTo(DiscussionId.require(discussionId), other)
                clock.advance()
                val results = listOf(
                    client.call("list_discussions", mapOf("page_id" to harness.seedPageId)),
                    client.call("get_discussion", mapOf("id" to discussionId)),
                    client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)),
                    client.call("add_comment", mapOf("id" to discussionId, "body" to "reply")),
                )
                results.forEach {
                    it.isErr() shouldBe true
                    it.text() shouldContain "forbidden"
                    it.text().contains("candidates") shouldBe false
                }
                harness.discussions.auditRows().count { it.action == "DISCUSS" && it.decision == "denied" } shouldBe 2
            }
            harness.rawMcpGet(bearer).status.value shouldBe 401
        }
    }

    test("should keep safe read failures inside an open SSE session") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val discussionId = id(client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)).text())
                harness.discussions.failDetailListing(DiscussionId.require(discussionId))
                val unreadable = client.call("get_discussion", mapOf("id" to discussionId))
                unreadable.isErr() shouldBe true
                unreadable.text() shouldContain "content_unreadable"
                unreadable.text().contains("private disk") shouldBe false
                harness.discussions.failNextDetailUnexpectedly()
                val internal = client.call("get_discussion", mapOf("id" to discussionId))
                internal.isErr() shouldBe true
                internal.text() shouldContain "internal"
                internal.text().contains("private test") shouldBe false
                client.call("list_discussions", mapOf("root" to "docs")).isErr() shouldBe false
            }
        }
    }

    test("should preserve exclusive cursors and advancing empty filtered root pages") {
        McpHarness().use { harness ->
            harness.session(harness.proposeBearer) { client ->
                repeat(5) { client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash)).isErr() shouldBe false }
                val first = client.call("list_discussions", mapOf("root" to "docs", "state" to "exact", "limit" to 1))
                val firstBody = Json.parseToJsonElement(first.text()).jsonObject
                firstBody.getValue("discussions").jsonArray.size shouldBe 0
                val next = firstBody.getValue("next").jsonPrimitive.content
                client.call(
                    "list_discussions",
                    mapOf(
                        "root" to "docs", "state" to "exact", "limit" to 1,
                        "cursor" to next,
                    ),
                ).text() shouldBe
                    harness.restGet("/api/v1/discussions?root=docs&state=exact&limit=1&cursor=$next", harness.proposeBearer)

                val list = client.call("list_discussions", mapOf("page_id" to harness.seedPageId, "limit" to 1))
                val one = Json.parseToJsonElement(list.text()).jsonObject
                one.getValue("discussions").jsonArray.size shouldBe 1
                val listNext = one.getValue("next").jsonPrimitive.content
                client.call(
                    "list_discussions",
                    mapOf(
                        "page_id" to harness.seedPageId, "limit" to 1,
                        "cursor" to listNext,
                    ),
                ).text() shouldBe
                    harness.restGet("/api/v1/pages/${harness.seedPageId}/discussions?limit=1&cursor=$listNext", harness.proposeBearer)
            }
        }
    }

    test("should show disabled reads without opening the discussion store") {
        McpHarness(editable = false).use { harness ->
            harness.session(harness.proposeBearer) { client ->
                val page = client.call("list_discussions", mapOf("page_id" to harness.seedPageId))
                page.isErr() shouldBe false
                Json.parseToJsonElement(page.text()).jsonObject.getValue("reason").jsonPrimitive.content shouldBe "read_only_root"
                val root = client.call("list_discussions", mapOf("root" to "docs"))
                root.isErr() shouldBe false
                val detail = client.call(
                    "get_discussion",
                    mapOf(
                        "id" to "01900000-0000-7000-8000-000000000099",
                        "root" to "docs",
                    ),
                )
                detail.isErr() shouldBe false
                harness.discussions.storeCalls.get() shouldBe 0
                val denied = client.call("start_discussion", start(harness.seedPageId, harness.seedBaseHash))
                denied.text() shouldContain "root_not_editable"
                harness.discussions.auditRows().single().decision shouldBe "denied"
            }
        }
    }
})
