package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.LinkChecker
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.security.ProxyCsrf
import io.ktor.server.application.ApplicationCall

/**
 * Assembles the A3 choke point — the [PolicyService], the two guarded facades, and the [RouteContext] the
 * routing layer receives — from the raw services. The ONE place the raw `WritePipeline`/`ContentStore`/
 * `IndexBuilder` are handed to the facade impls (their PRIVATE deps); routes never see them. Used by the Koin
 * `restModule` AND the route-test harnesses, so production and tests wire the choke point identically.
 *
 * [extract] defaults to the real A1/A2 [extractPrincipal] over [tokens]/[trustedProxyCidrs]; a harness may pass a
 * fixed-`Principal` source (a test-construction choice — auth is never turned off, §WI-9).
 */
@Suppress("LongParameterList")
fun buildRouteContext(
    policy: PolicyService,
    indexBuilder: IndexBuilder,
    pageService: PageService,
    searchService: SearchService,
    aliasRegistry: UrlAliasRegistry,
    contentStore: ContentStore,
    writePipeline: WritePipeline,
    history: HistoryProvider,
    idProvider: IdProvider,
    proposalService: ProposalService,
    proposalLabeler: ProposalAuthorLabeler,
    tokens: ApiTokenService,
    auth: AuthServices,
    trustedProxyCidrs: List<String>,
    maxWriteBodyBytes: Long,
    maxAssetBytes: Long,
    builtinAuthEnabled: Boolean = true,
    proxyAuthEnabled: Boolean = false,
    proxySecret: String? = null,
    proxyIdentityHeader: String = PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
    secureCookie: Boolean = false,
    // TEST-ONLY default: production wiring (Application.kt) ALWAYS passes the real key-derived ProxyCsrf, so this
    // zero-key fallback is never reached in prod — it only spares non-proxy route tests from constructing one.
    proxyCsrf: ProxyCsrf = ProxyCsrf(ByteArray(32)),
    extract: (ApplicationCall.() -> PrincipalExtraction)? = null,
): RouteContext {
    val read = GuardedReadFacade(
        policy = policy,
        pageService = pageService,
        searchService = searchService,
        contentStore = contentStore,
        indexBuilder = indexBuilder,
        history = history,
        aliasRegistry = aliasRegistry,
        linkChecker = LinkChecker(),
    )
    val mutate = GuardedMutatingFacade(
        policy = policy,
        writePipeline = writePipeline,
        contentStore = contentStore,
        indexBuilder = indexBuilder,
    )
    // The apply-on-approve composition (P1b) lives BEHIND ProposalFacade — it needs the guarded MUTATING path to do
    // the content write, so the facade is assembled HERE (where `mutate` exists), keeping the choke-point assembly
    // in ONE place. A route never sees the composition.
    val proposals = GuardedProposalFacade(
        policy = policy,
        proposals = proposalService,
        labeler = proposalLabeler,
        mutate = mutate,
    )
    return RouteContext(
        read = read,
        mutate = mutate,
        proposals = proposals,
        tokens = tokens,
        auth = auth,
        trustedProxyCidrs = trustedProxyCidrs,
        idProvider = idProvider,
        maxWriteBodyBytes = maxWriteBodyBytes,
        maxAssetBytes = maxAssetBytes,
        builtinAuthEnabled = builtinAuthEnabled,
        proxyAuthEnabled = proxyAuthEnabled,
        proxySecret = proxySecret,
        proxyIdentityHeader = proxyIdentityHeader,
        secureCookie = secureCookie,
        proxyCsrf = proxyCsrf,
        extract = extract ?: {
            extractPrincipal(
                tokens,
                trustedProxyCidrs,
                auth.session,
                builtinAuthEnabled,
                proxyAuthEnabled = proxyAuthEnabled,
                proxySecret = proxySecret,
                proxyIdentityHeader = proxyIdentityHeader,
            )
        },
    )
}

/** A fixed-`Principal` extraction source for the route-test harnesses (auth ON, a real role-appropriate principal). */
fun fixedPrincipal(principal: Principal): ApplicationCall.() -> PrincipalExtraction =
    { PrincipalExtraction.Resolved(principal) }
