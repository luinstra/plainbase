package com.plainbase.frameworks.security

import com.plainbase.domain.principal.PasswordHasher
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.util.Arrays
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id via Bouncy Castle's pure-Java implementation.
 *
 * Deliberately NOT argon2-jvm (JNA-based — a native-image hazard). Encodes in the
 * PHC string format: `$argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>`.
 */
class Argon2PasswordHasher(
    private val memoryKb: Int = 65536,
    private val iterations: Int = 3,
    private val parallelism: Int = 1,
    private val saltLength: Int = 16,
    private val hashLength: Int = 32,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {

    override fun hash(plain: CharArray): String {
        val salt = ByteArray(saltLength).also(random::nextBytes)
        val hash = compute(plain, salt, memoryKb, iterations, parallelism, hashLength)
        val b64 = Base64.getEncoder().withoutPadding()
        return "\$argon2id\$v=$PHC_VERSION\$m=$memoryKb,t=$iterations,p=$parallelism" +
            "\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    override fun verify(plain: CharArray, encoded: String): Boolean {
        val parts = encoded.split('$')
        // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
        if (parts.size != 6 || parts[1] != "argon2id") return false
        // Validate the PHC version segment explicitly: we only ever emit and
        // accept Argon2 version 19 (0x13). Anything else — missing, malformed,
        // or a different version — is rejected outright.
        val version = parts[2]
        if (!version.startsWith("v=")) return false
        if (version.removePrefix("v=").toIntOrNull() != PHC_VERSION) return false
        val params = parts[3].split(',').associate {
            val kv = it.split('=', limit = 2)
            if (kv.size != 2) return false
            kv[0] to (kv[1].toIntOrNull() ?: return false)
        }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        if (m <= 0 || t <= 0 || p <= 0) return false
        val salt = decodeBase64(parts[4]) ?: return false
        val expected = decodeBase64(parts[5]) ?: return false
        if (salt.isEmpty() || expected.isEmpty()) return false
        val actual = compute(plain, salt, m, t, p, expected.size)
        return Arrays.constantTimeAreEqual(expected, actual)
    }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun compute(
        plain: CharArray,
        salt: ByteArray,
        memoryKb: Int,
        iterations: Int,
        parallelism: Int,
        hashLength: Int,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        val out = ByteArray(hashLength)
        // Encode the password CharArray to UTF-8 without going through a String:
        // Strings are immutable and unzeroable, so a copy would linger on the heap
        // until GC. Both intermediate byte buffers are zeroed in `finally`.
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(plain))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        try {
            generator.generateBytes(bytes, out)
            return out
        } finally {
            bytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private companion object {
        /** The only Argon2 version we emit or accept (0x13 — “v=19” in PHC strings). */
        const val PHC_VERSION = 19
    }
}
