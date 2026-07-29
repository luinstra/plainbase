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
 * cannot drift from what is mounted. A cross-stack SPA/server parity ledger is deliberately not kept
 * here: it would either couple this server unit to TypeScript source or duplicate a frontend-owned
 * route table that could drift while staying green. Selected deep links are covered at the real browser/
 * server boundary; exhaustive parity, including currently uncovered or future SPA routes, is explicitly
 * UNGATED.
 */
internal object SpaTopLevel {
    val segments = listOf("admin", "new", "review")
    val parameterized = listOf("/review/{id}")
}
