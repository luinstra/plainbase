package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.routes.adminRoute
import com.plainbase.frameworks.ktor.routes.adminTokenRoutes
import com.plainbase.frameworks.ktor.routes.adminUserRoutes
import com.plainbase.frameworks.ktor.routes.apiFallbackRoute
import com.plainbase.frameworks.ktor.routes.assetRoute
import com.plainbase.frameworks.ktor.routes.authRoutes
import com.plainbase.frameworks.ktor.routes.browseRedirectRoute
import com.plainbase.frameworks.ktor.routes.docsRoutes
import com.plainbase.frameworks.ktor.routes.healthRoute
import com.plainbase.frameworks.ktor.routes.historyRoutes
import com.plainbase.frameworks.ktor.routes.malformedQueryMessage
import com.plainbase.frameworks.ktor.routes.pageCreateRoutes
import com.plainbase.frameworks.ktor.routes.pageRoutes
import com.plainbase.frameworks.ktor.routes.pageWriteRoutes
import com.plainbase.frameworks.ktor.routes.permalinkRoute
import com.plainbase.frameworks.ktor.routes.previewRoute
import com.plainbase.frameworks.ktor.routes.proposalRoutes
import com.plainbase.frameworks.ktor.routes.respondError
import com.plainbase.frameworks.ktor.routes.searchRoute
import com.plainbase.frameworks.ktor.routes.sessionRoutes
import com.plainbase.frameworks.ktor.routes.setupRoutes
import com.plainbase.frameworks.ktor.routes.treeRoute
import com.plainbase.frameworks.mcp.plainbaseMcp
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

/**
 * Ktor on the CIO engine — the only engine Plainbase will ever use
 * (pure-Kotlin coroutines, native-image friendly; Netty is banned, §3).
 */
class KtorServer(
    private val config: PlainbaseConfig,
    private val routeContext: RouteContext,
) {

    fun start(wait: Boolean) {
        embeddedServer(CIO, host = config.host, port = config.port) {
            plainbaseModule(routeContext, secureCookie = config.secureCookie())
        }.start(wait = wait)
    }
}

private val logger = KotlinLogging.logger {}

/**
 * Shared between the real server and `testApplication` tests. [secureCookie] mirrors the secure context (ADR-0008,
 * WI-8): the `pb_session` cookie's `Secure` attribute is true whenever the transport is TLS-fronted — a non-loopback
 * bind OR a loopback bind that declares a trusted proxy (the canonical prod deployment, see
 * [PlainbaseConfig.secureCookie]) — and false ONLY on pure loopback-dev with no proxy (a `Secure` cookie would never
 * be sent back over plain http://localhost). Defaults to false (the dev/test loopback default).
 */
