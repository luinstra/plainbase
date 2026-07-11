package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /browse/{root}/{file-path}` (§A4 + C3): the file-path-exact lookup for tools. A tail whose
 * first segment names a known registry root scopes the remainder to that root; otherwise the WHOLE
 * tail is a legacy main-relative FILE path and RESOLVES under main directly (D-C3-3: an API
 * surface never pays a redirect hop for legacy input - the 302 target is already canonical). The
 * file path (e.g. `guides/deploy-guide.md`) is percent-decoded once and NFC-normalized (both via
 * the chunk 1.5 primitives inside [decodedTreePath]) → **302** to the page's current canonical
 * `/docs/{root}/...` URL.
 *
 * A path-space collision loser has no canonical URL; its permalink is the page's one durable URL,
 * so the 302 targets `/p/{id}` instead — same contract (a redirect to where the page lives now).
 *
 * A3: `read`-gated — the snapshot resolve goes through the guarded facade, so the gate fires
 * (401/403) BEFORE the resolve and the 302 cannot leak page existence to an unauthorized caller.
 */
fun Route.browseRedirectRoute(ctx: RouteContext) {
    get("/browse/{path...}") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val raw = call.rawPathAfter("/browse/")
                ?: return@guarded call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_PATH,
                    "Expected a content file path: /browse/{file-path}",
                )
            val decoded = decodedTreePath(raw)
                ?: return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid file path: '$raw'")
            val (root, path) = splitRootTail(decoded, ctx.roots) ?: (RootName.MAIN to decoded)
            // A bare known root names no file - the same malformed-input arm as a bare mount point.
            if (path == null) {
                return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid file path: '$raw'")
            }
            val page = ctx.read.currentSnapshot(principal, path.value).byPath[RootedPath(root, path)]
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such page file: ${path.value}")
            call.respondRedirectPreservingQuery(page.url ?: page.permalink, permanent = false)
        }
    }
}
