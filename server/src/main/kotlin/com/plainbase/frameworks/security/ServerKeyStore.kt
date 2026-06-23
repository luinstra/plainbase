package com.plainbase.frameworks.security

import com.plainbase.frameworks.sqldelight.PlainbaseDb
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Loads (or, on first boot, generates + persists) the server key the A4b [ProxyCsrf] HMACs its double-submit
 * tokens with. The key is a 32-byte `SecureRandom` value stored base64url in the `app_meta` table (TEXT columns,
 * the `ApiToken` encoding idiom) — NOT a config value: zero operator burden, and it survives a restart so issued
 * proxy-CSRF tokens stay valid (no spurious 403s after a bounce). `SecureRandom` + `app_meta.upsert` are both
 * native-proven (NativeSpike), so no new metadata.
 *
 * MUST be called INSIDE the DataDirLock region (the A4a WI-13 single-instance boot lock) so two processes never
 * race a double-generate. Mirrors `AdminCommand`'s direct-repo precedent — a single key does not warrant a domain
 * port (OPEN-QUESTION 3). The key material is NEVER logged.
 */
fun loadOrCreateProxyCsrfKey(db: PlainbaseDb, random: SecureRandom = SecureRandom()): ByteArray {
    val queries = db.appMetaQueries
    queries.selectByKey(PROXY_CSRF_HMAC_KEY).executeAsOneOrNull()?.let { stored ->
        runCatching { base64.decode(stored) }.getOrNull()?.takeIf { it.size == KEY_BYTES }?.let { return it }
        // A malformed/wrong-length stored value (manual edit, corruption) is replaced — fail-forward to a usable key.
    }
    val key = ByteArray(KEY_BYTES).also(random::nextBytes)
    queries.upsert(key = PROXY_CSRF_HMAC_KEY, value = base64.encode(key))
    return key
}

private const val PROXY_CSRF_HMAC_KEY = "proxy_csrf_hmac_key"
private const val KEY_BYTES = 32
private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
