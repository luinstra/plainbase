package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.WatchCoverage
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
 * [reason] is the FIXED cause vocabulary (`missing_at_boot` | `vanished` | `watcher_failed` | `corpus_missing`),
 * never free text: this endpoint is UNAUTHENTICATED, so paths and exception messages stay in the logs. Root NAMES
 * are already public topology (the URL grammar puts them in every `/docs/{root}/...`), and the availability bit is
 * the same exposure class — accepted, on the record.
 *
 * Unavailability is STICKY UNTIL RESTART: a root whose path comes back stays `available: false` here, because a
 * vanished root's scan and identity state cannot be trusted afterwards. Restore the path, THEN restart the server.
 *
 * [coverage] is the OTHER axis, and it is deliberately not an availability cause: `partial` (or absent) says how
 * much of the root's tree its watcher can actually SEE. A root whose watcher could not register a subtree (the
 * inotify watch limit, a `chmod 000` directory) is `available: true` and honest about it — it serves every byte it
 * holds, and its edits converge on a periodic full pass instead of on their own events. NOT sticky: it clears the
 * moment a retry re-registers the tree, with no restart. A root reporting `partial` for long is an operator
 * condition (raise `fs.inotify.max_user_watches`, or fix the permissions), never an outage.
 *
 * [limbo] is the THIRD axis and the newest (C1): how many durable rows this root holds whose pages the last pass did
 * not witness and no absence proof covers. They are neither present nor deleted; each one reads 503
 * `absence_unverified` rather than the 404 that would tell an agent its citations were never real, and NOTHING is
 * deleted for them. A healthy root reports `0`. A non-zero count on an `available: true` root is the signal an
 * operator actually acts on - a failed submount, a half-finished restore, a decoy tree at the mount point - and it
 * SELF-HEALS to zero with no operator action the moment the pages are read again.
 *
 * A DETACHED root — one whose name has left `roots {}` while its rows remain — does not appear here AT ALL: health
 * reports the configured topology's liveness, and a detached root's visibility is the boot WARN that already ships.
 */
@Serializable
data class RootHealth(
    val root: String,
    val available: Boolean,
    val reason: String? = null,
    val coverage: String? = null,
    val limbo: Int = 0,
)

/** Registers the unauthenticated `GET /healthz` liveness probe. */
fun Route.healthRoute(ctx: RouteContext) {
    get("/healthz") {
        // ONE snapshot of each holder for the whole response, never a per-root re-read: a payload that reported
        // half the roots from before a flip and half from after would be a picture of no moment in time.
        val unavailable = ctx.availability.current().unavailable
        val degraded = ctx.convergence.degraded()
        val limbo = ctx.limbo.current()
        call.respond(
            HealthStatus(
                status = "ok",
                version = PlainbaseConfig.VERSION,
                roots = ctx.registry.roots.map { root ->
                    val down = unavailable[root.name]
                    RootHealth(
                        root = root.name.value,
                        available = down == null,
                        reason = down?.cause?.name?.lowercase(),
                        coverage = if (root.name in degraded) WatchCoverage.PARTIAL.name.lowercase() else null,
                        limbo = limbo[root.name]?.size ?: 0,
                    )
                },
            ),
        )
    }
}
