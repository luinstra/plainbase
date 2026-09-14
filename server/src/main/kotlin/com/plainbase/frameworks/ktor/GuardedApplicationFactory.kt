package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.LinkChecker
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.runtime.ServingRuntime
import com.plainbase.frameworks.security.ProxyCsrf
import io.ktor.server.application.ApplicationCall

internal fun buildGuardedApplication(
    serving: ServingRuntime,
    security: SecurityAssembly,
    transport: TransportSettings,
): RouteContext {
    val rooted = RootedContentInputs(serving)
    val published = PublishedReadInputs(serving)
    val mutation = MutationInputs(serving)
    val securityInputs = SecurityInputs(security)
    val transportInputs = TransportInputs(transport)

    val read = GuardedReadFacade(
        policy = securityInputs.policy,
        pageService = published.pageService,
        searchService = published.searchService,
        indexBuilder = published.indexBuilder,
        aliasRegistry = published.aliasRegistry,
        linkChecker = LinkChecker(),
        registry = rooted.registry,
        availability = rooted.availability,
        resolver = rooted.resolver,
        absence = rooted.absence,
        stores = rooted.stores,
        histories = rooted.histories,
    )
    lateinit var proposalsFacade: ProposalFacade
    val mutate = GuardedMutatingFacade(
        policy = securityInputs.policy,
        writePipeline = mutation.writePipeline,
        stores = rooted.stores,
        indexBuilder = published.indexBuilder,
        availability = rooted.availability,
        resolver = rooted.resolver,
        absence = rooted.absence,
        proposals = { proposalsFacade },
        agentDirectCommitGlobs = mutation.agentDirectCommitGlobs,
        proposalLabeler = mutation.proposalLabeler,
    )
    val proposals = GuardedProposalFacade(
        policy = securityInputs.policy,
        proposals = mutation.proposalService,
        labeler = mutation.proposalLabeler,
        mutate = mutate,
        idProvider = mutation.idProvider,
        indexBuilder = published.indexBuilder,
        resolver = rooted.resolver,
        availability = rooted.availability,
        absence = rooted.absence,
    )
    proposalsFacade = proposals
    return RouteContext(
        read = read,
        mutate = mutate,
        proposals = proposals,
        registry = rooted.registry,
        availability = rooted.availability,
        convergence = rooted.convergence,
        limbo = rooted.limbo,
        tokens = securityInputs.tokens,
        auth = securityInputs.auth,
        trustedProxyCidrs = securityInputs.trustedProxyCidrs,
        idProvider = mutation.idProvider,
        maxWriteBodyBytes = transportInputs.maxWriteBodyBytes,
        maxAssetBytes = transportInputs.maxAssetBytes,
        mcpAllowedHosts = transportInputs.mcpAllowedHosts,
        mcpAllowedOrigins = transportInputs.mcpAllowedOrigins,
        builtinAuthEnabled = securityInputs.builtinAuthEnabled,
        proxyAuthEnabled = securityInputs.proxyAuthEnabled,
        proxySecret = securityInputs.proxySecret,
        proxyIdentityHeader = securityInputs.proxyIdentityHeader,
        secureCookie = transportInputs.secureCookie,
        proxyCsrf = securityInputs.proxyCsrf,
        extract = securityInputs.extract,
    )
}

private class RootedContentInputs(serving: ServingRuntime) {
    val registry: RootRegistry = serving.index.registry
    val availability: RootAvailability = serving.index.availability
    val convergence: RootConvergence = serving.index.convergence
    val limbo: RootLimbo = serving.index.limbo
    val resolver: PageRootResolver = serving.resolver
    val absence: AbsenceClassifier = serving.absence
    val stores: (RootName) -> ContentStore = serving.index.stores::get
    val histories: (RootName) -> HistoryProvider = serving.index.histories::get
}

private class PublishedReadInputs(serving: ServingRuntime) {
    val indexBuilder: IndexBuilder = serving.index.builder
    val pageService: PageService = serving.pageService
    val searchService: SearchService = serving.searchService
    val aliasRegistry: UrlAliasRegistry = serving.index.aliasRegistry
}

private class MutationInputs(serving: ServingRuntime) {
    val writePipeline: WritePipeline = serving.writePipeline
    val idProvider: IdProvider = serving.index.idProvider
    val proposalService: ProposalService = serving.proposalService
    val proposalLabeler: ProposalAuthorLabeler = serving.proposalLabeler
    val agentDirectCommitGlobs: List<CommitGlob> = serving.agentDirectCommitGlobs
}

private class SecurityInputs(security: SecurityAssembly) {
    val policy: PolicyService = security.policy
    val tokens: ApiTokenService = security.tokens
    val auth: AuthServices = security.auth
    val trustedProxyCidrs: List<String> = security.trustedProxyCidrs
    val builtinAuthEnabled: Boolean = security.builtinAuthEnabled
    val proxyAuthEnabled: Boolean = security.proxyAuthEnabled
    val proxySecret: String? = security.proxySecret
    val proxyIdentityHeader: String = security.proxyIdentityHeader
    val proxyCsrf: ProxyCsrf = security.proxyCsrf
    val extract: ApplicationCall.() -> PrincipalExtraction = security.extract
}

private class TransportInputs(transport: TransportSettings) {
    val maxWriteBodyBytes: Long = transport.maxWriteBodyBytes
    val maxAssetBytes: Long = transport.maxAssetBytes
    val mcpAllowedHosts: List<String> = transport.mcpAllowedHosts
    val mcpAllowedOrigins: List<String> = transport.mcpAllowedOrigins
    val secureCookie: Boolean = transport.secureCookie
}
