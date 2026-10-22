package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.TransportSecurityPolicy
import com.plainbase.frameworks.ktor.routes.ExtractedPrincipal
import com.plainbase.frameworks.ktor.routes.RECEIVE_BODY_ENTRY_OBSERVER
import com.plainbase.frameworks.ktor.routes.ShortRequestBodyException
import com.plainbase.frameworks.ktor.routes.adminRoute
import com.plainbase.frameworks.ktor.routes.adminTokenRoutes
import com.plainbase.frameworks.ktor.routes.adminUserRoutes
import com.plainbase.frameworks.ktor.routes.apiFallbackRoute
import com.plainbase.frameworks.ktor.routes.assetRoute
import com.plainbase.frameworks.ktor.routes.authRoutes
import com.plainbase.frameworks.ktor.routes.browseRedirectRoute
import com.plainbase.frameworks.ktor.routes.discussionRoutes
import com.plainbase.frameworks.ktor.routes.frontendStaticRoutes
import com.plainbase.frameworks.ktor.routes.healthRoute
import com.plainbase.frameworks.ktor.routes.historyRoutes
import com.plainbase.frameworks.ktor.routes.malformedQueryMessage
import com.plainbase.frameworks.ktor.routes.pageCreateRoutes
import com.plainbase.frameworks.ktor.routes.pageReadRoutes
import com.plainbase.frameworks.ktor.routes.pageRoutes
import com.plainbase.frameworks.ktor.routes.pageWriteRoutes
import com.plainbase.frameworks.ktor.routes.permalinkRoute
import com.plainbase.frameworks.ktor.routes.previewRoute
import com.plainbase.frameworks.ktor.routes.principalOrRefuseToShell
import com.plainbase.frameworks.ktor.routes.proposalRoutes
import com.plainbase.frameworks.ktor.routes.readerContentRoutes
import com.plainbase.frameworks.ktor.routes.readerSessionRoute
import com.plainbase.frameworks.ktor.routes.respondError
import com.plainbase.frameworks.ktor.routes.respondRedirectPreservingQuery
import com.plainbase.frameworks.ktor.routes.rootContentRoutes
import com.plainbase.frameworks.ktor.routes.searchRoute
import com.plainbase.frameworks.ktor.routes.sessionRoutes
import com.plainbase.frameworks.ktor.routes.setupRoutes
import com.plainbase.frameworks.ktor.routes.spaShellRoutes
import com.plainbase.frameworks.ktor.routes.themeRoutes
import com.plainbase.frameworks.ktor.routes.treeRoute
import com.plainbase.frameworks.lifecycle.CompletionWait
import com.plainbase.frameworks.mcp.plainbaseMcp
import com.plainbase.frameworks.protocol.ErrorBody
import com.plainbase.frameworks.protocol.ErrorCodes
import com.plainbase.frameworks.protocol.ErrorEnvelope
import com.plainbase.frameworks.protocol.RestJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Ktor on the CIO engine — the only engine Plainbase will ever use
 * (pure-Kotlin coroutines, native-image friendly; Netty is banned, §3).
 */
