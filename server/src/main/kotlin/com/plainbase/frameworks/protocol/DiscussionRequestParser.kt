package com.plainbase.frameworks.protocol

import com.plainbase.domain.discussion.AnchorLimits
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.SelectionRequest
import com.plainbase.domain.discussion.strictUtf8
import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.DiscussionAnchorRequest
import com.plainbase.domain.service.DiscussionReads
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DiscussionRequestInvalid(val status: Int, val code: String, override val message: String) : RuntimeException(message)

data class DiscussionQuery(val cursor: DiscussionId?, val limit: Int, val state: String?)
data class DiscussionDetailQuery(val cursor: CommentId?, val limit: Int)
data class DiscussionStartRequest(val anchor: DiscussionAnchorRequest, val body: String)

/** The single decoded-value grammar for REST now and MCP arguments later. */
object DiscussionRequestParser {
    private const val MAX_JSON_ENVELOPE_DEPTH = 64
    private const val MAX_COMMENT_PAYLOAD_BYTES = 65_536
    private val hash = Regex("sha256:[0-9a-f]{64}")
    private val decimal = Regex("[0-9]+")
    private val offset = Regex("0|[1-9][0-9]{0,9}")
    private val states = setOf(
        "page_level", "exact", "moved", "ambiguous", "changed", "orphaned", "unavailable", "unreadable", "incomplete",
    )

    fun requireSafeJsonNesting(raw: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in raw) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when (character) {
                    '"' -> quoted = true
                    '{', '[' -> {
                        depth++
                        if (depth > MAX_JSON_ENVELOPE_DEPTH) invalid(ErrorCodes.INVALID_REQUEST_BODY, "JSON nesting limit exceeded")
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
        }
    }

    fun pageId(raw: String): PageId = raw.takeIf(CANONICAL_PAGE_ID::matches)?.let(PageId::of)
        ?: invalid(ErrorCodes.INVALID_PAGE_ID, "Invalid page id")

    fun discussionId(raw: String): DiscussionId = DiscussionId.of(raw)
        ?: invalid(ErrorCodes.INVALID_REQUEST_BODY, "Invalid discussion id")

    fun commentId(raw: String): CommentId = CommentId.of(raw)
        ?: invalid(ErrorCodes.INVALID_REQUEST_BODY, "Invalid comment id")

    fun listQuery(values: Map<String, List<String>>, rootList: Boolean): DiscussionQuery {
        checkDuplicates(values)
        if (!rootList && "state" in values) invalid(ErrorCodes.INVALID_QUERY, "State is only accepted for root lists")
        val state = values["state"]?.singleOrNull()?.also {
            if (it !in states) invalid(ErrorCodes.INVALID_QUERY, "Invalid discussion state")
        }
        val cursor = values["cursor"]?.singleOrNull()?.let {
            DiscussionId.of(it) ?: invalid(ErrorCodes.INVALID_QUERY, "Invalid discussion cursor")
        }
        val maximum = if (rootList) DiscussionReads.ROOT_LIMIT else DiscussionReads.PAGE_LIMIT
        val limit = values["limit"]?.singleOrNull()?.let { parseLimit(it, maximum) } ?: maximum
        return DiscussionQuery(cursor, limit, state)
    }

    fun detailQuery(values: Map<String, List<String>>): DiscussionDetailQuery {
        checkDuplicates(values)
        if ("state" in values) invalid(ErrorCodes.INVALID_QUERY, "State is only accepted for root lists")
        val cursor = values["cursor"]?.singleOrNull()?.let {
            CommentId.of(it) ?: invalid(ErrorCodes.INVALID_QUERY, "Invalid comment cursor")
        }
        val limit = values["limit"]?.singleOrNull()?.let { parseLimit(it, DiscussionReads.DETAIL_LIMIT) }
            ?: DiscussionReads.DETAIL_LIMIT
        return DiscussionDetailQuery(cursor, limit)
    }

    fun rootOnlyQuery(values: Map<String, List<String>>) {
        checkDuplicates(values)
        if (values.keys.any { it == "state" || it == "cursor" || it == "limit" }) {
            invalid(ErrorCodes.INVALID_QUERY, "Unsupported discussion query parameter")
        }
    }

    fun parseLimit(token: String, maximum: Int): Int {
        if (!decimal.matches(token)) invalid(ErrorCodes.INVALID_QUERY, "Limit must be an unsigned decimal integer")
        val value = token.toIntOrNull() ?: invalid(ErrorCodes.INVALID_QUERY, "Limit is out of range")
        if (value !in 1..maximum) invalid(ErrorCodes.INVALID_QUERY, "Limit is out of range")
        return value
    }

    fun start(element: JsonElement): DiscussionStartRequest {
        val fields = fields(element, setOf("anchor", "body"), setOf("anchor", "body"))
        val body = string(fields.getValue("body"))
        val anchor = anchor(fields.getValue("anchor"), quoteOnly = false)
        commentBody(body)
        return DiscussionStartRequest(anchor, body)
    }

    fun body(element: JsonElement): String {
        val fields = fields(element, setOf("body"), setOf("body"))
        return string(fields.getValue("body")).also(::commentBody)
    }

    fun reattach(element: JsonElement): DiscussionAnchorRequest.Quote {
        val fields = fields(element, setOf("anchor"), setOf("anchor"))
        return anchor(fields.getValue("anchor"), quoteOnly = true) as DiscussionAnchorRequest.Quote
    }

