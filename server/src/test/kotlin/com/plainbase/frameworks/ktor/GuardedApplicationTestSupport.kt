package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import io.ktor.server.application.ApplicationCall

/** Test-only principal source used by fixed-principal route matrices. */
fun fixedPrincipal(principal: Principal): ApplicationCall.() -> PrincipalExtraction =
    { PrincipalExtraction.Resolved(principal) }

/** Reconstructs only the test extraction seam after the real guarded graph has been assembled. */
internal fun RouteContext.withExtract(extract: ApplicationCall.() -> PrincipalExtraction): RouteContext =
    RouteContext(
        read = read,
        mutate = mutate,
        proposals = proposals,
        registry = registry,
        availability = availability,
        convergence = convergence,
        limbo = limbo,
        tokens = tokens,
        auth = auth,
        trustedProxyCidrs = trustedProxyCidrs,
        idProvider = idProvider,
        maxWriteBodyBytes = maxWriteBodyBytes,
        maxAssetBytes = maxAssetBytes,
        mcpAllowedHosts = mcpAllowedHosts,
        mcpAllowedOrigins = mcpAllowedOrigins,
        builtinAuthEnabled = builtinAuthEnabled,
        proxyAuthEnabled = proxyAuthEnabled,
        proxySecret = proxySecret,
        proxyIdentityHeader = proxyIdentityHeader,
        secureCookie = secureCookie,
        proxyCsrf = proxyCsrf,
        extract = extract,
    )
