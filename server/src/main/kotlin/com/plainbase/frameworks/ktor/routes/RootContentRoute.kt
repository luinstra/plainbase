package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.ktor.RouteContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

/**
 * The root-content surface: the C3 root grammar + alias redirects + the SPA shell, in the §A4
 * routing-matrix order (root split, then alias check, strictly before the shell - one handler, so
 * the order is structural). Commit 6 moved this surface from `/docs/{root}/...` to top-level
 * `/{root}/...`; the ARMS below are unchanged except where this doc says otherwise.
 *
 *  - A tail whose first decoded segment is NOT a known registry root addresses no root, and there is
 *    no such thing as a page outside a root: **404** carrying the shell BODY (the address is still a
 *    browser navigation, so the SPA renders the not-found view; the status stays honest).
 *  - An alias hit under a known root (move aliases recorded by the indexer + `redirect_from`
 *    registrations) goes **301** to the page's CURRENT canonical `/{root}/...` URL - or, when the
 *    target is a path-space collision loser (`url = null`), to its `/p/{root}/{id}` permalink: the
 *    same fallback `/browse` uses, because the permalink IS the loser's one durable URL. Always one hop.
 *  - Everything else - canonical page URLs, the bare `/{root}` landing, unknown paths, and pages
 *    under a root that is NOT SERVING - serves the SPA shell (**200**, per the matrix: the SPA
 *    fetches via `by-path` and owns its own not-found AND its own root-outage UI).
 *
 * **CHANGED IN COMMIT 6: an UNDECODABLE tail is now 404-with-shell, where it used to be a 200 shell.**
 * Under the old grammar the constant `/docs` prefix had already proved a root-scoped address before the
 * tail was decoded, so a malformed tail was "a bad path under a known surface" and the SPA could render
 * its not-found view at 200. Top-level, the tail IS the root decision: a tail that will not decode never
 * yields a first segment, so it cannot name a root, which is exactly the no-such-root case one line
 * below it. The two now agree because they are now the same fact, and answering them differently would
 * mean claiming a root was named when nothing was. Pinned by `RootUrlGrammarTest` and by
 * `RootPathDecodingNativeTest`'s encoded-slash and invalid-UTF-8 rows.
 *
 * **This is the BROWSER surface, and its answer is the shell - a downed root is not an exception.** An HTML
 * navigation to a canonical page URL is a bookmark, a refresh, a link in a chat: answering it with a 503 JSON
 * body renders `{"error":…}` as literal text in the tab, and the SPA's own full-page outage view - which it
 * renders from the tree's `available:false`, needing no 503 at all - becomes unreachable on a cold load. The bare
 * `/{root}` landing URL already serves the shell, so a 503 one segment deeper made the SAME root show the
 * right outage page at one URL and raw JSON at another. The honest 503 belongs on the surfaces the SPA and the
 * agents CONSUME - `by-path`, `pages/{id}`, `/assets`, MCP - and it is untouched there. Here, `RootUnavailable`
 * is contained and the shell is served.
 *
 * A live canonical path always shadows an alias; the indexer's shadow sweep drops such rows at
 * rebuild, and the belt-and-suspenders check here keeps the invariant even mid-rebuild.
 *
 * A3: ONLY the alias-redirect arm is `read`-gated - [com.plainbase.domain.service.ReadFacade
 * .resolveRootContentRedirect] returns the target only when there IS a live alias AND the principal may read it; on no
 * alias OR a deny it returns null and we fall through to the PUBLIC SPA-shell arm, so unauthenticated SPA
 * navigation still loads the shell and a denied caller cannot tell an alias exists (no 301 existence-leak). The
 * no-root 404 is PRE-gate and leak-free: the root decision is config topology only (registry names, operator
 * config), never content existence, and fires uniformly for every non-root first segment.
 *
 * An insecure-transport credential is the ONE exception to "fall through to the shell": it is REFUSED (421) via
 * [principalOrRefuseToShell], never silently downgraded to anonymous and served the shell - a credential sent
 * over plaintext must be refused before it is honored, just like every other gated route.
 */
fun Route.rootContentRoutes(ctx: RouteContext) {
    val handler: suspend RoutingContext.() -> Unit = handler@{
        val principal = when (val extracted = ctx.principalOrRefuseToShell(call)) {
            is ExtractedPrincipal.Resolved -> extracted.principal
            ExtractedPrincipal.Refused -> return@handler
        }
        val rawTail = call.rawPathAfter("/") ?: return@handler call.respondShellNotFound()
        val stripped = rawTail.removeSuffix("/")
        val path = decodedTreePath(stripped) ?: return@handler call.respondShellNotFound()
        val (root, remainder) = splitRootTail(path, ctx.roots) ?: return@handler call.respondShellNotFound()
        call.guarded {
            val target = try {
                remainder?.let { ctx.read.resolveRootContentRedirect(principal, root, it) }
            } catch (_: RootUnavailable) {
                null
            }
            if (target != null) call.respondRedirectPreservingQuery(target, permanent = true) else call.respondSpaShell()
        }
    }
    get("/{root}", handler)
    get("/{root}/{path...}", handler)
}