@Suppress("TooGenericExceptionCaught")
class KtorServer(
    config: PlainbaseConfig,
    routeContext: RouteContext,
) {

    private val admission = HttpCallAdmission()
    private val stopStarted = AtomicBoolean(false)
    private val stopCompleted = CountDownLatch(1)
    private val stopWorkerCompleted = CountDownLatch(1)
    private val stopWorker = AtomicReference<Thread?>(null)
    private val stopWorkerFailure = AtomicReference<Throwable?>(null)
    private val nextReceiveObservation = AtomicReference<((ApplicationCall) -> Unit)?>(null)
    private val nextStructuredChild = AtomicReference<((CoroutineScope) -> Unit)?>(null)

    @Volatile
    private var stopWorkerFactory: (Runnable) -> Thread = { task -> Thread(task, STOP_WORKER_NAME) }

    @Volatile
    private var stopWorkerStarter: (Thread) -> Unit = { worker -> worker.start() }

    @Volatile
    private var stopFailure: Throwable? = null

    @Volatile
    private var beforeEngineStopOperation: () -> Unit = {}

    @Volatile
    private var engineStopOperation: () -> Unit = ::stopEngine

    // Held rather than discarded, so shutdown can STOP it: on SIGTERM the teardown must drain in-flight
    // requests instead of severing them mid-write. Built here as a `val` (the engine binds at start(), not
    // at construction), which also publishes it safely to the shutdown-hook thread with no mutable state.
    private val engine = embeddedServer(
        CIO,
        serverConfig {
            // CIO's accept job otherwise sends startup failures (such as BindException) to the global uncaught
            // handler, while start() also throws them. Keep coroutine failures visible in this engine's error log.
            parentCoroutineContext = CoroutineExceptionHandler { _, failure ->
                logger.error(failure) { "HTTP engine coroutine failed" }
            }
            module {
                monitor.subscribe(ApplicationStopPreparing) {
                    admission.closeAdmission()
                }
                // Setup is before plugins, authentication and routing. Closed admission therefore has no collaborator or
                // business-code path to resolve, including on an already-accepted keep-alive connection.
                intercept(ApplicationCallPipeline.Setup) {
                    if (!admission.tryAdmit(context)) {
                        context.response.header(HttpHeaders.Connection, "close")
                        context.respondText(
                            RestJson.encodeToString(
                                ErrorEnvelope.serializer(),
                                ErrorEnvelope(ErrorBody(ErrorCodes.SERVER_SHUTTING_DOWN, "Server is shutting down")),
                            ),
                            ContentType.Application.Json,
                            HttpStatusCode.ServiceUnavailable,
                        )
                        finish()
                    } else {
                        nextReceiveObservation.getAndSet(null)?.let { observer ->
                            context.attributes.put(RECEIVE_BODY_ENTRY_OBSERVER, observer)
                        }
                        nextStructuredChild.getAndSet(null)?.invoke(context)
                    }
                }
                plainbaseModule(routeContext, secureCookie = TransportSecurityPolicy.derive(config).secureCookie)
            }
        },
        configure = {
            connector {
                host = config.host
                port = config.port
            }
        },
    ).apply {
        engineConfig.shutdownGracePeriod = STOP_GRACE_MILLIS
        engineConfig.shutdownTimeout = STOP_TIMEOUT_MILLIS
    }

    fun start(wait: Boolean) {
        engine.start(wait = wait)
    }

    /** Stops the engine on one owned worker, then drains the admitted call barrier. */
    fun stop() {
        val launchOwner = stopStarted.compareAndSet(false, true)
        CompletionWait.run {
            if (launchOwner) {
                admission.closeAdmission()
                launchAndAwaitStop(this)
            }
            awaitForever(
                await = { stopCompleted.await(it, TimeUnit.MILLISECONDS) },
                completed = { stopCompleted.count == 0L },
            )
        }
        stopFailure?.let { throw it }
    }

    private fun launchAndAwaitStop(wait: CompletionWait) {
        var launchPrimary: Throwable? = null
        var retainedLaunchFailureCount = 0

        fun retainLaunchFailure(failure: Throwable) {
            if (launchPrimary == null) {
                launchPrimary = failure
                retainedLaunchFailureCount = 1
                logger.warn(failure) { "HTTP stop worker launch failed; retaining shutdown ownership for retry" }
            } else if (
                failure !== launchPrimary &&
                requireNotNull(launchPrimary).suppressed.none { it === failure } &&
                retainedLaunchFailureCount < MAX_RETAINED_LAUNCH_FAILURES
            ) {
                requireNotNull(launchPrimary).addSuppressed(failure)
                retainedLaunchFailureCount++
            }
        }

        var worker: Thread? = null
        while (worker == null) {
            val candidate = try {
                stopWorkerFactory(Runnable(::runStop))
            } catch (failure: Throwable) {
                retainLaunchFailure(failure)
                awaitLaunchRetry(wait)
                continue
            }
            stopWorker.set(candidate)
            try {
                candidate.isDaemon = false
                stopWorkerStarter(candidate)
                if (candidate.state == Thread.State.NEW) {
                    throw IllegalStateException("HTTP stop worker launcher returned without starting its worker")
                }
                worker = candidate
            } catch (failure: Throwable) {
                if (candidate.state == Thread.State.NEW) {
                    stopWorker.compareAndSet(candidate, null)
                    retainLaunchFailure(failure)
                    awaitLaunchRetry(wait)
                } else {
                    retainLaunchFailure(failure)
                    worker = candidate
                }
            }
        }

        val startedWorker = requireNotNull(worker)
        wait.awaitForever(
            await = { stopWorkerCompleted.await(it, TimeUnit.MILLISECONDS) },
            completed = { stopWorkerCompleted.count == 0L },
        )
        wait.awaitForever(
            await = { startedWorker.join(it) },
            completed = { !startedWorker.isAlive },
        )
        stopFailure = combineFailures(launchPrimary, stopWorkerFailure.get())
        stopCompleted.countDown()
    }

    private fun awaitLaunchRetry(wait: CompletionWait) {
        wait.awaitUntil(
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LAUNCH_RETRY_MILLIS),
            await = { remainingNanos -> TimeUnit.NANOSECONDS.sleep(remainingNanos) },
            completed = { false },
        )
    }

    private fun combineFailures(primary: Throwable?, secondary: Throwable?): Throwable? {
        if (primary == null) return secondary
        if (secondary != null && secondary !== primary && primary.suppressed.none { it === secondary }) {
            primary.addSuppressed(secondary)
        }
        return primary
    }

    private fun runStop() {
        var primary: Throwable? = null
        fun retain(failure: Throwable) {
            primary = combineFailures(primary, failure)
        }
        try {
            admission.closeAdmission()
            try {
                beforeEngineStopOperation()
            } catch (failure: Throwable) {
                retain(failure)
            }
            try {
                engineStopOperation()
            } catch (failure: Throwable) {
                retain(failure)
            }
            try {
                admission.awaitFinalCalls()
            } catch (failure: Throwable) {
                retain(failure)
            }
        } catch (failure: Throwable) {
            retain(failure)
        } finally {
            stopWorkerFailure.set(primary)
            stopWorkerCompleted.countDown()
        }
    }

    internal fun closeAdmission() = admission.closeAdmission()

    internal fun captureNextAdmissionForTest(observer: (HttpCallAdmission.CallJobObservation) -> Unit) {
        admission.captureNextObservationForTest(observer)
    }

    internal fun captureNextReceiveForTest(observer: (ApplicationCall) -> Unit) {
        check(nextReceiveObservation.compareAndSet(null, observer)) { "a receive observation is already armed" }
    }

    internal fun launchNextStructuredChildForTest(factory: (CoroutineScope) -> Unit) {
        check(nextStructuredChild.compareAndSet(null, factory)) { "a structured-child observation is already armed" }
    }

    /** The normal path is a no-op; H1 uses this narrow gap to race a new request against closed admission. */
    internal fun beforeEngineStopForTest(operation: () -> Unit) {
        beforeEngineStopOperation = operation
    }

    /** Injects a failure after real engine cancellation, independently from [beforeEngineStopForTest]. */
    internal fun failAfterEngineStopForTest(failure: Throwable) {
        engineStopOperation = {
            stopEngine()
            throw failure
        }
    }

    internal fun configureStopWorkerForTest(
        factory: (Runnable) -> Thread = { task -> Thread(task, STOP_WORKER_NAME) },
        starter: (Thread) -> Unit = { worker -> worker.start() },
    ) {
        check(!stopStarted.get()) { "stop worker configuration must precede stop" }
        stopWorkerFactory = factory
        stopWorkerStarter = starter
    }

    internal fun stopWorkerForTest(): Thread? = stopWorker.get()

    internal fun admittedCallsForTest(): Int = admission.admittedCountForTest()

    internal fun admissionOpenForTest(): Boolean = admission.isAcceptingForTest()

    private fun stopEngine() {
        engine.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    internal companion object {
        private const val STOP_GRACE_MILLIS = 3_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val LAUNCH_RETRY_MILLIS = 100L
        private const val MAX_RETAINED_LAUNCH_FAILURES = 4
        private const val STOP_WORKER_NAME = "plainbase-http-stop"

        /** Configured CIO wait plus application disposal wait; diagnostic only, not a wall-clock bound. */
        const val STOP_BOUND_MILLIS: Long = STOP_TIMEOUT_MILLIS + STOP_TIMEOUT_MILLIS
    }
}

