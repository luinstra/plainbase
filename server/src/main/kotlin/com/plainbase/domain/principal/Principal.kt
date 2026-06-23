package com.plainbase.domain.principal

/**
 * Who is making a request. A1 lands the TYPE + the extraction seam (returning [Anonymous]); the identity
 * SOURCES arrive later — [Agent] in A2 (`pb_` bearer), [Human] in A4a (login) / A4b (proxy header). The
 * (issuer, externalId) pair rides from day one (master §5.5) so SSO is an ADDITION, not a migration.
 *
 * Identity only: privilege comes from A3's role table keyed on `(issuer, externalId)`, never carried here —
 * a frontmatter/header claim is never a permission input (master §5.5: "never trust claims for privilege").
 */
sealed interface Principal {

    /**
     * A built-in or proxy-asserted human. [issuer]/[externalId] identify the source (e.g. `"builtin"`/a user
     * id, or `"proxy"`/an SSO subject) — the `(issuer, externalId)` → role key A3's role table uses.
     */
    data class Human(val issuer: String, val externalId: String) : Principal

    /** An app-issued agent token (A2). [tokenId] is the public `pb_<id>_…` prefix (NOT the secret). */
    data class Agent(val tokenId: String) : Principal

    /** No credential presented. Makes [Principal] total so A3's `check(principal, …)` needs no null branch. */
    data object Anonymous : Principal
}
