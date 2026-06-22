package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.WritePipeline
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
    tokens: ApiTokenService,
    trustedProxyCidrs: List<String>,
    maxWriteBodyBytes: Long,
    maxAssetBytes: Long,
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
    )
    val mutate = GuardedMutatingFacade(
        policy = policy,
        writePipeline = writePipeline,
        contentStore = contentStore,
        indexBuilder = indexBuilder,
    )
    return RouteContext(
        read = read,
        mutate = mutate,
        tokens = tokens,
        trustedProxyCidrs = trustedProxyCidrs,
        idProvider = idProvider,
        maxWriteBodyBytes = maxWriteBodyBytes,
        maxAssetBytes = maxAssetBytes,
        extract = extract ?: { extractPrincipal(tokens, trustedProxyCidrs) },
    )
}

/** A fixed-`Principal` extraction source for the route-test harnesses (auth ON, a real role-appropriate principal). */
fun fixedPrincipal(principal: Principal): ApplicationCall.() -> PrincipalExtraction =
    { PrincipalExtraction.Resolved(principal) }
