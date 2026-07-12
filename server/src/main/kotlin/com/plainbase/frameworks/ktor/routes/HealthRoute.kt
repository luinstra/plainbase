package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.RouteContext
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * The `/healthz` liveness payload: a fixed `ok` status, the running server version, and the per-root serving state.
 *
 * [status] stays `"ok"` even when a root is down, and that is deliberate: this is a LIVENESS probe, and a vanished
 * EXTRA root must not flip a k8s probe into a restart loop — a restart cannot remount a disk, and killing a server
 * that is still serving every OTHER root makes the outage worse. The per-root detail is what an operator (or an
 * alert) reads instead.
 */
@Serializable
data class HealthStatus(val status: String, val version: String, val roots: List<RootHealth>)

/**
 * One CONFIGURED root's serving state, in registry (ADR-0011 D7) order.
 *
 * [reason] is the FIXED cause vocabulary (`missing_at_boot` | `vanished` | `watcher_failed`), never free text: this
 * endpoint is UNAUTHENTICATED, so paths and exception messages stay in the logs. Root NAMES are already public
 * topology (the URL grammar puts them in every `/docs/{root}/...`), and the availability bit is the same exposure
 * class — accepted, on the record.
 *
 * Unavailability is STICKY UNTIL RESTART: a root whose path comes back stays `available: false` here, because a
 * vanished root's scan and identity state cannot be trusted afterwards. Restore the path, THEN restart the server.
 *
 * A DETACHED root — one whose name has left `roots {}` while its rows remain — does not appear here AT ALL: health
 * reports the configured topology's liveness, and a detached root's visibility is the boot WARN that already ships.
 */
@Serializable
data class RootHealth(val root: String, val available: Boolean, val reason: String? = null)

/** Registers the unauthenticated `GET /healthz` liveness probe. */
fun Route.healthRoute(ctx: RouteContext) {
    get("/healthz") {
        val unavailable = ctx.availability.current().unavailable
        call.respond(
            HealthStatus(
                status = "ok",
                version = PlainbaseConfig.VERSION,
                roots = ctx.registry.roots.map { root ->
                    val down = unavailable[root.name]
                    RootHealth(root = root.name.value, available = down == null, reason = down?.cause?.name?.lowercase())
                },
            ),
        )
    }
}
