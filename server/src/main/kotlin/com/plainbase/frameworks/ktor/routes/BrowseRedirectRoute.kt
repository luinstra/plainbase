package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.DiagramAsset
import com.plainbase.domain.root.ServerTopLevel
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.protocol.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /browse/{root}/{file-path}` (§A4 + C3): the file-path-exact lookup for tools. A tail whose
 * first segment names a known registry root scopes the remainder to that root; any other tail
 * addresses no root and therefore no file → **404**. The file path (e.g. `guides/deploy-guide.md`)
 * is percent-decoded once and NFC-normalized (both via the chunk 1.5 primitives inside
 * [decodedTreePath]) → **302** to the page's current canonical `/{root}/...` URL.
 *
 * A path-space collision loser has no canonical URL; its permalink is the page's one durable URL,
 * so the 302 targets `/p/{root}/{id}` instead — same contract (a redirect to where the page lives now).
 *
 * Lowercase `.mmd` tails are the standalone diagram browser arm: after the root/path grammar is validated, they
 * receive the public SPA shell without a content read. The shell's guarded `/assets` request owns authorization,
 * availability, membership and live bytes. Malformed or unknown diagram addresses receive a 404 shell.
 *
 * A3: the Markdown redirect arm is `read`-gated — its resolve goes through a dedicated guarded facade OPERATION
 * (never a route-side snapshot walk), so the gate fires (401/403) before the 302 and cannot leak page existence.
 * The standalone diagram arm is a public shell lookup: valid rooted diagram addresses return 200, while malformed
 * or unknown diagram addresses return the 404 shell. Diagram source content remains guarded by the `/assets` route.
 * The Markdown arm also availability-gates: a root-blind snapshot walk would happily 302 to a carried-forward page
 * in a root that answers 503 everywhere else. An unavailable root answers 503 through `guarded {}`; anonymous still
 * gets its 401, unchanged.
 */
fun Route.browseRedirectRoute(ctx: RouteContext) {
    get("/${ServerTopLevel.BROWSE}/{path...}") {
        val principal = when (val extracted = ctx.principalOrRefuseToShell(call)) {
            is ExtractedPrincipal.Resolved -> extracted.principal
            ExtractedPrincipal.Refused -> return@get
        }
        val raw = call.rawPathAfter("/${ServerTopLevel.BROWSE}/")
            ?: return@get call.respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.INVALID_PATH,
                "Expected a content file path: /browse/{file-path}",
            )
        val decodedForShape = decodedTreePath(raw)
        val diagramAddress = raw.endsWith(".mmd") || decodedForShape?.let(DiagramAsset::isStandalone) == true
        if (diagramAddress) {
            val decoded = decodedForShape ?: return@get call.respondShellNotFound()
            val (root, path) = splitRootTail(decoded, ctx.roots) ?: return@get call.respondShellNotFound()
            if (path == null || !DiagramAsset.isBrowserAddressable(path)) return@get call.respondShellNotFound()
            return@get call.respondSpaShell()
        }
        call.guarded {
            val decoded = decodedTreePath(raw)
                ?: return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid file path: '$raw'")
            // A first segment naming no registered root names no file: a 404 miss, not a guess at the
            // primary. A BARE known root keeps its 400 - that tail is malformed rather than absent.
            val (root, path) = splitRootTail(decoded, ctx.roots)
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such page file: ${decoded.value}")
            if (path == null) {
                return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid file path: '$raw'")
            }
            val target = ctx.read.browseTarget(principal, root, path)
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such page file: ${path.value}")
            call.respondRedirectPreservingQuery(target, permanent = false)
        }
    }
}
