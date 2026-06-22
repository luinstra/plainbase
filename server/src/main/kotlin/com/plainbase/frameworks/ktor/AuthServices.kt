package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.AdminFacade
import com.plainbase.domain.service.LoginService
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.SetupService

/**
 * The A4a auth services the route layer + the extraction seam share, bundled into ONE holder so [RouteContext]
 * carries a single auth field rather than five (and so the cookie-extraction seam + every harness wire the same
 * graph). NONE of these is a raw mutator: [admin] is the `checkManage`-gated [AdminFacade] (the route reaches
 * user/role/session mutators only through it — the choke-point invariant); [session]/[login]/[setup] are the
 * pre-auth/credential services the PUBLIC routes call without any `check*`. [rateLimiter] is the in-memory login
 * throttle.
 */
class AuthServices(
    val session: SessionService,
    val login: LoginService,
    val setup: SetupService,
    val admin: AdminFacade,
    val rateLimiter: LoginRateLimiter,
)
