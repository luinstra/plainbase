package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.protocol.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

/**
 * Top-level browser document surface for `/{root}` and `/{root}/{path...}`.
 *
 * HTML/default requests keep the browser contract: known-root landings and document misses serve the SPA shell,
 * readable aliases redirect 301, and root outages remain shell-rendered. An explicit Markdown request is a guarded
 * document read instead: readable pages return indexed source, auth/availability failures return structured JSON, and
 * missing, unknown, or undecodable document paths return JSON 404. Bare root landings remain shells; `/` and `/browse`
 * are separate surfaces.
 *
 * A live canonical path shadows an alias. An insecure-transport credential is refused (421) before either
 * representation is selected.
 */
fun Route.rootContentRoutes(ctx: RouteContext) {
    val handler: suspend RoutingContext.() -> Unit = handler@{
        call.appendAcceptVary()
        val representation = call.selectPageRepresentation(PageRepresentation.HTML)
        val rawTail = call.rawPathAfter("/")
        val hasDocumentTail = rawTail?.let { tail ->
            tail.removeSuffix("/").contains('/') || tail.contains("%2f", ignoreCase = true)
        } == true
        if (representation == PageRepresentation.MARKDOWN && hasDocumentTail) call.markdownCacheHeaders()
        val principal = when (val extracted = ctx.principalOrRefuseToShell(call)) {
            is ExtractedPrincipal.Resolved -> extracted.principal
            ExtractedPrincipal.Refused -> return@handler
        }
        val nonEmptyRawTail = rawTail ?: return@handler call.respondShellNotFound()
        val stripped = nonEmptyRawTail.removeSuffix("/")
        val path = decodedTreePath(stripped)
        if (path == null) {
            if (representation == PageRepresentation.MARKDOWN) {
                call.markdownCacheHeaders()
                return@handler call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page at path '$stripped'")
            }
            return@handler call.respondShellNotFound()
        }
        val split = splitRootTail(path, ctx.roots)
        if (split == null) {
            if (representation == PageRepresentation.MARKDOWN) {
                call.markdownCacheHeaders()
                return@handler call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page at path '$stripped'")
            }
            return@handler call.respondShellNotFound()
        }
        val (root, remainder) = split
        if (remainder == null) return@handler call.respondSpaShell()
        call.guarded {
            if (representation == PageRepresentation.MARKDOWN) {
                val payload = ctx.read.pageByUrlPath(principal, root, remainder)
                    ?: return@guarded call.respondError(
                        HttpStatusCode.NotFound,
                        ErrorCodes.PAGE_NOT_FOUND,
                        "No page at path ${path.value}",
                    )
                call.respondMarkdown(payload.page.markdown)
            } else {
                val target = try {
                    ctx.read.resolveRootContentRedirect(principal, root, remainder)
                } catch (_: RootUnavailable) {
                    null
                }
                if (target != null) call.respondRedirectPreservingQuery(target, permanent = true) else call.respondSpaShell()
            }
        }
    }
    get("/{root}", handler)
    get("/{root}/{path...}", handler)
}
