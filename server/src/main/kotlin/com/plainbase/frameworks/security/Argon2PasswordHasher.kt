package com.plainbase.frameworks.security

import com.plainbase.domain.principal.PasswordHasher
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.util.Arrays
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
        return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=$parallelism" +
            "\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    override fun verify(plain: CharArray, encoded: String): Boolean {
        val parts = encoded.split('$')
        // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate {
            val (k, v) = it.split('=', limit = 2)
            k to v.toInt()
        }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        val salt = Base64.getDecoder().decode(parts[4])
        val expected = Base64.getDecoder().decode(parts[5])
        val actual = compute(plain, salt, m, t, p, expected.size)
        return Arrays.constantTimeAreEqual(expected, actual)
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
        val bytes = String(plain).toByteArray(StandardCharsets.UTF_8)
        generator.generateBytes(bytes, out)
        return out
    }
}
