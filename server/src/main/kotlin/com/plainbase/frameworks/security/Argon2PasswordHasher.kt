package com.plainbase.frameworks.security

import com.plainbase.domain.principal.PasswordHasher
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.util.Arrays
import java.nio.CharBuffer
import java.security.SecureRandom
import kotlin.io.encoding.Base64

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

    // PHC argon2 uses unpadded standard base64; ABSENT_OPTIONAL omits padding on
    // encode and tolerates it on decode (matching the prior java getEncoder().withoutPadding()).
    private val base64 = Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    override fun hash(plain: CharArray): String {
        val salt = ByteArray(saltLength).also(random::nextBytes)
        val hash = compute(plain, salt, memoryKb, iterations, parallelism, hashLength)
        return "\$argon2id\$v=$PHC_VERSION\$m=$memoryKb,t=$iterations,p=$parallelism" +
            "\$${base64.encode(salt)}\$${base64.encode(hash)}"
    }

    override fun verify(plain: CharArray, encoded: String): Boolean {
        val phc = parsePhc(encoded) ?: return false
        val actual = compute(plain, phc.salt, phc.memoryKb, phc.iterations, phc.parallelism, phc.hash.size)
        return Arrays.constantTimeAreEqual(phc.hash, actual)
    }

    /** Holds the parsed PHC parameters; a plain class (not `data`) because of the [ByteArray] fields. */
    private class Phc(
        val memoryKb: Int,
        val iterations: Int,
        val parallelism: Int,
        val salt: ByteArray,
        val hash: ByteArray,
    )

    /**
     * Parses `$argon2id$v=19$m=..,t=..,p=..$<salt>$<hash>` into a [Phc], or null on any deviation.
     * Only Argon2 version 19 (0x13) is accepted; the `v=` prefix is load-bearing — a bare `19` must
     * not pass.
     */
    private fun parsePhc(encoded: String): Phc? {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return null
        val version = parts[2].takeIf { it.startsWith("v=") }?.removePrefix("v=")?.toIntOrNull()
        if (version != PHC_VERSION) return null
        val params = parseParams(parts[3]) ?: return null
        val m = params["m"]?.takeIf { it > 0 } ?: return null
        val t = params["t"]?.takeIf { it > 0 } ?: return null
        val p = params["p"]?.takeIf { it > 0 } ?: return null
        val salt = decodeBase64(parts[4])?.takeIf { it.isNotEmpty() } ?: return null
        val hash = decodeBase64(parts[5])?.takeIf { it.isNotEmpty() } ?: return null
        return Phc(m, t, p, salt, hash)
    }

    private fun parseParams(segment: String): Map<String, Int>? = buildMap {
        for (param in segment.split(',')) {
            val (k, v) = param.split('=', limit = 2).takeIf { it.size == 2 } ?: return null
            put(k, v.toIntOrNull() ?: return null)
        }
    }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            base64.decode(value)
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
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(plain))
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
