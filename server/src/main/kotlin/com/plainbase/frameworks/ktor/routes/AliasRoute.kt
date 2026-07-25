package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.ktor.RouteContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The `/docs` surface: the C3 root grammar + alias redirects + the SPA shell, in the §A4
 * routing-matrix order (root split, then alias check, strictly before the shell - one handler, so
 * the order is structural).
 *
 *  - A tail whose first decoded segment is NOT a known registry root is a LEGACY main-relative
 *    path (ADR-0011 D3, unconditional): **301** to `/docs/main/{tail}` (the ORIGINAL raw tail, so
 *    percent-encoding survives; query preserved).
 *  - An alias hit under a known root (move aliases recorded by the indexer + `redirect_from`
 *    registrations) → **301** to the page's CURRENT canonical `/docs/{root}/...` URL - or, when
 *    the target is a path-space collision loser (`url = null`), to its `/p/{root}/{id}` permalink: the
 *    same fallback `/browse` uses, because the permalink IS the loser's one durable URL. One hop
 *    from a canonical-era alias; a LEGACY-prefix hit chains two hops (the legacy 301 above, then
 *    the alias 301 - ADR-0011 D3, accepted).
 *  - Everything else - canonical page URLs, bare `/docs/{root}`, unknown paths, undecodable ones,
 *    and pages under a root that is NOT SERVING - serves the SPA shell (**200**, per the matrix: the
 *    SPA fetches via `by-path` and owns its own not-found AND its own root-outage UI).
 *
 * **`/docs` is the BROWSER surface, and its answer is the shell - a downed root is not an exception.** An HTML
 * navigation to a canonical page URL is a bookmark, a refresh, a link in a chat: answering it with a 503 JSON
 * body renders `{"error":…}` as literal text in the tab, and the SPA's own full-page outage view - which it
 * renders from the tree's `available:false`, needing no 503 at all - becomes unreachable on a cold load. The bare
 * `/docs/{root}` landing URL already serves the shell, so a 503 one segment deeper made the SAME root show the
 * right outage page at one URL and raw JSON at another. The honest 503 belongs on the surfaces the SPA and the
 * agents CONSUME - `by-path`, `pages/{id}`, `/assets`, MCP - and it is untouched there. Here, `RootUnavailable`
 * is contained and the shell is served.
 *
 * A live canonical path always shadows an alias; the indexer's shadow sweep drops such rows at
 * rebuild, and the belt-and-suspenders check here keeps the invariant even mid-rebuild.
 *
 * A3: ONLY the alias-redirect arm is `read`-gated — [com.plainbase.domain.service.ReadFacade
 * .resolveDocsRedirect] returns the target only when there IS a live alias AND the principal may read it; on no
 * alias OR a deny it returns null and we fall through to the PUBLIC SPA-shell arm, so unauthenticated SPA
 * navigation still loads the shell and a denied caller cannot tell an alias exists (no 301 existence-leak). The
 * legacy 301 is PRE-gate and leak-free: the root decision is config topology only (registry names, operator
 * config), never content existence, and fires uniformly for every non-root first segment.
 *
 * An insecure-transport credential is the ONE exception to "fall through to the shell": it is REFUSED (421) via
 * [principalOrRefuseToShell], never silently downgraded to anonymous and served the shell — a credential sent
 * over plaintext must be refused before it is honored, just like every other gated route.
 */
fun Route.docsRoutes(ctx: RouteContext) {
    get("/docs") {
        // The shell is public, but a credential carried over insecure transport is REFUSED (421), never
        // silently downgraded to the anonymous shell — the same secure-context rule as the path arm below.
        if (ctx.principalOrRefuseToShell(call) is ExtractedPrincipal.Refused) return@get
        call.respondSpaShell()
    }
    get("/docs/{path...}") {
        val principal = when (val extracted = ctx.principalOrRefuseToShell(call)) {
            is ExtractedPrincipal.Resolved -> extracted.principal
            ExtractedPrincipal.Refused -> return@get // 421 already sent (insecure-transport credential)
        }
        val raw = call.rawPathAfter("/docs/")
        // An undecodable tail can never name content - no first segment to make a root decision on;
        // serve the shell exactly as before the root grammar existed.
        val path = raw?.let(::decodedTreePath) ?: return@get call.respondSpaShell()
        // The legacy 301 stays OUTSIDE the wrap: it is pure config topology, calls no facade, and can throw nothing.
        val (root, remainder) = splitRootTail(path, ctx.roots)
            ?: return@get call.respondRedirectPreservingQuery("/docs/${RootName.MAIN}/$raw", permanent = true)
        // The alias arm stays wrapped: `guarded {}` is the one facade-exception mapping site, and a handler with no
        // wrap would surface a facade throw as a 500. What it does NOT do here is answer a downed root with JSON -
        // the facade's availability gate fires before the alias lookup for EVERY page URL under the root, so that
        // 503 would be the answer to an ordinary browser navigation. It is contained to the shell instead (see the
        // file doc); the facade keeps its deny->null->shell contract for AccessDenied, so anonymous still gets the
        // shell, and now so does everybody else.
        call.guarded {
            val target = try {
                remainder?.let { ctx.read.resolveDocsRedirect(principal, root, it) }
            } catch (_: RootUnavailable) {
                null // no alias answer is available from a root that is not serving - fall through to the shell
            }
            if (target != null) call.respondRedirectPreservingQuery(target, permanent = true) else call.respondSpaShell()
        }
    }
}
