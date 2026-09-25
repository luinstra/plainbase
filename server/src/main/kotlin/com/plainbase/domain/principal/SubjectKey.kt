package com.plainbase.domain.principal

data class SubjectKey(val issuer: String, val id: String) {
    init {
        require(issuer.isNotBlank()) { "issuer must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
    }
}