private val logger = KotlinLogging.logger {}

/**
 * Shared between the real server and `testApplication` tests. The session cookie is installed only in the workspace
 * profile. [secureCookie] mirrors its secure context (ADR-0008): the `pb_session` cookie's `Secure` attribute is true
 * for a non-loopback bind or a loopback bind with a trusted proxy (see [TransportSecurityPolicy]), and false for
 * loopback development without a proxy. Defaults to false.
 */
fun Application.plainbaseModule(ctx: RouteContext, secureCookie: Boolean = false) {
    if (ctx.reader != null) {
        install(AutoHeadResponse)
        install(ReaderCacheControl)
    } else {
        install(Sessions) {
            // Identity serialization keeps the opaque token safe for native images (SessionCookieNativeTest).
            // HttpOnly + Path=/ + SameSite=Lax; Secure mirrors the workspace bind transport (see [secureCookie]).
            cookie<String>(SESSION_COOKIE_NAME) {
                cookie.httpOnly = true
                cookie.path = "/"
                cookie.secure = secureCookie
                cookie.extensions["SameSite"] = "Lax"
                serializer = OpaqueStringSerializer
            }
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
        exception<ShortRequestBodyException> { call, cause ->
            logger.debug(cause) { "rejected short request body ${call.request.local.uri}" }
            call.respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.INVALID_REQUEST_BODY,
                "Request body is shorter than declared Content-Length",
            )
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
        val reader = ctx.reader
        if (reader != null) readerRoutes(ctx, reader) else workspaceRoutes(ctx)
    }
}

private fun Route.workspaceRoutes(ctx: RouteContext) {
    // Routing order: API, assets, permalinks/aliases/browse, public theme namespace, frontend files, then content and shell.
    healthRoute(ctx)
    // Password login, setup and user administration exist only in builtin auth mode. Off mode is loopback
    // development; proxy mode asserts identity through a trusted header. Keeping builtin routes absent in those
    // modes prevents leftover users or sessions from bypassing their identity path. Login, session, setup and reset
    // use the secure-context gate and their own rate-limit, CSRF and single-use-token guards instead of facade
    // checks, which deny Anonymous under enforced policy and would prevent authentication. User administration
    // goes through AdminFacade's checkManage gate.
    if (ctx.builtinAuthEnabled) {
        authRoutes(ctx)
        setupRoutes(ctx)
        adminUserRoutes(ctx)
    }
    // /session is the public, pre-identity CSRF-bootstrap read in builtin (synchronizer token) and proxy
    // (double-submit token) modes. Login, setup and user administration stay builtin-only above.
    if (ctx.builtinAuthEnabled || ctx.proxyAuthEnabled) {
        sessionRoutes(ctx)
        // Token, audit and role management requires manage permission in either auth mode, including for proxy
        // admins. User administration stays builtin-only.
        adminTokenRoutes(ctx)
    }
    pageRoutes(ctx)
    // PUT save coexists with the GETs by method on the same `/api/v1/pages/{id}` path.
    pageWriteRoutes(ctx)
    // POST create uses the collection path `/api/v1/pages`, distinct from the item-path GET/PUT.
    pageCreateRoutes(ctx)
    // PB-PROPOSE-1 proposal contract under `/api/v1/changes`, a distinct constant prefix.
    proposalRoutes(ctx)
    discussionRoutes(ctx)
    // The in-binary MCP server uses SSE on CIO at `/api/v1/mcp` with agent-only connect auth on the SSE GET.
    // Mount its distinct constant prefix before the API fallback to preserve API, fallback, then static ordering.
    plainbaseMcp(ctx)
    // Per-page history/diff reads use `/{id}/history` and `/{id}/diff`, distinct paths from the page GETs.
    historyRoutes(ctx)
    // Read-only preview rendering is private and non-contractual; asset uploads belong to pageWriteRoutes.
    previewRoute(ctx)
    treeRoute(ctx)
    searchRoute(ctx)
    adminRoute(ctx)
    // The /api tailcard loses to every real API route by specificity and beats the static fallback.
    // An unknown API path must return 404 in the envelope, never 200 with the shell.
    apiFallbackRoute()
    assetRoute(ctx)
    permalinkRoute(ctx)
    browseRedirectRoute(ctx)
    themeRoutes(ctx)
    frontendStaticRoutes(ctx)
    rootContentRoutes(ctx)
    spaShellRoutes(ctx)
    primaryRedirect(ctx)
}

/**
 * Only this positive list is mounted for the reader profile. Unmounted paths reach the API fallback, the
 * `/_plainbase/{...}` theme fallback or the root-content 404 without calling a facade or writing an audit row.
 * The live-tree contract checks registration shape only; handler bodies, route interceptors and handlers sharing
 * the same selectors are outside its view.
 * See the READER profile KDoc for the trust boundary.
 */
private fun Route.readerRoutes(ctx: RouteContext, reader: ReaderSurface) {
    healthRoute(ctx)
    readerSessionRoute(reader)
    pageReadRoutes(ctx)
    treeRoute(ctx)
    searchRoute(ctx)
    apiFallbackRoute()
    assetRoute(ctx)
    permalinkRoute(ctx)
    browseRedirectRoute(ctx)
    themeRoutes(ctx)
    frontendStaticRoutes(ctx)
    readerContentRoutes(ctx, reader)
    primaryRedirect(ctx)
}

private fun Route.primaryRedirect(ctx: RouteContext) {
    // The workspace redirect refuses (421) an insecure-transport credential before choosing its destination.
    get("/") root@{
        if (ctx.principalOrRefuseToShell(call) is ExtractedPrincipal.Refused) return@root
        // The query rides through, same as every other redirect on this surface: `/?mode=edit` is a
        // pasted link or a refresh, and dropping the query here would land the SPA in the read view
        // while the alias and browse hops preserve it. One idiom for every hop.
        call.respondRedirectPreservingQuery("/${ctx.primary.value}", permanent = false)
    }
}