    fun preview(element: JsonElement): DiscussionAnchorRequest.Quote =
        anchor(element, quoteOnly = true) as DiscussionAnchorRequest.Quote

    fun empty(element: JsonElement?) {
        if (element != null) fields(element, emptySet(), emptySet())
    }

    fun validUnicode(element: JsonElement) {
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.addLast(element to 0)
        while (pending.isNotEmpty()) {
            val (value, ancestorDepth) = pending.removeLast()
            when (value) {
                is JsonObject -> {
                    if (ancestorDepth >= MAX_JSON_ENVELOPE_DEPTH) {
                        invalid(ErrorCodes.INVALID_REQUEST_BODY, "JSON nesting limit exceeded")
                    }
                    value.forEach { (key, child) ->
                        validUnicode(key)
                        pending.addLast(child to ancestorDepth + 1)
                    }
                }
                is JsonArray -> {
                    if (ancestorDepth >= MAX_JSON_ENVELOPE_DEPTH) {
                        invalid(ErrorCodes.INVALID_REQUEST_BODY, "JSON nesting limit exceeded")
                    }
                    value.forEach { pending.addLast(it to ancestorDepth + 1) }
                }
                is JsonPrimitive -> if (value.isString) validUnicode(value.content)
            }
        }
    }

    private fun validUnicode(value: String) {
        utf8Bytes(value)
    }

    private fun anchor(element: JsonElement, quoteOnly: Boolean): DiscussionAnchorRequest {
        val objectValue = element as? JsonObject ?: invalid(ErrorCodes.INVALID_REQUEST_BODY, "Anchor must be an object")
        val kind = objectValue["kind"]?.let(::string) ?: invalid(ErrorCodes.INVALID_REQUEST_BODY, "Missing anchor kind")
        val allowed = when (kind) {
            "page" -> setOf("kind", "content_hash")
            "quote" -> setOf("kind", "content_hash", "selected_text", "block_start", "block_end")
            else -> invalid(ErrorCodes.INVALID_REQUEST_BODY, "Invalid anchor kind")
        }
        val required = if (kind == "page") allowed else setOf("kind", "content_hash", "selected_text")
        fields(objectValue, allowed, required)
        if (quoteOnly && kind != "quote") invalid(ErrorCodes.INVALID_REQUEST_BODY, "Quote anchor required")
        val contentHash = string(objectValue.getValue("content_hash"))
        if (!hash.matches(contentHash)) invalid(ErrorCodes.INVALID_REQUEST_BODY, "Invalid anchor content hash")
        if (kind == "page") return DiscussionAnchorRequest.Page(contentHash)
        val selected = string(objectValue.getValue("selected_text"))
        val start = objectValue["block_start"]
        val end = objectValue["block_end"]
        if ((start == null) != (end == null)) invalid(ErrorCodes.INVALID_REQUEST_BODY, "Both block offsets are required")
        val selection = if (start == null) {
            SelectionRequest.Agent(selected)
        } else {
            val startValue = offset(start)
            val endValue = offset(checkNotNull(end))
            if (startValue >= endValue) invalid(ErrorCodes.INVALID_REQUEST_BODY, "Block offsets must increase")
            SelectionRequest.Spa(startValue, endValue, selected)
        }
        if (utf8Bytes(selected).size > AnchorLimits.MAX_QUOTE_BYTES) {
            invalid(ErrorCodes.ANCHOR_TOO_LARGE, "Selected text exceeds the quote limit", 422)
        }
        return DiscussionAnchorRequest.Quote(contentHash, selection)
    }

    private fun offset(element: JsonElement): Long {
        val value = element as? JsonPrimitive ?: invalid(ErrorCodes.INVALID_REQUEST_BODY, "Invalid block offset")
        if (value.isString || !offset.matches(value.content)) invalid(ErrorCodes.INVALID_REQUEST_BODY, "Invalid block offset")
        return value.content.toLong()
    }

    private fun commentBody(value: String) {
        val size = utf8Bytes(value).size
        if (value.isBlank()) invalid(ErrorCodes.COMMENT_EMPTY, "Comment must contain text", 422)
        if (size > MAX_COMMENT_PAYLOAD_BYTES) invalid(ErrorCodes.COMMENT_TOO_LARGE, "Comment exceeds the size limit", 422)
    }

    private fun fields(element: JsonElement, allowed: Set<String>, required: Set<String>): JsonObject {
        val value = element as? JsonObject ?: invalid(ErrorCodes.INVALID_REQUEST_BODY, "Request must be a JSON object")
        if (!value.keys.containsAll(required) || !allowed.containsAll(value.keys)) {
            invalid(ErrorCodes.INVALID_REQUEST_BODY, "Invalid request fields")
        }
        return value
    }

    private fun string(element: JsonElement): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid(ErrorCodes.INVALID_REQUEST_BODY, "Expected a JSON string")

    private fun checkDuplicates(values: Map<String, List<String>>) {
        if (values.any { (_, supplied) -> supplied.size != 1 }) invalid(ErrorCodes.INVALID_QUERY, "Duplicate query parameter")
    }

    private fun utf8Bytes(value: String): ByteArray = try {
        strictUtf8(value)
    } catch (_: IllegalArgumentException) {
        invalid(ErrorCodes.INVALID_UTF8, "Request contains invalid Unicode")
    }

    private fun invalid(code: String, message: String, status: Int = 400): Nothing =
        throw DiscussionRequestInvalid(status, code, message)
}
