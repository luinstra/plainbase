package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RestServices
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
 */
fun Route.docsRoutes(services: RestServices) {
    get("/docs") { call.respondSpaShell() }
    get("/docs/{path...}") {
        val path = call.rawPathAfter("/docs/")?.let(::decodedTreePath)
        if (path != null) {
            val snapshot = services.indexBuilder.current
            val target = services.aliasRegistry.find(path)
                .takeIf { path !in snapshot.byUrlPath } // live canonical wins (§A4)
                ?.let { snapshot.byId[it] }
            if (target != null) return@get call.respondRedirectPreservingQuery(target.url ?: target.permalink, permanent = true)
        }
        call.respondSpaShell()
    }
}
