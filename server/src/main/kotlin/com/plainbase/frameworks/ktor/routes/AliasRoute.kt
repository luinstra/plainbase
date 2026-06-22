package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RouteContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The `/docs` surface: alias redirects + the SPA shell, in the §A4 routing-matrix order
 * (alias check strictly before the shell — one handler, so the order is structural).
 *
 *  - An alias hit (move aliases recorded by the indexer + `redirect_from` registrations) → **301**
 *    to the page's CURRENT canonical `/docs/...` URL — or, when the target is a path-space
 *    collision loser (`url = null`), to its `/p/{id}` permalink: the same fallback `/browse` uses,
 *    because the permalink IS the loser's one durable URL. One hop by construction: the registry
 *    maps paths to page ids, never to other aliases, so chains collapse on write.
 *  - Everything else — canonical page URLs, unknown paths, even undecodable ones — serves the SPA
 *    shell (**200**, per the matrix: the SPA fetches via `by-path` and owns its own not-found UI).
 *
 * A live canonical path always shadows an alias; the indexer's shadow sweep drops such rows at
 * rebuild, and the belt-and-suspenders check here keeps the invariant even mid-rebuild.
 *
 * A3 (§WI-5): ONLY the alias-redirect arm is `read`-gated — [com.plainbase.domain.service.ReadFacade
 * .resolveDocsRedirect] returns the target only when there IS a live alias AND the principal may read it; on no
 * alias OR a deny it returns null and we fall through to the PUBLIC SPA-shell arm, so unauthenticated SPA
 * navigation still loads the shell and a denied caller cannot tell an alias exists (no 301 existence-leak). The
 * `/docs` shell and the shell fallback need no principal — they are public.
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
        val path = call.rawPathAfter("/docs/")?.let(::decodedTreePath)
        val target = if (path != null) ctx.read.resolveDocsRedirect(principal, path) else null
        if (target != null) return@get call.respondRedirectPreservingQuery(target, permanent = true)
        call.respondSpaShell()
    }
}
