package com.plainbase.frameworks.ktor

import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.TrailingSlashRouteSelector
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication

/** Registration shape only: handlers sharing selectors and route interceptors are not distinguished. */
internal fun mountedRoutes(root: RoutingNode): Set<String> = root.getAllRoutes().mapTo(linkedSetOf()) { node ->
    val segments = mutableListOf<String>()
    var method = "ANY"
    generateSequence(node) { it.parent }.toList().asReversed().forEach { ancestor ->
        when (val selector = ancestor.selector) {
            is PathSegmentConstantRouteSelector -> segments += selector.value
            is PathSegmentParameterRouteSelector ->
                segments += "${selector.prefix.orEmpty()}{${selector.name}}${selector.suffix.orEmpty()}"
            is PathSegmentOptionalParameterRouteSelector ->
                segments += "${selector.prefix.orEmpty()}{${selector.name}?}${selector.suffix.orEmpty()}"
            is PathSegmentTailcardRouteSelector -> segments += "${selector.prefix}{${selector.name}...}"
            is HttpMethodRouteSelector -> method = selector.method.value
            is RootRouteSelector, TrailingSlashRouteSelector -> Unit
            else -> error("unrecognized route selector ${selector.javaClass.name} under /${segments.joinToString("/")}")
        }
    }
    "$method /${segments.joinToString("/")}"
}

internal fun routeInventory(ctx: RouteContext): Set<String> {
    var routes = emptySet<String>()
    testApplication {
        application { plainbaseModule(ctx) }
        startApplication()
        routes = mountedRoutes(application.routingRoot)
    }
    return routes
}
