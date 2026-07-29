package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.routes.ExtractedPrincipal
import com.plainbase.frameworks.ktor.routes.adminRoute
import com.plainbase.frameworks.ktor.routes.adminTokenRoutes
import com.plainbase.frameworks.ktor.routes.adminUserRoutes
import com.plainbase.frameworks.ktor.routes.apiFallbackRoute
import com.plainbase.frameworks.ktor.routes.assetRoute
import com.plainbase.frameworks.ktor.routes.authRoutes
import com.plainbase.frameworks.ktor.routes.browseRedirectRoute
import com.plainbase.frameworks.ktor.routes.frontendStaticRoutes
import com.plainbase.frameworks.ktor.routes.healthRoute
import com.plainbase.frameworks.ktor.routes.historyRoutes
import com.plainbase.frameworks.ktor.routes.malformedQueryMessage
import com.plainbase.frameworks.ktor.routes.pageCreateRoutes
import com.plainbase.frameworks.ktor.routes.pageRoutes
import com.plainbase.frameworks.ktor.routes.pageWriteRoutes
import com.plainbase.frameworks.ktor.routes.permalinkRoute
import com.plainbase.frameworks.ktor.routes.previewRoute
import com.plainbase.frameworks.ktor.routes.principalOrRefuseToShell
import com.plainbase.frameworks.ktor.routes.proposalRoutes
import com.plainbase.frameworks.ktor.routes.respondError
import com.plainbase.frameworks.ktor.routes.respondRedirectPreservingQuery
import com.plainbase.frameworks.ktor.routes.rootContentRoutes
import com.plainbase.frameworks.ktor.routes.searchRoute
import com.plainbase.frameworks.ktor.routes.sessionRoutes
import com.plainbase.frameworks.ktor.routes.setupRoutes
import com.plainbase.frameworks.ktor.routes.spaShellRoutes
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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Ktor on the CIO engine — the only engine Plainbase will ever use
 * (pure-Kotlin coroutines, native-image friendly; Netty is banned, §3).
 */
class KtorServer(
    config: PlainbaseConfig,
    routeContext: RouteContext,
) {

    // Held rather than discarded, so shutdown can STOP it: on SIGTERM the teardown must drain in-flight
    // requests instead of severing them mid-write. Built here as a `val` (the engine binds at start(), not
    // at construction), which also publishes it safely to the shutdown-hook thread with no mutable state.
    private val engine = embeddedServer(CIO, host = config.host, port = config.port) {
        plainbaseModule(routeContext, secureCookie = config.secureCookie())
    }

    fun start(wait: Boolean) {
        engine.start(wait = wait)
    }

    /**
     * The bounded graceful stop: refuse new connections, let in-flight requests finish within
     * [STOP_GRACE_MILLIS], then hard-stop at [STOP_TIMEOUT_MILLIS] - a shutdown step must never be the thing
     * that hangs. Also what unblocks a `start(wait = true)`, so the caller's own cleanup can proceed.
     */
    fun stop() {
        engine.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    internal companion object {
        private const val STOP_GRACE_MILLIS = 3_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** What [stop] can honestly take: the drain grace, then the hard stop behind it - the bound the
         *  graceful-shutdown budget counts for the http-server step (see `serve()`). */
        const val STOP_BOUND_MILLIS: Long = STOP_GRACE_MILLIS + STOP_TIMEOUT_MILLIS
    }
}

private val logger = KotlinLogging.logger {}

/**
 * Shared between the real server and `testApplication` tests. [secureCookie] mirrors the secure context (ADR-0008):
 * the `pb_session` cookie's `Secure` attribute is true whenever the transport is TLS-fronted — a non-loopback
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
        //
        // A CANCELLED call is the one case that is NOT a fault: an SSE client (the in-binary MCP
        // transport) that hangs up cancels the serving coroutine, and `plainbase spike` logged
        // `ERROR unhandled error serving /api/v1/mcp` over a PASSING check for exactly that.
        // `McpMount` swallows the cancellation arriving through its own `try`, but a child coroutine
        // the MCP SDK starts lazily cancels on another path and lands here. Only the SEVERITY moves:
        // the envelope still answers, because a handler that responds NOTHING hands the call to
        // Ktor's own error page instead, which is strictly worse than the frozen envelope. The
        // response is moot anyway on a socket nobody is reading; the false ERROR was the real cost,
        // because agents connect and disconnect constantly and it buries genuine failures.
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) {
                // "cancelled", not "client disconnected": a hang-up is the common source, but this type
                // also covers timeouts and shutdown cancellation, and the log should not name a cause
                // it cannot actually distinguish.
                logger.debug { "call cancelled while serving ${call.request.local.uri}" }
            } else {
                logger.error(cause) { "unhandled error serving ${call.request.local.uri}" }
            }
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL_ERROR, "Internal server error")
        }
    }
    // SSE — the in-binary MCP transport (P3). Installed ONCE at module scope (the `mcp(Route)` overload asserts it);
    // it touches NO content negotiation, so the app-wide `json()` above is left untouched.
    install(SSE)
    // C1a: stamp the shell Content-Security-Policy on every text/html response (the SPA shell from every
    // bundle and shell route). Built once from the embedded shell's inline-script hash;
    // skipped (with a warning) when no frontend is bundled. Must precede routing so it sees every respond.
    installShellSecurityHeaders()
    routing {
        // §A4 routing-matrix order: API → assets → permalinks/aliases/browse → root content and shell.
        healthRoute(ctx)
        // A4a builtin auth surface: registered ONLY in auth.mode=builtin. In OFF (loopback dev) and PROXY
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
        // Per-page history/diff reads — `/{id}/history` and `/{id}/diff`, distinct paths from the GETs.
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
        frontendStaticRoutes(ctx)
        rootContentRoutes(ctx)
        spaShellRoutes(ctx)
        // A3's bare arm on the LAST entry point. `RouteSupport`'s A3 contract names redirect arms
        // explicitly: an insecure-transport credential is REFUSED (421), never silently downgraded to
        // anonymous and handed a 302. This arm is new in this commit and was the one entry point that
        // still downgraded, while `/docs`, `/index.html`, `/admin`, `/browse` and `/p` all refused.
        get("/") root@{
            if (ctx.principalOrRefuseToShell(call) is ExtractedPrincipal.Refused) return@root
            // The query rides through, same as every other redirect on this surface: `/?mode=edit` is a
            // pasted link or a refresh, and dropping the query here would land the SPA in the read view
            // while the alias and browse hops preserve it. One idiom for every hop.
            call.respondRedirectPreservingQuery("/${ctx.primary.value}", permanent = false)
        }
    }
}