fun Application.plainbaseModule(ctx: RouteContext, secureCookie: Boolean = false) {
    install(Sessions) {
        // The opaque session token (§1): a String round-tripped by identity — NO reflection serializer (the
        // native-crash hazard SessionCookieNativeTest proves this avoids). HttpOnly + Path=/ + SameSite=Lax;
        // Secure mirrors the bind transport (see [secureCookie]).
        cookie<String>(SESSION_COOKIE_NAME) {
            cookie.httpOnly = true
            cookie.path = "/"
            cookie.secure = secureCookie
            cookie.extensions["SameSite"] = "Lax"
            serializer = OpaqueStringSerializer
        }
    }
    install(ContentNegotiation) {
        // kotlinx.serialization is the only serializer in the tree (§3). This is the app-wide
        // default; the PB-REST-1 response DTOs encode through the scoped `RestJson` instead
        // (present-null guaranteed there only, §A4).
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    install(StatusPages) {
        // Ktor's ROUTING layer percent-decodes path segments to match routes — BEFORE any handler
        // runs — and wraps a malformed escape (`/assets/%GG`, `/api/v1/pages/by-path/%`) in
        // BadRequestException (RoutingResolveContext catches URLDecodeException and rethrows it).
        // Map it to the same 400 `invalid_path` the routes answer for an undecodable path, in the
        // frozen envelope; without this it fell through to the catch-all as a 500 `internal_error`.
        exception<BadRequestException> { call, cause ->
            logger.debug(cause) { "rejected undecodable request ${call.request.local.uri}" }
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Malformed percent-encoding in request path")
        }
        // The QUERY-STRING decode is NOT covered by that wrapping (ktor#2559): once a route
        // matches, RoutingCall eagerly merges query+path parameters, so a malformed escape in
        // the query (`?q=%`, `?q=100%`) throws a bare URLDecodeException before ANY handler runs
        // — which used to fall to the catch-all as a 500, exactly what §A6's adversarial corpus
        // (lone `%`) bans. A request undecodable as delivered is the client's 400.
        exception<URLDecodeException> { call, cause ->
            logger.debug(cause) { "rejected undecodable query string ${call.request.local.uri}" }
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_QUERY, malformedQueryMessage(call.request.rawQueryParameters))
        }
        // Uncaught failures still answer in the frozen envelope; the code is an append to the
        // §A4 vocabulary (codes are append-only). Details go to the log, never the wire.
        exception<Throwable> { call, cause ->
            logger.error(cause) { "unhandled error serving ${call.request.local.uri}" }
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL_ERROR, "Internal server error")
        }
    }
    // SSE — the in-binary MCP transport (P3). Installed ONCE at module scope (the `mcp(Route)` overload asserts it);
    // it touches NO content negotiation, so the app-wide `json()` above is left untouched.
    install(SSE)
    routing {
        // §A4 routing-matrix order: API → assets → permalinks/aliases/browse → /docs SPA shell →
        // static. Ktor resolves by specificity, and every surface below owns a distinct constant
        // prefix, so registration order and match order agree; the alias-before-shell ordering is
        // structural inside docsRoutes.
        healthRoute()
        // A4a builtin auth surface (WI-7): registered ONLY in auth.mode=builtin. In OFF (loopback dev) and PROXY
        // (A4b asserts identity via a trusted header) there is no password login, so these routes must be ABSENT
        // (404) — leaving them live would let a leftover builtin user/session authenticate as Principal.Human and
        // bypass the proxy/off identity path. login/session/setup/reset call NO facade `check*` (PolicyService
        // denies Anonymous under enforced mode, so routing them through `check*` would make auth impossible); they
        // run the secure-context gate + their own rate-limit/CSRF/single-use-token guards. Admin user CRUD is GATED
        // through the `checkManage`-gated AdminFacade.
        if (ctx.builtinAuthEnabled) {
            authRoutes(ctx)
            setupRoutes(ctx)
            adminUserRoutes(ctx)
        }
        // /session is the CSRF-bootstrap read in BOTH builtin (synchronizer token) and proxy (double-submit token)
        // modes — public, pre-identity (A4b WIDEN). login/setup/admin-user stay builtin-only above.
        if (ctx.builtinAuthEnabled || ctx.proxyAuthEnabled) {
            sessionRoutes(ctx)
            // The token/audit/role management surface is `manage`-gated and mode-INDEPENDENT (a proxy admin needs it
            // too) — registered when EITHER auth mode is active; user CRUD (adminUserRoutes) stays builtin-only.
            adminTokenRoutes(ctx)
        }
        pageRoutes(ctx)
        // PUT save coexists with the GETs by method on the same `/api/v1/pages/{id}` path.
        pageWriteRoutes(ctx)
        // POST create on the collection path `/api/v1/pages` — distinct from the item-path GET/PUT.
        pageCreateRoutes(ctx)
        // PB-PROPOSE-1 (P1a): the agent proposal surface under `/api/v1/changes` (distinct constant prefix).
        proposalRoutes(ctx)
        // P3: the in-binary MCP server (SSE-on-CIO) at `/api/v1/mcp` — agent-only connect auth on the SSE GET. Mounted
        // here (a distinct constant prefix), BEFORE apiFallbackRoute(), so the §A4 "API → fallback → static" order holds.
        plainbaseMcp(ctx)
        // W5 per-page history/diff reads — `/{id}/history` and `/{id}/diff`, distinct paths from the GETs.
        historyRoutes(ctx)
        // W3b read-only preview render (private, non-contractual); the asset upload folds into pageWriteRoutes.
        previewRoute(ctx)
        treeRoute(ctx)
        searchRoute(ctx)
        adminRoute(ctx)
        // Tailcard under /api: loses to every real API route by specificity, beats the static
        // fallback — an unknown API path must 404 in the envelope, never 200 the shell.
        apiFallbackRoute()
        assetRoute(ctx)
        permalinkRoute(ctx)
        browseRedirectRoute(ctx)
        docsRoutes(ctx)
        // Built SPA shell, embedded as static resources by the :server build.
        staticResources("/", "static") {
            default("index.html")
        }
    }
}
