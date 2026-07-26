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

    init {
        require(memoryKb in 1..MAX_MEMORY_KB) { "Argon2 memory must be between 1 and $MAX_MEMORY_KB KiB" }
        require(iterations in 1..MAX_ITERATIONS) { "Argon2 iterations must be between 1 and $MAX_ITERATIONS" }
        require(parallelism in 1..MAX_PARALLELISM) { "Argon2 parallelism must be between 1 and $MAX_PARALLELISM" }
        require(memoryKb >= MIN_MEMORY_KB_PER_LANE * parallelism) {
            "Argon2 memory must be at least $MIN_MEMORY_KB_PER_LANE KiB per lane"
        }
        require(saltLength in MIN_SALT_BYTES..MAX_SALT_BYTES) {
            "Argon2 salt length must be between $MIN_SALT_BYTES and $MAX_SALT_BYTES bytes"
        }
        require(hashLength in MIN_HASH_BYTES..MAX_HASH_BYTES) {
            "Argon2 hash length must be between $MIN_HASH_BYTES and $MAX_HASH_BYTES bytes"
        }
    }

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
        val parts = encoded.takeIf { it.length <= MAX_PHC_CHARS }
            ?.split('$')
            ?.takeIf { it.size == PHC_PART_COUNT && it[1] == "argon2id" }
        val version = parts?.get(2)?.takeIf { it.startsWith("v=") }?.removePrefix("v=")?.toIntOrNull()
        val params = parts?.get(3)?.takeIf { version == PHC_VERSION }?.let(::parseParams)
        val memory = params?.get("m")?.takeIf { it in 1..MAX_MEMORY_KB }
        val iterations = params?.get("t")?.takeIf { it in 1..MAX_ITERATIONS }
        val parallelism = params?.get("p")?.takeIf { it in 1..MAX_PARALLELISM }
        val salt = parts?.get(4)?.let(::decodeBase64)?.takeIf { it.size in MIN_SALT_BYTES..MAX_SALT_BYTES }
        val hash = parts?.get(5)?.let(::decodeBase64)?.takeIf { it.size in MIN_HASH_BYTES..MAX_HASH_BYTES }
        return when {
            memory != null &&
                iterations != null &&
                parallelism != null &&
                memory >= MIN_MEMORY_KB_PER_LANE * parallelism &&
                salt != null &&
                hash != null ->
                Phc(memory, iterations, parallelism, salt, hash)
            else -> null
        }
    }

    private fun parseParams(segment: String): Map<String, Int>? = buildMap {
        for (param in segment.split(',')) {
            val (k, v) = param.split('=', limit = 2).takeIf { it.size == 2 } ?: return null
            if (k !in PHC_PARAMETERS || containsKey(k)) return null
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
        private const val MIN_MEMORY_KB_PER_LANE = 8
        private const val PHC_PART_COUNT = 6

        /** The only Argon2 version we emit or accept (0x13 — “v=19” in PHC strings). */
        const val PHC_VERSION = 19

        /**
         * Verification resource envelope. Plainbase has only emitted m=65536,t=3,p=1. These caps also admit the
         * RFC 9106 low-memory profile and every OWASP-listed Argon2id profile, while a corrupted/hostile DB row
         * cannot request more memory, passes, lanes, or decode buffers than the server deliberately supports.
         */
        const val MAX_MEMORY_KB = 65_536
        const val MAX_ITERATIONS = 5
        const val MAX_PARALLELISM = 4
        const val MIN_SALT_BYTES = 8
        const val MAX_SALT_BYTES = 64
        const val MIN_HASH_BYTES = 4
        const val MAX_HASH_BYTES = 64
        const val MAX_PHC_CHARS = 512

        val PHC_PARAMETERS = setOf("m", "t", "p")
    }
}
