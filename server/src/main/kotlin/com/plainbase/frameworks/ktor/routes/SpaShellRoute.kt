package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RouteContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

fun Route.spaShellRoutes(ctx: RouteContext) {
    val shell: suspend RoutingContext.() -> Unit = shell@{
        if (ctx.principalOrRefuseToShell(call) is ExtractedPrincipal.Refused) return@shell
        call.respondSpaShell()
    }
    SpaTopLevel.segments.forEach { get("/$it", shell) }
    SpaTopLevel.parameterized.forEach { get(it, shell) }
}

/**
 * The SPA's own top-level paths, kept separate from the frontend bundle inventory.
 *
 * Only [segments] and [parameterized] are here, and both are READ by the routes below, so the object
 * cannot drift from what is mounted. An `ownedElsewhere` / `all` ledger was drafted alongside them and
 * is deliberately NOT kept: nothing in this commit could read it, and a route-ownership ledger no
 * assertion contradicts is a claim with no falsifier, which is the exact shape this chunk keeps
 * producing. Its intended reader is commit 7's `createAppRouter` parity assertion; it belongs in the
 * commit that can actually pin it, against the router table it is supposed to mirror. Contrast
 * [FrontendBundle.ownedElsewhere], which stays because `FrontendBundleTest`'s H2 audits it against the
 * served tree today.
 */
internal object SpaTopLevel {
    val segments = listOf("admin", "new", "review")
    val parameterized = listOf("/review/{id}")
}
