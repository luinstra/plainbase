package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TOKEN_PREFIX
import com.plainbase.domain.root.ServerTopLevel
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.bearerToken
import com.plainbase.frameworks.protocol.DISCUSSION_JSON_ENVELOPE_CAP
import com.plainbase.frameworks.protocol.DiscussionDetailDto
import com.plainbase.frameworks.protocol.DiscussionListDto
import com.plainbase.frameworks.protocol.DiscussionMutationDto
import com.plainbase.frameworks.protocol.DiscussionPreviewDto
import com.plainbase.frameworks.protocol.DiscussionRequestInvalid
import com.plainbase.frameworks.protocol.DiscussionRequestParser
import com.plainbase.frameworks.protocol.ErrorCodes
import com.plainbase.frameworks.protocol.RestJson
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

private const val DISCUSSION_CONTENT_UNREADABLE_RETRY_AFTER_SECONDS = 30

fun Route.discussionRoutes(ctx: RouteContext) {
    val discussions = ctx.discussions ?: return
    route("/${ServerTopLevel.API}/v1") {
        get("/pages/{id}/discussions") {
            call.discussionGet(ctx) { principal ->
                val pin = pinnedRootOrRefuse() ?: return@discussionGet
                val query = DiscussionRequestParser.listQuery(queryValues(), rootList = false)
                val pageId = discussionPageId()
                respondRest(DiscussionListDto.serializer(), discussions.pageList(principal, pageId, pin.root, query.cursor, query.limit))
            }
        }
        post("/pages/{id}/discussions/anchor-preview") {
            call.discussionPost(ctx) { principal, body ->
                val pin = pinnedRootOrRefuse() ?: return@discussionPost
                DiscussionRequestParser.rootOnlyQuery(queryValues())
                val pageId = discussionPageId()
                val anchor = DiscussionRequestParser.preview(requiredBody(body))
                respondRest(DiscussionPreviewDto.serializer(), discussions.preview(principal, pageId, pin.root, anchor))
            }
        }
        post("/pages/{id}/discussions") {
            call.discussionPost(ctx) { principal, body ->
                val pin = pinnedRootOrRefuse() ?: return@discussionPost
                DiscussionRequestParser.rootOnlyQuery(queryValues())
                val pageId = discussionPageId()
                val request = DiscussionRequestParser.start(requiredBody(body))
                respondDiscussionOutcome(discussions.start(principal, pageId, pin.root, request.anchor, request.body), true, true)
            }
        }
        get("/discussions") {
            call.discussionGet(ctx) { principal ->
                val pin = pinnedRootOrRefuse() ?: return@discussionGet
                val root = pin.root ?: throw DiscussionRequestInvalid(400, ErrorCodes.INVALID_ROOT, "A root pin is required")
                val query = DiscussionRequestParser.listQuery(queryValues(), rootList = true)
                respondRest(DiscussionListDto.serializer(), discussions.rootList(principal, root, query.cursor, query.limit, query.state))
            }
        }
        get("/discussions/{id}") {
            call.discussionGet(ctx) { principal ->
                val pin = pinnedRootOrRefuse() ?: return@discussionGet
                val query = DiscussionRequestParser.detailQuery(queryValues())
                respondRest(
                    DiscussionDetailDto.serializer(),
                    discussions.detail(principal, discussionId(), pin.root, query.cursor, query.limit),
                )
            }
        }
        post("/discussions/{id}/comments") {
            call.discussionPost(ctx) { principal, body ->
                val pin = discussionPin() ?: return@discussionPost
                val request = DiscussionRequestParser.body(requiredBody(body))
                respondDiscussionOutcome(discussions.comment(principal, discussionId(), pin.root, request), true, true)
            }
        }
        post("/discussions/{id}/comments/{commentId}/edit") {
            call.discussionPost(ctx) { principal, body ->
                val pin = discussionPin() ?: return@discussionPost
                val request = DiscussionRequestParser.body(requiredBody(body))
                respondDiscussionOutcome(discussions.edit(principal, discussionId(), pin.root, commentId(), request), false, false)
            }
        }
        post("/discussions/{id}/comments/{commentId}/retract") {
            call.discussionPost(ctx, emptyAllowed = true) { principal, body ->
                val pin = discussionPin() ?: return@discussionPost
                DiscussionRequestParser.empty(body)
                respondDiscussionOutcome(discussions.retract(principal, discussionId(), pin.root, commentId()), false, false)
            }
        }
        post("/discussions/{id}/comments/{commentId}/purge") {
            call.discussionPost(ctx, emptyAllowed = true) { principal, body ->
                val pin = discussionPin() ?: return@discussionPost
                DiscussionRequestParser.empty(body)
                respondDiscussionOutcome(discussions.purge(principal, discussionId(), pin.root, commentId()), false, false)
            }
        }
        post("/discussions/{id}/resolve") {
            call.discussionPost(ctx, emptyAllowed = true) { principal, body ->
                val pin = discussionPin() ?: return@discussionPost
                DiscussionRequestParser.empty(body)
                respondDiscussionOutcome(discussions.resolve(principal, discussionId(), pin.root), false, false)
            }
        }
        post("/discussions/{id}/reopen") {
            call.discussionPost(ctx, emptyAllowed = true) { principal, body ->
                val pin = discussionPin() ?: return@discussionPost
                DiscussionRequestParser.empty(body)
                respondDiscussionOutcome(discussions.reopen(principal, discussionId(), pin.root), false, false)
            }
        }
        post("/discussions/{id}/reattach") {
            call.discussionPost(ctx) { principal, body ->
                val pin = discussionPin() ?: return@discussionPost
                val anchor = DiscussionRequestParser.reattach(requiredBody(body))
                respondDiscussionOutcome(discussions.reattach(principal, discussionId(), pin.root, anchor), false, false)
            }
        }
    }
}

