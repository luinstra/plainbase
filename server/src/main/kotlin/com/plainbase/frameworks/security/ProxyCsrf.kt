package com.plainbase.frameworks.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * The A4b proxy-mode CSRF token: a STATELESS double-submit token, stored NOWHERE (no `sessions` row). A proxy
 * session is ambient (the trusted proxy re-asserts identity per request, there is no app session to bind a
 * synchronizer token to), so the cookie-mode synchronizer token (A4a) does not apply. Instead the token is
 * `base64url(nonce16 || HMAC-SHA256(serverKey, nonce16))` — set as a readable (`HttpOnly=false`) `pb_proxy_csrf`
 * cookie AND echoed by the SPA as the `X-CSRF-Token` header; [validate] requires cookie == header AND that the
 * HMAC tail re-derives under the server key.
 *
 * Two layers make it unforgeable WITHOUT server-side storage: the double-submit equality (an attacker on another
 * origin can't read the victim's cookie to echo it — SOP) PLUS the HMAC tail (a self-minted cookie an attacker
 * sets via a shared-domain sibling fails re-derivation without the key). The server [key] is the SecureRandom value
 * persisted in `app_meta` (see [loadOrCreateProxyCsrfKey]); it is NEVER logged. HMAC-SHA256 is `javax.crypto.Mac`
 * (JDK, native-safe, the same family as the allowlisted `MessageDigest`/`SecureRandom`) — no new runtime dep.
 */
class ProxyCsrf(private val key: ByteArray, private val random: SecureRandom = SecureRandom()) {

    /** Mint a fresh token: a random 16-byte nonce concatenated with its HMAC tail, base64url-encoded. */
    fun issue(): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return base64.encode(nonce + hmac(nonce))
    }

    /**
     * True iff [presentedCookie] and [presentedHeader] are both present, equal (constant-time over decoded bytes),
     * AND the embedded HMAC tail re-derives from the nonce under [key] (unforgeability). A null/blank either, or a
     * malformed base64 / wrong-length decode, returns false — a bad token is a 403, never an unhandled 500
     * (MINOR fold-in: base64 decode is CAUGHT).
     */
    fun validate(presentedCookie: String?, presentedHeader: String?): Boolean {
        val cookieBytes = decode(presentedCookie) ?: return false
        val headerBytes = decode(presentedHeader) ?: return false
        if (!MessageDigest.isEqual(cookieBytes, headerBytes)) return false
        // Double-submit equality is proven; now RE-DERIVE the HMAC tail from the nonce to prove the token is
        // server-minted (unforgeable). The cookie/header are equal, so deriving from either is the same — use the cookie.
        if (cookieBytes.size != NONCE_BYTES + MAC_BYTES) return false
        val nonce = cookieBytes.copyOfRange(0, NONCE_BYTES)
        val tail = cookieBytes.copyOfRange(NONCE_BYTES, cookieBytes.size)
        return MessageDigest.isEqual(hmac(nonce), tail)
    }

    private fun hmac(nonce: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALG).apply { init(SecretKeySpec(key, HMAC_ALG)) }.doFinal(nonce)

    private fun decode(raw: String?): ByteArray? {
        if (raw.isNullOrBlank()) return null
        return runCatching { base64.decode(raw) }.getOrNull()
    }

    private companion object {
        const val NONCE_BYTES = 16
        const val MAC_BYTES = 32 // HMAC-SHA256 output
        const val HMAC_ALG = "HmacSHA256"
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}
