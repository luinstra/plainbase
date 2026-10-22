package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.AbsenceUnverified
import com.plainbase.domain.service.DocumentResolution
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.ktor.ReaderSurface
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.bundledShellHtml
import com.plainbase.frameworks.protocol.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

/** Reader document navigation and source reads share one guarded resolution. */
fun Route.readerContentRoutes(ctx: RouteContext, reader: ReaderSurface) {
    val document = bundledShellHtml()?.let { ReaderDocument(it, reader.site) }
    val handler: suspend RoutingContext.() -> Unit = handler@{
        call.appendAcceptVary()
        val representation = call.selectPageRepresentation(PageRepresentation.HTML)
        if (representation == PageRepresentation.MARKDOWN) call.markdownCacheHeaders()
        val principal = when (val extracted = ctx.principalOrRefuseToShell(call)) {
            is ExtractedPrincipal.Resolved -> extracted.principal
            ExtractedPrincipal.Refused -> return@handler
        }
        val rawTail = call.rawPathAfter("/") ?: return@handler call.respondShellNotFound()
        val stripped = rawTail.removeSuffix("/")
        val path = decodedTreePath(stripped)
        if (path == null) {
            if (representation == PageRepresentation.MARKDOWN) {
                return@handler call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page at path '$stripped'")
            }
            return@handler call.respondShellNotFound()
        }
        val split = splitRootTail(path, ctx.roots)
        if (split == null) {
            if (representation == PageRepresentation.MARKDOWN) {
                return@handler call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page at path '$stripped'")
            }
            return@handler call.respondShellNotFound()
        }
        val (root, remainder) = split
        call.guarded {
            if (representation == PageRepresentation.MARKDOWN) {
                when (val resolution = ctx.read.document(principal, root, remainder)) {
                    is DocumentResolution.Serve -> call.respondMarkdown(resolution.page.markdown)
                    is DocumentResolution.Redirect -> call.respondMarkdown(resolution.page.markdown)
                    DocumentResolution.FolderListing, DocumentResolution.Miss -> call.respondError(
                        HttpStatusCode.NotFound,
                        ErrorCodes.PAGE_NOT_FOUND,
                        "No page at path ${path.value}",
                    )
                }
            } else {
                val resolution = try {
                    ctx.read.document(principal, root, remainder)
                } catch (_: RootUnavailable) {
                    return@guarded call.respondShellUnavailable()
                } catch (_: AbsenceUnverified) {
                    return@guarded call.respondShellUnavailable()
                }
                when (resolution) {
                    is DocumentResolution.Serve -> {
                        if (document == null) {
                            call.respondSpaShell()
                        } else {
                            call.respondHtmlDocument(document.render(resolution.page, resolution.canonicalUrl), HttpStatusCode.OK)
                        }
                    }
                    DocumentResolution.FolderListing -> call.respondSpaShell()
                    is DocumentResolution.Redirect -> call.respondRedirectPreservingQuery(resolution.location, permanent = true)
                    DocumentResolution.Miss -> call.respondShellNotFound()
                }
            }
        }
    }
    get("/{root}", handler)
    get("/{root}/{path...}", handler)
}

/**
 * Adapts a reader redirect to the document's final landing destination using a separate snapshot read from
 * the permalink or browse lookup. A local reader may publish between these reads; that race is accepted.
 */
internal fun RouteContext.readerDestination(principal: Principal, url: String): String {
    val path = decodedTreePath(url.removePrefix("/")) ?: return url
    val (root, remainder) = splitRootTail(path, roots) ?: return url
    if (remainder == null) return url
    return (read.document(principal, root, remainder) as? DocumentResolution.Redirect)?.location ?: url
}
