package com.plainbase.frameworks.mcp

import com.plainbase.domain.principal.Principal
import com.plainbase.frameworks.ktor.PrincipalExtraction
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.extractPrincipal
import com.plainbase.frameworks.ktor.routes.respondError
import com.plainbase.frameworks.ktor.routes.respondTransportInsecure
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.DnsRebindingProtection
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.ConcurrentHashMap

/** The frozen P3 MCP mount path — a distinct constant prefix under `/api`, consistent with the §A4 routing matrix. */
const val MCP_PATH: String = "/api/v1/mcp"

/** The connect-time-authenticated agent, stashed by the SSE-GET gate for the per-connection factory to close over. */
internal val McpPrincipalKey: AttributeKey<Principal.Agent> = AttributeKey("plainbase.mcp.principal")

/**
 * Mounts the in-binary MCP server (P3) at [MCP_PATH] inside `routing{}`, BEFORE the docs/static fallthrough.
 *
 * **Why not the SDK's `mcp(Route)` overload (a flagged SDK-vs-HEAD contradiction).** `mcp(Route, path, …)` advertises
 * a HARDCODED-EMPTY message endpoint in the SSE handshake (`KtorServerKt$mcp$2` passes `""` to `mcpSseEndpoint`), and
 * the SDK's `SseClientTransport` resolves an empty/relative endpoint against the PARENT directory of the SSE URL — so
 * for any non-root mount (`/api/v1/mcp`) the POST-back lands on `/api/v1/` and 404s. The only knob that would rescue
 * `mcp(Route)` is app-wide `IgnoreTrailingSlash`, which is forbidden here: the frozen §A4 contract REQUIRES a
 * trailing-slash `/api/v1/pages/{id}/` to 404 (RestErrorContractTest). So we wire the SSE GET + sessionId POST-back
 * ourselves using the SAME SDK primitives `mcp(Route)` uses internally — [SseServerTransport], `Server.createSession`,
 * `handlePostMessage`, and the SDK's [DnsRebindingProtection] plugin — but with an ABSOLUTE message endpoint
 * ([MCP_PATH]), which the client resolves correctly (the `startsWith("/")` branch). This is NOT a protocol
 * reimplementation; it is the route wiring the SDK overload got wrong for sub-path mounts.
 *
 * **Connect-time auth (the crux).** The per-connection factory can't emit a clean 401 once the SSE stream opens, so the
 * SSE GET is gated AT THE ROUTE *before* the upgrade — AGENT-ONLY (`extractPrincipal` with sessions=null +
 * builtin/proxy disabled, so no cookie/proxy human is ever admitted). `InsecureTransportRefused` → 421; Anonymous /
 * non-Agent → 401; both reject before the upgrade. On success the agent is stashed on the call's attributes and the SSE
 * handler closes it over the seven tool handlers → ONE choke point ([RouteContext.read]/[RouteContext.proposals]). The
 * gate is method-discriminated to fire on the SSE GET ONLY — the sessionId-bound POST-back (an unguessable v4 UUID
 * minted only after the SSE authenticated) carries no bearer and MUST NOT be 401'd. CSRF-exempt (bearer, no cookie).
 */
fun Route.plainbaseMcp(ctx: RouteContext) {
    // One transport per open SSE stream, keyed by the SseServerTransport's unguessable random v4 sessionId (the
    // capability the POST-back presents). CIO + the transport own the connection lifecycle; we only track the map.
    val transports = ConcurrentHashMap<String, SseServerTransport>()
    route(MCP_PATH) {
        // DNS-rebinding protection (the same plugin `mcp(Route)` installs) over the fail-closed bind-host allowlist.
        install(DnsRebindingProtection) {
            allowedHosts = ctx.mcpAllowedHosts
            allowedOrigins = ctx.mcpAllowedOrigins
        }
        // The agent-only connect gate, on the SSE GET ONLY (the POST-back is sessionId-authed). Runs on this route's
        // pipeline before the SSE handler (Plugins precedes Call); stashes the agent for the handler to close over.
        intercept(ApplicationCallPipeline.Plugins) {
            val call = context
            if (call.request.httpMethod != HttpMethod.Get) return@intercept
            when (
                val extraction = call.extractPrincipal(
                    ctx.tokens,
                    ctx.trustedProxyCidrs,
                    sessions = null,
                    builtinAuthEnabled = false,
                    proxyAuthEnabled = false,
                )
            ) {
                PrincipalExtraction.InsecureTransportRefused -> {
                    call.respondTransportInsecure()
                    finish()
                }
                is PrincipalExtraction.Resolved -> {
                    val principal = extraction.principal
                    if (principal is Principal.Agent) {
                        call.attributes.put(McpPrincipalKey, principal)
                    } else {
                        call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Authentication required")
                        finish()
                    }
                }
                is PrincipalExtraction.ProxyIdentityRejected -> {
                    // Unreachable (proxyAuthEnabled=false) — treat as 401 for totality.
                    call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Authentication required")
                    finish()
                }
            }
        }
        // The SSE GET: the gate has authenticated + stashed the agent; build a per-connection Server closing over it,
        // advertise the ABSOLUTE POST-back endpoint, then hold the stream open until the client disconnects.
        sse {
            val principal = call.attributes.getOrNull(McpPrincipalKey)
                ?: throw IllegalStateException("MCP SSE reached without an authenticated agent (gate bypassed)")
            val transport = SseServerTransport(MCP_PATH, this)
            transports[transport.sessionId] = transport
            val server = buildPlainbaseMcpServer(principal, ctx)
            server.onClose { transports.remove(transport.sessionId) }
            try {
                server.createSession(transport) // starts the transport (sends the endpoint event) + runs the session
                awaitCancellation() // keep the SSE stream open until the client disconnects (CIO cancels the coroutine)
            } catch (_: CancellationException) {
                // The client disconnected — CIO cancels this stream coroutine. That is the EXPECTED end of an SSE
                // session, not a failure: swallow it so it never surfaces to the app-level StatusPages catch-all
                // (which would otherwise log `ERROR unhandled error serving /api/v1/mcp`). The connection is already
                // gone, so swallowing is safe even in the rare case CIO wraps a transport error as a cancellation cause.
            } finally {
                transports.remove(transport.sessionId)
            }
        }
        // The sessionId-bound POST-back (capability-by-sessionId; no bearer). Correlate to its open stream and dispatch.
        post {
            val sessionId = call.request.queryParameters["sessionId"]
            val transport = sessionId?.let { transports[it] }
            if (transport == null) {
                call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No active MCP session for the given sessionId")
                return@post
            }
            transport.handlePostMessage(call)
        }
    }
}
