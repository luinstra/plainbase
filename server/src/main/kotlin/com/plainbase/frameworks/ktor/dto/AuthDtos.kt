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

/** `GET /api/v1/session` response: the current cookie-auth state + a FRESH read of the session's CSRF token. */
@Serializable
data class SessionResponse(
    val authenticated: Boolean,
    val username: String?,
    @SerialName("csrf_token") val csrfToken: String?,
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
