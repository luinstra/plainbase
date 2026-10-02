package com.plainbase.domain.principal

data class SubjectKey(val issuer: String, val id: String) {
    init {
        require(issuer.isNotBlank()) { "issuer must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
    }

    companion object {
        fun of(principal: Principal): SubjectKey = when (principal) {
            is Principal.Human -> SubjectKey(principal.issuer, principal.externalId)
            is Principal.Agent -> SubjectKey("agent", principal.tokenId)
            Principal.Anonymous -> SubjectKey("anonymous", "local")
        }
    }
}
