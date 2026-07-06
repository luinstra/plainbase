package com.plainbase.frameworks.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The `s3-smoke` config-validation contract: the endpoint https gate and the strict soak-count
 * parse both fail fast as usage errors (exit 2) BEFORE any network client is built, so they are
 * unit-testable without a live endpoint. The happy path needs real credentials and is exercised
 * only by an operator run on the native binary.
 */
class S3SmokeCommandTest : FunSpec({

    fun validEnv(vararg overrides: Pair<String, String>): Map<String, String> = mapOf(
        "PLAINBASE_SMOKE_ENDPOINT" to "https://acct.r2.cloudflarestorage.com",
        "PLAINBASE_SMOKE_REGION" to "auto",
        "PLAINBASE_SMOKE_BUCKET" to "scratch",
        "PLAINBASE_SMOKE_ACCESS_KEY_ID" to "AKIA",
        "PLAINBASE_SMOKE_SECRET_ACCESS_KEY" to "shh",
    ) + overrides

    test("an http endpoint is refused as a usage error (never SigV4 creds over cleartext) unless the insecure override is set") {
        val err = captureStderr {
            S3SmokeCommand.run(emptyList(), validEnv("PLAINBASE_SMOKE_ENDPOINT" to "http://acct.r2.cloudflarestorage.com")) shouldBe 2
        }
        err shouldContain "must be https"
        err shouldContain "PLAINBASE_INSECURE_HTTP=1"
    }

    test("a non-URL endpoint is refused as a usage error") {
        val err = captureStderr {
            S3SmokeCommand.run(emptyList(), validEnv("PLAINBASE_SMOKE_ENDPOINT" to "not-a-url")) shouldBe 2
        }
        err shouldContain "not an absolute http(s) URL"
    }

    test("a negative PLAINBASE_SMOKE_SOAK_GETS is a usage error (never a silent skip)") {
        val err = captureStderr {
            S3SmokeCommand.run(emptyList(), validEnv("PLAINBASE_SMOKE_SOAK_GETS" to "-1")) shouldBe 2
        }
        err shouldContain "PLAINBASE_SMOKE_SOAK_GETS must be a non-negative integer"
    }

    test("a non-integer PLAINBASE_SMOKE_SOAK_GETS is a usage error (never a silent coerce to 100)") {
        val err = captureStderr {
            S3SmokeCommand.run(emptyList(), validEnv("PLAINBASE_SMOKE_SOAK_GETS" to "lots")) shouldBe 2
        }
        err shouldContain "PLAINBASE_SMOKE_SOAK_GETS must be a non-negative integer"
    }

    test("missing required env is a usage error naming the gaps") {
        val err = captureStderr { S3SmokeCommand.run(emptyList(), emptyMap()) shouldBe 2 }
        err shouldContain "missing env"
    }

    test("any argument is a usage error (config comes from env, never argv)") {
        S3SmokeCommand.run(listOf("--bogus"), validEnv()) shouldBe 2
    }
})

private fun captureStderr(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.err
    System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setErr(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}
