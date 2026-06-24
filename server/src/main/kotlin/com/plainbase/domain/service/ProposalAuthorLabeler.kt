package com.plainbase.domain.service

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.UserRepository

/**
 * Resolves the SNAPSHOT author attribution `(issuer, externalId, label)` for a [Principal] at propose time (C4).
 * `Principal` carries NO display-label field, so the label is RESOLVED here from the token/user row and SNAPSHOTTED
 * into the proposal — authorship then survives a later token/user deletion (the council's snapshot requirement; a
 * read-time resolve would lose it). Domain-side over the repository ports, so `ProposalService` stays framework-free.
 *
 *  - Agent  -> (issuer="agent", externalId=tokenId, label=token.agentLabel ?: tokenId)
 *  - builtin Human -> (issuer, externalId, label=user.displayName ?: externalId)
 *  - proxy   Human -> (issuer, externalId, label=externalId) — no local users row
 *  - Anonymous     -> ("anonymous", "local", "anonymous") — the off-mode E1 arm (auth.mode=off only); the NOT-NULL
 *    columns get a synthetic value (consistent with the audit `decisionRow` `kind="anonymous"`), never a real NULL.
 */
class ProposalAuthorLabeler(
    private val tokens: ApiTokenRepository,
    private val users: UserRepository,
) {

    fun resolve(principal: Principal): ProposalAuthor = when (principal) {
        is Principal.Agent -> ProposalAuthor(
            issuer = AGENT_ISSUER,
            externalId = principal.tokenId,
            // Narrow label-only lookup — this non-auth attribution path never loads the token's at-rest secret hash.
            label = tokens.agentLabelById(principal.tokenId) ?: principal.tokenId,
        )
        is Principal.Human -> ProposalAuthor(
            issuer = principal.issuer,
            externalId = principal.externalId,
            label = if (principal.issuer == BUILTIN_ISSUER) {
                // Narrow label-only lookup — never loads the user's at-rest password hash.
                users.displayNameById(principal.externalId) ?: principal.externalId
            } else {
                principal.externalId
            },
        )
        Principal.Anonymous -> ProposalAuthor(issuer = ANONYMOUS_ISSUER, externalId = ANONYMOUS_EXTERNAL_ID, label = ANONYMOUS_LABEL)
    }

    private companion object {
        const val AGENT_ISSUER = "agent"
        const val BUILTIN_ISSUER = "builtin"
        const val ANONYMOUS_ISSUER = "anonymous"
        const val ANONYMOUS_EXTERNAL_ID = "local"
        const val ANONYMOUS_LABEL = "anonymous"
    }
}
