package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.PolicyService
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.security.ProxyCsrf
import io.ktor.server.application.ApplicationCall

/** The normalized security inputs and the extraction boundary shared by guarded routes. */
internal class SecurityAssembly(
    config: PlainbaseConfig,
    val policy: PolicyService,
    val tokens: ApiTokenService,
    val auth: AuthServices,
    val proxyCsrf: ProxyCsrf,
) {
    private val authConfig = config.auth
    val trustedProxyCidrs: List<String> = authConfig.trustedProxyCidrs
    val builtinAuthEnabled: Boolean = authConfig.mode == AuthMode.BUILTIN
    val proxyAuthEnabled: Boolean = authConfig.mode == AuthMode.PROXY
    val proxySecret: String? = authConfig.proxySecret
    val proxyIdentityHeader: String = authConfig.proxyIdentityHeader
    val extract: ApplicationCall.() -> PrincipalExtraction = {
        extractPrincipal(
            tokens = tokens,
            trustedProxyCidrs = trustedProxyCidrs,
            sessions = auth.session,
            builtinAuthEnabled = builtinAuthEnabled,
            proxyAuthEnabled = proxyAuthEnabled,
            proxySecret = proxySecret,
            proxyIdentityHeader = proxyIdentityHeader,
        )
    }
}

internal fun securityAssembly(
    config: PlainbaseConfig,
    policy: PolicyService,
    tokens: ApiTokenService,
    auth: AuthServices,
    proxyCsrf: ProxyCsrf,
): SecurityAssembly = SecurityAssembly(
    config = config,
    policy = policy,
    tokens = tokens,
    auth = auth,
    proxyCsrf = proxyCsrf,
)