private suspend fun RoutingCall.discussionGet(ctx: RouteContext, action: suspend RoutingCall.(Principal) -> Unit) {
    val principal = ctx.principalOrRefuse(this) ?: return
    if (refuseUnresolvedBearer(principal)) return
    guarded {
        try {
            this@discussionGet.action(principal)
        } catch (invalid: DiscussionRequestInvalid) {
            respondError(HttpStatusCode.fromValue(invalid.status), invalid.code, invalid.message)
        }
    }
}

private suspend fun RoutingCall.discussionPost(
    ctx: RouteContext,
    emptyAllowed: Boolean = false,
    action: suspend RoutingCall.(Principal, JsonElement?) -> Unit,
) {
    val principal = ctx.mutatingPrincipalOrRefuse(this) ?: return
    if (refuseUnresolvedBearer(principal)) return
    guarded {
        val mediaType = try {
            request.contentType().withoutParameters()
        } catch (_: BadContentTypeFormatException) {
            null
        }
        if (mediaType != ContentType.Application.Json) {
            respondError(
                HttpStatusCode.UnsupportedMediaType,
                ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                "POST requires Content-Type: application/json",
            )
            return@guarded
        }
        val cap = minOf(ctx.maxWriteBodyBytes, DISCUSSION_JSON_ENVELOPE_CAP)
        val bytes = receiveBodyCapped(cap) ?: return@guarded respondBodyTooLarge(cap)
        try {
            val body = if (bytes.isEmpty() && emptyAllowed) null else parseDiscussionBody(bytes)
            this@discussionPost.action(principal, body)
        } catch (invalid: DiscussionRequestInvalid) {
            respondError(HttpStatusCode.fromValue(invalid.status), invalid.code, invalid.message)
        }
    }
}

private suspend fun ApplicationCall.refuseUnresolvedBearer(principal: Principal): Boolean {
    val bearer = request.bearerToken()
    if (principal != Principal.Anonymous || bearer?.startsWith(TOKEN_PREFIX) != true) return false
    respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Authentication required")
    return true
}

private fun parseDiscussionBody(bytes: ByteArray): JsonElement {
    val text = strictUtf8Decode(bytes) ?: throw DiscussionRequestInvalid(400, ErrorCodes.INVALID_UTF8, "Request is not valid UTF-8")
    DiscussionRequestParser.requireSafeJsonNesting(text)
    val parsed = try {
        RestJson.parseToJsonElement(text)
    } catch (_: SerializationException) {
        throw DiscussionRequestInvalid(400, ErrorCodes.INVALID_REQUEST_BODY, "Malformed JSON request body")
    }
    DiscussionRequestParser.validUnicode(parsed)
    return parsed
}

private fun requiredBody(body: JsonElement?): JsonElement = body
    ?: throw DiscussionRequestInvalid(400, ErrorCodes.INVALID_REQUEST_BODY, "Request body is required")

private fun ApplicationCall.queryValues(): Map<String, List<String>> =
    request.queryParameters.names().associateWith { name -> request.queryParameters.getAll(name).orEmpty() }

private suspend fun ApplicationCall.discussionPin(): PinResolved? {
    val pin = pinnedRootOrRefuse() ?: return null
    DiscussionRequestParser.rootOnlyQuery(queryValues())
    return pin
}

private fun RoutingCall.discussionPageId(): PageId = DiscussionRequestParser.pageId(pathParameters["id"].orEmpty())
private fun RoutingCall.discussionId(): DiscussionId = DiscussionRequestParser.discussionId(pathParameters["id"].orEmpty())
private fun RoutingCall.commentId(): CommentId = DiscussionRequestParser.commentId(pathParameters["commentId"].orEmpty())

private suspend fun ApplicationCall.respondDiscussionOutcome(
    outcome: DiscussionWriteOutcome,
    created: Boolean,
    returnComment: Boolean,
) {
    when (outcome) {
        is DiscussionWriteOutcome.Done -> respondRest(
            DiscussionMutationDto.serializer(),
            DiscussionMutationDto(outcome.id.value, if (returnComment) outcome.commentId?.value else null, outcome.commit),
            if (created) HttpStatusCode.Created else HttpStatusCode.OK,
        )
        is DiscussionWriteOutcome.Refused -> respondDiscussionRefusal(outcome.refusal.status, outcome.refusal.code)
    }
}

internal suspend fun ApplicationCall.respondDiscussionRefusal(status: Int, code: String) {
    if (status == HttpStatusCode.ServiceUnavailable.value && code == ErrorCodes.CONTENT_UNREADABLE) {
        response.header(HttpHeaders.RetryAfter, DISCUSSION_CONTENT_UNREADABLE_RETRY_AFTER_SECONDS.toString())
    }
    val message = if (code == ErrorCodes.CONTENT_UNREADABLE) {
        "Discussion content is temporarily unreadable; retry shortly"
    } else {
        "Discussion request refused: $code"
    }
    respondError(HttpStatusCode.fromValue(status), code, message)
}
