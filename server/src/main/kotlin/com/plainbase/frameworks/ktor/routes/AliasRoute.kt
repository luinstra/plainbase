package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.root.RootName
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
 *    the target is a path-space collision loser (`url = null`), to its `/p/{id}` permalink: the
 *    same fallback `/browse` uses, because the permalink IS the loser's one durable URL. One hop
 *    from a canonical-era alias; a LEGACY-prefix hit chains two hops (the legacy 301 above, then
 *    the alias 301 - ADR-0011 D3, accepted).
 *  - Everything else - canonical page URLs, bare `/docs/{root}`, unknown paths, even undecodable
 *    ones - serves the SPA shell (**200**, per the matrix: the SPA fetches via `by-path` and owns
 *    its own not-found UI).
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
        // The alias arm IS wrapped, which is what makes `guarded {}` the one 503 mapping site literally rather than
        // by convention: this handler had no wrap at all, so a RootUnavailable would have escaped it as a 500. The
        // facade keeps its deny->null->shell contract for AccessDenied (it swallows it internally), so the wrap's 401
        // arm is simply never reached from here - anonymous still gets the shell - while an AUTHORIZED caller under an
        // unavailable root gets the honest 503 and never the miss-to-shell fallthrough (a 503 must not degrade into
        // "SPA not-found", which is the whole point).
        call.guarded {
            val target = remainder?.let { ctx.read.resolveDocsRedirect(principal, root, it) }
            if (target != null) call.respondRedirectPreservingQuery(target, permanent = true) else call.respondSpaShell()
        }
    }
}
