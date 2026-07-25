package com.plainbase.frameworks.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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
        val io = CommandOutputFixture()
        S3SmokeCommand.run(
            emptyList(),
            validEnv("PLAINBASE_SMOKE_ENDPOINT" to "http://acct.r2.cloudflarestorage.com"),
            io.output,
        ) shouldBe 2
        io.stderr shouldContain "must be https"
        io.stderr shouldContain "PLAINBASE_INSECURE_HTTP=1"
    }

    test("a non-URL endpoint is refused as a usage error") {
        val io = CommandOutputFixture()
        S3SmokeCommand.run(emptyList(), validEnv("PLAINBASE_SMOKE_ENDPOINT" to "not-a-url"), io.output) shouldBe 2
        io.stderr shouldContain "not an absolute http(s) URL"
    }

    test("a negative PLAINBASE_SMOKE_SOAK_GETS is a usage error (never a silent skip)") {
        val io = CommandOutputFixture()
        S3SmokeCommand.run(emptyList(), validEnv("PLAINBASE_SMOKE_SOAK_GETS" to "-1"), io.output) shouldBe 2
        io.stderr shouldContain "PLAINBASE_SMOKE_SOAK_GETS must be a non-negative integer"
    }

    test("a non-integer PLAINBASE_SMOKE_SOAK_GETS is a usage error (never a silent coerce to 100)") {
        val io = CommandOutputFixture()
        S3SmokeCommand.run(emptyList(), validEnv("PLAINBASE_SMOKE_SOAK_GETS" to "lots"), io.output) shouldBe 2
        io.stderr shouldContain "PLAINBASE_SMOKE_SOAK_GETS must be a non-negative integer"
    }

    test("missing required env is a usage error naming the gaps") {
        val io = CommandOutputFixture()
        S3SmokeCommand.run(emptyList(), emptyMap(), io.output) shouldBe 2
        io.stderr shouldContain "missing env"
    }

    test("any argument is a usage error (config comes from env, never argv)") {
        val io = CommandOutputFixture()
        S3SmokeCommand.run(listOf("--bogus"), validEnv(), io.output) shouldBe 2
        io.stderr shouldContain "usage: plainbase s3-smoke"
    }
})
