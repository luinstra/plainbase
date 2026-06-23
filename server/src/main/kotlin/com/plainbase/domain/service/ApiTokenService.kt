package com.plainbase.domain.service

import com.plainbase.domain.principal.MintedToken
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TokenMinter
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.principal.parseToken
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenMeta
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.ApiTokenRow
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The single mint / authenticate / revoke path the bearer-extraction seam (§WI-5) and the `admin` CLI (§WI-8)
 * share. Composes the [TokenMinter] (SecureRandom), the [TokenSecretHasher] (SHA-256 + constant-time verify),
 * the [ApiTokenRepository], and an injectable [Clock] — `clock.now()` everywhere a timestamp is read, so
 * expiry/last-used are deterministic in tests (§0.9), never an inline `Instant.now()`.
 *
 * Transport-agnostic: the secure-context refusal is an HTTP-level decision (it needs the socket peer +
 * headers) and lives at the Ktor extraction point BEFORE [authenticate] is called — this service stays pure.
 */
class ApiTokenService(
    private val minter: TokenMinter,
    private val hasher: TokenSecretHasher,
    private val tokens: ApiTokenRepository,
    private val clock: Clock,
) {

    /**
     * Mints + persists a token; returns the plaintext ONCE in [MintedToken] (never stored, never re-derivable).
     * Agent tokens default `issuer = "agent"`, `external_id = id` — the `(issuer, externalId)` key A3 will use.
     */
    fun mint(label: String, mode: AgentMode, ttl: Duration? = null): MintedToken {
        val minted = minter.mint()
        val now = clock.now()
        tokens.insert(
            ApiTokenRow(
                id = minted.id,
                secretHash = minted.secretHash,
                agentLabel = label,
                issuer = AGENT_ISSUER,
                externalId = minted.id,
                mode = mode,
                createdAt = now,
                lastUsedAt = null,
                expiresAt = ttl?.let { now + it },
                revokedAt = null,
            ),
        )
        return minted
    }

    /**
     * Resolves a presented bearer to a [Principal]. Anti-enumeration (§WI-6): an unknown id and a wrong secret
     * are INDISTINGUISHABLE — both return [Principal.Anonymous] AND run the constant-time compare exactly once
     * (the verifier compares against a dummy hash when the row is absent, so the unknown-id path never
     * early-outs and the timing of "this prefix exists" is not observable). A revoked or expired token is also
     * [Principal.Anonymous].
     *
     * Success is LINEARIZED through a single conditional write
     * ([touchIfActive][ApiTokenRepository.touchIfActive]): the revoked/expiry check and the last-used stamp are
     * one atomic UPDATE that fires only while the token is still active, so a concurrent revoke committing
     * between the by-id read and the stamp cannot grant a [Principal.Agent] (TOCTOU-safe). The hash never
     * changes on revoke, so the constant-time verify still runs against the row read; we grant Agent ONLY when
     * the secret verified AND that conditional write affected exactly one row.
     */
    fun authenticate(rawBearer: String): Principal {
        val parsed = parseToken(rawBearer) ?: return Principal.Anonymous
        val row = tokens.findById(parsed.id)
        // Always compare (dummy hash when row == null) so unknown-id and wrong-secret do equal work.
        val secretMatches = hasher.verify(parsed.secret, row?.secretHash)
        if (row == null || !secretMatches) return Principal.Anonymous
        // ONE atomic write decides revoked/expired AND stamps last-used: no read-then-touch race window.
        if (!tokens.touchIfActive(row.id, clock.now())) return Principal.Anonymous
        return Principal.Agent(tokenId = row.id)
    }

    /** Revoke a token by its public [id] (idempotent — a no-op if already revoked/unknown). */
    fun revoke(id: String) {
        tokens.revoke(id, clock.now())
    }

    /** Every token's metadata, secret-hash-free ([ApiTokenMeta]) — the list/CLI surface. */
    fun list(): List<ApiTokenMeta> = tokens.all()

    private companion object {
        const val AGENT_ISSUER = "agent"
    }
}
