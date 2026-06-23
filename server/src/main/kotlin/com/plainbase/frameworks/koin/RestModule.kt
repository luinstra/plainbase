package com.plainbase.frameworks.koin

import com.plainbase.domain.service.AdminFacade
import com.plainbase.domain.service.LoginService
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.SetupService
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.AuthServices
import com.plainbase.frameworks.ktor.GuardedAdminFacade
import com.plainbase.frameworks.ktor.LoginRateLimiter
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.buildRouteContext
import com.plainbase.frameworks.security.ProxyCsrf
import com.plainbase.frameworks.security.dummyPasswordHash
import com.plainbase.frameworks.security.loadOrCreateProxyCsrfKey
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Wires the chunk-6 REST read path (+ the S4 search read path + the W1 write pipeline) and the A3 choke point:
 * the [PolicyService] + the guarded facades + the [RouteContext] the routing layer receives. Constructor DSL
 * only — no reflection (native-image gate).
 */
val restModule = module {
    single { PageService(indexBuilder = get(), aliasRegistry = get(), citations = get()) }
    single { SearchService(provider = get(), indexBuilder = get()) }
    single {
        WritePipeline(
            contentStore = get(),
            indexBuilder = get(),
            citations = get(),
            frontmatterParser = get(),
            dirtyPages = get(),
            idMap = get(),
            aliasRegistry = get(),
            historyHook = get(),
        )
    }
    single {
        // No `Clock` Koin single exists (ApiTokenService inlines Clock.System — SecurityModule.kt:18); inline it
        // here too, the least-surprising choice. `enforced` is auth-mode-derived: OFF (loopback-dev) opens the
        // choke point; builtin/proxy enforce the role×action matrix.
        PolicyService(
            roles = get(),
            apiTokens = get(),
            audit = get(),
            idProvider = get(),
            clock = Clock.System,
            enforced = get<PlainbaseConfig>().auth.mode != AuthMode.OFF,
        )
    }
    // A4a session/login/setup/admin services. Session id ROTATES on login/change/reset (§5); the TTLs use the
    // SessionService build defaults (idle 7d, absolute 30d) — a config knob is a restart-only future addition.
    single { SessionService(minter = get(), hasher = get(), sessions = get(), clock = Clock.System) }
    single {
        // The dummy PHC is precomputed once (anti-enumeration timing parity, §6); inlined as a constructor arg.
        LoginService(users = get(), passwordHasher = get(), sessions = get(), transactions = get(), dummyHash = dummyPasswordHash(get()))
    }
    single {
        SetupService(
            minter = get(),
            hasher = get(),
            setupTokens = get(),
            users = get(),
            roles = get(),
            sessions = get(),
            passwordHasher = get(),
            idProvider = get(),
            transactions = get(),
            clock = Clock.System,
        )
    }
    single<AdminFacade> {
        GuardedAdminFacade(
            policy = get(),
            users = get(),
            roles = get(),
            setup = get(),
            sessions = get(),
            passwordHasher = get(),
            idProvider = get(),
            transactions = get(),
            clock = Clock.System,
            tokens = get(),
            audit = get(),
        )
    }
    single { LoginRateLimiter() }
    single { AuthServices(session = get(), login = get(), setup = get(), admin = get(), rateLimiter = get()) }
    // The A4b proxy-CSRF server key is SecureRandom-generated + persisted in app_meta on first boot (so issued tokens
    // survive a restart). The single is resolved INSIDE the DataDirLock region (serve() touches it before starting
    // KtorServer), so two processes never race a double-generate (WI-9 fold-in). The key bytes never log.
    single { ProxyCsrf(loadOrCreateProxyCsrfKey(get<PlainbaseDb>())) }
    single<RouteContext> {
        val config = get<PlainbaseConfig>()
        buildRouteContext(
            policy = get(),
            indexBuilder = get(),
            pageService = get(),
            searchService = get(),
            aliasRegistry = get(),
            contentStore = get(),
            writePipeline = get(),
            history = get(),
            idProvider = get(),
            tokens = get(),
            auth = get(),
            trustedProxyCidrs = config.auth.trustedProxyCidrs,
            maxWriteBodyBytes = config.maxWriteBodyBytes,
            maxAssetBytes = config.maxAssetBytes,
            builtinAuthEnabled = config.auth.mode == AuthMode.BUILTIN,
            proxyAuthEnabled = config.auth.mode == AuthMode.PROXY,
            proxySecret = config.auth.proxySecret,
            proxyIdentityHeader = config.auth.proxyIdentityHeader,
            secureCookie = config.secureCookie(),
            proxyCsrf = get(),
        )
    }
}
