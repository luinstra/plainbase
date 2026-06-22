package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /browse/{file-path}` (§A4): the file-path-exact lookup for tools. The tail is the EXACT
 * content-relative FILE path (e.g. `guides/deploy-guide.md`), percent-decoded once and
 * NFC-normalized (both via the chunk 1.5 primitives inside [decodedTreePath]) → **302** to the
 * page's current canonical `/docs/...` URL.
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
            val path = decodedTreePath(raw)
                ?: return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid file path: '$raw'")
            val page = ctx.read.currentSnapshot(principal, path.value).byPath[path]
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such page file: ${path.value}")
            call.respondRedirectPreservingQuery(page.url ?: page.permalink, permanent = false)
        }
    }
}
