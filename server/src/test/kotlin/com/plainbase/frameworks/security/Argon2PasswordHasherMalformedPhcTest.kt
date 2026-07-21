package com.plainbase.frameworks.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

class Argon2PasswordHasherMalformedPhcTest : FunSpec({

    val hasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1)
    val password = "correct horse battery staple".toCharArray()

    context("malformed PHC strings") {
        val malformed = mapOf(
            "wrong algorithm" to "\$argon2i\$v=19\$m=256,t=1,p=1\$AQIDBA\$AQ",
            "wrong version" to "\$argon2id\$v=16\$m=256,t=1,p=1\$AQIDBA\$AQ",
            "missing version prefix" to "\$argon2id\$19\$m=256,t=1,p=1\$AQIDBA\$AQ",
            "missing memory parameter" to "\$argon2id\$v=19\$t=1,p=1\$AQIDBA\$AQ",
            "missing iteration parameter" to "\$argon2id\$v=19\$m=256,p=1\$AQIDBA\$AQ",
            "missing parallelism parameter" to "\$argon2id\$v=19\$m=256,t=1\$AQIDBA\$AQ",
            "nonnumeric parameter" to "\$argon2id\$v=19\$m=lots,t=1,p=1\$AQIDBA\$AQ",
            "zero parameter" to "\$argon2id\$v=19\$m=0,t=1,p=1\$AQIDBA\$AQ",
            "negative parameter" to "\$argon2id\$v=19\$m=256,t=-1,p=1\$AQIDBA\$AQ",
            "overflowed parameter" to "\$argon2id\$v=19\$m=2147483648,t=1,p=1\$AQIDBA\$AQ",
            "memory above the verification cap" to "\$argon2id\$v=19\$m=65537,t=1,p=1\$AQIDBAUGBwg\$AQIDBA",
            "iterations above the verification cap" to "\$argon2id\$v=19\$m=256,t=6,p=1\$AQIDBAUGBwg\$AQIDBA",
            "parallelism above the verification cap" to "\$argon2id\$v=19\$m=256,t=1,p=5\$AQIDBAUGBwg\$AQIDBA",
            "insufficient memory per lane" to "\$argon2id\$v=19\$m=31,t=1,p=4\$AQIDBAUGBwg\$AQIDBA",
            "invalid salt base64" to "\$argon2id\$v=19\$m=256,t=1,p=1\$%%%\$AQ",
            "invalid hash base64" to "\$argon2id\$v=19\$m=256,t=1,p=1\$AQIDBA\$%%%",
            "empty salt" to "\$argon2id\$v=19\$m=256,t=1,p=1\$\$AQ",
            "empty hash" to "\$argon2id\$v=19\$m=256,t=1,p=1\$AQIDBA\$",
            "salt shorter than 8 bytes" to "\$argon2id\$v=19\$m=256,t=1,p=1\$AQIDBA\$AQIDBA",
            "hash shorter than 4 bytes" to "\$argon2id\$v=19\$m=256,t=1,p=1\$AQIDBAUGBwg\$AQ",
            "salt longer than 64 bytes" to "\$argon2id\$v=19\$m=256,t=1,p=1\$${"A".repeat(87)}\$AQIDBA",
            "hash longer than 64 bytes" to "\$argon2id\$v=19\$m=256,t=1,p=1\$AQIDBAUGBwg\$${"A".repeat(87)}",
            "PHC string longer than 512 characters" to
                "\$argon2id\$v=19\$m=256,t=1,p=1\$${"A".repeat(513)}\$AQIDBA",
        )

        malformed.forEach { (case, encoded) ->
            test("should return false without throwing for $case") {
                hasher.verify(password, encoded).shouldBeFalse()
            }
        }
    }

    context("non-canonical parameter sets") {
        val valid = hasher.hash(password)
        val parameterEnd = valid.indexOf('$', startIndex = "\$argon2id\$v=19\$".length)
        val parameters = valid.substring("\$argon2id\$v=19\$".length, parameterEnd)

        test("should reject a duplicate parameter") {
            val encoded = valid.replaceFirst(parameters, "$parameters,m=256")

            hasher.verify(password, encoded).shouldBeFalse()
        }

        test("should reject an unknown parameter") {
            val encoded = valid.replaceFirst(parameters, "$parameters,x=1")

            hasher.verify(password, encoded).shouldBeFalse()
        }

        test("should accept the required parameters in a different order") {
            val encoded = valid.replaceFirst(parameters, "p=1,t=1,m=256")

            hasher.verify(password, encoded).shouldBeTrue()
        }
    }

    context("emitter resource policy") {
        test("should reject constructor settings that the verifier would refuse") {
            listOf<() -> Unit>(
                { Argon2PasswordHasher(memoryKb = 65_537) },
                { Argon2PasswordHasher(iterations = 6) },
                { Argon2PasswordHasher(parallelism = 5) },
                { Argon2PasswordHasher(memoryKb = 31, parallelism = 4) },
                { Argon2PasswordHasher(saltLength = 7) },
                { Argon2PasswordHasher(hashLength = 65) },
            ).forEach { construct ->
                shouldThrow<IllegalArgumentException>(construct)
            }
        }
    }
})
