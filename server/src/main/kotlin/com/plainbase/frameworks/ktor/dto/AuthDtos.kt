package com.plainbase.frameworks.ktor.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The A4a auth wire shapes (login / session / setup / reset / change / admin user CRUD). Served + parsed through
// the scoped RestJson (manual respondText / RestJson.decodeFromString — the PageCreateRoutes idiom), NOT Ktor
// content-negotiation, exactly like every PB-REST/WRITE DTO. Consequently they need NO reflect-config.json triples
// (§0.12); the native content-negotiation round-trip test is the proof. List/read responses NEVER carry a plaintext
// password / hash / token (the ApiTokenMeta discipline) — the only secret on the wire is a one-time minted token a
// CREATE/reset response returns ONCE.

/** `POST /api/v1/login` request. The route copies [password] into a CharArray and zeroes it best-effort after. */
@Serializable
data class LoginRequest(val username: String, val password: String)

/** `POST /api/v1/login` success response: the CSRF token (base64url). The session itself rides the `pb_session` cookie. */
@Serializable
data class LoginResponse(@SerialName("csrf_token") val csrfToken: String)

/**
 * `GET /api/v1/session` response: the current auth state + a FRESH CSRF token (the cookie-mode session synchronizer
 * token, or the A4b proxy double-submit token). [authMode] (`"builtin"|"proxy"|"off"`) lets the SPA hide builtin-only
 * panels (user CRUD) in proxy mode — the server stays authoritative (it still 404/403s the builtin-only routes), this
 * only drives the UI hint (BLOCKING-2).
 */
@Serializable
data class SessionResponse(
    val authenticated: Boolean,
    val username: String?,
    @SerialName("csrf_token") val csrfToken: String?,
    @SerialName("auth_mode") val authMode: String,
)

/** `POST /api/v1/setup/consume` request: a bootstrap token + the first admin's chosen credentials. */
@Serializable
data class SetupConsumeRequest(val token: String, val username: String, val password: String)

/** `POST /api/v1/password/reset/consume` request: a reset token + the new password (token from the BODY only). */
@Serializable
data class ResetConsumeRequest(val token: String, @SerialName("new_password") val newPassword: String)

/** `POST /api/v1/password/change` request (cookie-auth + CSRF-protected): the current + new password. */
@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

/** `POST /api/v1/admin/users` request: the server mints a reset token (no password here — conveyed out-of-band). */
@Serializable
data class CreateUserRequest(
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val role: String,
)

/**
 * `POST /api/v1/admin/users` + `…/reset` response: the created/target user id + the one-time reset token the admin
 * conveys out-of-band (returned ONCE, never re-readable). This is the ONLY response carrying a token.
 */
@Serializable
data class CreatedUserResponse(
    val id: String,
    val username: String,
    @SerialName("reset_token") val resetToken: String,
)

/** One admin-list user — NEVER a `password_hash`/secret field (the metadata-only list discipline). */
@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String?,
    val disabled: Boolean,
)

/** `GET /api/v1/admin/users` response. */
@Serializable
data class UserListResponse(val users: List<UserResponse>)

/** `POST /api/v1/admin/sessions/revoke` request: revoke all of a target user's sessions (the admin-revoke hook). */
@Serializable
data class SessionRevokeRequest(@SerialName("user_id") val userId: String)

// ---- A4b: the admin token / audit / role management wire shapes (manage-gated, mode-independent) --------------

/** One API-token row — metadata only, NEVER a `secret_hash`/plaintext (the list discipline). */
@Serializable
data class TokenMetaResponse(
    val id: String,
    val label: String,
    val mode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String?,
    @SerialName("expires_at") val expiresAt: String?,
    @SerialName("revoked_at") val revokedAt: String?,
)

/** `GET /api/v1/admin/tokens` response. */
@Serializable
data class TokenListResponse(val tokens: List<TokenMetaResponse>)

/** `POST /api/v1/admin/tokens` request: the label + the agent mode (read-only/propose/commit). */
@Serializable
data class MintTokenRequest(val label: String, val mode: String)

/** `POST /api/v1/admin/tokens` response: the minted token's id + the one-time plaintext (returned ONCE). */
@Serializable
data class CreatedTokenResponse(val id: String, val plaintext: String)

/** One audit decision row — WHO/WHAT/decision (no secret fields). */
@Serializable
data class AuditEntryResponse(
    val id: String,
    val ts: String,
    @SerialName("principal_kind") val principalKind: String,
    val issuer: String?,
    @SerialName("external_id") val externalId: String?,
    val action: String,
    val resource: String,
    val decision: String,
)

/** `GET /api/v1/admin/audit` response. */
@Serializable
data class AuditListResponse(val entries: List<AuditEntryResponse>)

/** One subject→role row. */
@Serializable
data class RoleResponse(
    val issuer: String,
    @SerialName("external_id") val externalId: String,
    val role: String,
    @SerialName("created_at") val createdAt: String,
)

/** `GET /api/v1/admin/roles` response. */
@Serializable
data class RoleListResponse(val roles: List<RoleResponse>)

/** `POST /api/v1/admin/roles` request: grant/regrant a role to an `(issuer, external_id)` identity. */
@Serializable
data class GrantRoleRequest(
    val issuer: String,
    @SerialName("external_id") val externalId: String,
    val role: String,
)
