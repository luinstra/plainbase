package com.plainbase.frameworks.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The CONTENT_DIR startup guard: serve must fail fast with an operator-actionable message that
 * NAMES the offending path — never the scan's bare `NoSuchFileException`, never a silently empty
 * tree.
 */
class PlainbaseConfigTest : FunSpec({

    fun configWith(contentDir: Path) = PlainbaseConfig(
        contentDir = contentDir,
        dataDir = contentDir.resolveSibling("data"),
        host = "127.0.0.1",
        port = PlainbaseConfig.DEFAULT_PORT,
    )

    test("a missing CONTENT_DIR fails fast with a message naming the path") {
        val parent = Files.createTempDirectory("pb-config")
        try {
            val missing = parent.resolve("does-not-exist")
            val failure = shouldThrow<IllegalArgumentException> { configWith(missing).requireContentDir() }
            failure.message shouldContain "CONTENT_DIR does not exist or is not a directory"
            failure.message shouldContain missing.toString()
        } finally {
            Files.deleteIfExists(parent)
        }
    }

    test("a CONTENT_DIR that is a regular file fails fast with the same actionable message") {
        val file = Files.createTempFile("pb-config", ".txt")
        try {
            val failure = shouldThrow<IllegalArgumentException> { configWith(file).requireContentDir() }
            failure.message shouldContain "CONTENT_DIR does not exist or is not a directory"
            failure.message shouldContain file.toString()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("an existing directory passes the guard and is returned") {
        val dir = Files.createTempDirectory("pb-config-content")
        try {
            configWith(dir).requireContentDir() shouldBe dir
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    test("DATA_DIR equal to CONTENT_DIR fails fast: app-owned state inside the watched root is a rebuild loop") {
        val dir = Files.createTempDirectory("pb-config-shared")
        try {
            val config = PlainbaseConfig(contentDir = dir, dataDir = dir, host = "127.0.0.1", port = PlainbaseConfig.DEFAULT_PORT)
            val failure = shouldThrow<IllegalArgumentException> { config.requireContentDir() }
            failure.message shouldContain "DATA_DIR and CONTENT_DIR must be different directories"
            failure.message shouldContain dir.toString()
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    // --- HOCON layer (ADR-0009): fromEnvAndFile reads DATA_DIR/plainbase.conf, env always wins ----------

    fun withDataDir(conf: String?, block: (Map<String, String>) -> Unit) {
        val data = Files.createTempDirectory("pb-config-hocon")
        try {
            if (conf != null) Files.writeString(data.resolve("plainbase.conf"), conf)
            block(mapOf("DATA_DIR" to data.toString()))
        } finally {
            Files.walk(data).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    test("fromEnvAndFile reads a value from plainbase.conf") {
        withDataDir("auth { mode = builtin }") { env ->
            PlainbaseConfig.fromEnvAndFile(env).auth.mode shouldBe AuthMode.BUILTIN
        }
    }

    test("env overrides the file for a string key (auth.mode)") {
        withDataDir("auth { mode = builtin }") { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_AUTH_MODE" to "proxy"))
            config.auth.mode shouldBe AuthMode.PROXY
        }
    }

    test("env overrides the file for a list key (auth.trustedProxy)") {
        withDataDir("""auth { trustedProxy = ["10.0.0.0/8"] }""") { env ->
            val fileOnly = PlainbaseConfig.fromEnvAndFile(env)
            fileOnly.auth.trustedProxyCidrs shouldBe listOf("10.0.0.0/8")

            val overridden = PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_TRUSTED_PROXY" to "192.168.0.0/16, 172.16.0.0/12"))
            overridden.auth.trustedProxyCidrs shouldBe listOf("192.168.0.0/16", "172.16.0.0/12")
        }
    }

    test("a key absent from both env and file falls to the default") {
        withDataDir(conf = null) { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env)
            config.auth.mode shouldBe AuthMode.OFF
            config.auth.trustedProxyCidrs shouldBe emptyList()
            config.auth.insecureHttp shouldBe false
            config.auth.agentDirectCommitGlobs shouldBe emptyList()
            // A4b: proxy fields default to no secret + the X-Forwarded-User identity header.
            config.auth.proxySecret shouldBe null
            config.auth.proxyIdentityHeader shouldBe "X-Forwarded-User"
        }
    }

    // A4b WI-2: the proxy secret + identity-header config (env-wins over file over the default).
    test("PLAINBASE_PROXY_SECRET reads from env; the identity header defaults to X-Forwarded-User") {
        PlainbaseConfig.fromEnv(mapOf("PLAINBASE_PROXY_SECRET" to "s3cr3t")).auth.proxySecret shouldBe "s3cr3t"
        PlainbaseConfig.fromEnv(emptyMap()).auth.proxyIdentityHeader shouldBe "X-Forwarded-User"
    }

    test("PLAINBASE_PROXY_IDENTITY_HEADER env-wins over file over the default; a blank config falls back") {
        withDataDir("""auth { proxyIdentityHeader = "X-Auth-Request-User" }""") { env ->
            PlainbaseConfig.fromEnvAndFile(env).auth.proxyIdentityHeader shouldBe "X-Auth-Request-User"
            PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_PROXY_IDENTITY_HEADER" to "X-SSO-Subject"))
                .auth.proxyIdentityHeader shouldBe "X-SSO-Subject"
            // A blank env value falls back to the default, never an empty header name.
            PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_PROXY_IDENTITY_HEADER" to "   "))
                .auth.proxyIdentityHeader shouldBe "X-Forwarded-User"
        }
    }

    test("a missing plainbase.conf is a clean no-op: fromEnvAndFile equals fromEnv field-for-field") {
        withDataDir(conf = null) { env ->
            PlainbaseConfig.fromEnvAndFile(env) shouldBe PlainbaseConfig.fromEnv(env)
        }
    }

    test("a malformed auth.mode fails fast naming the legal values") {
        withDataDir("auth { mode = nonsense }") { env ->
            val failure = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            failure.message shouldContain "auth.mode"
            failure.message shouldContain "off"
            failure.message shouldContain "builtin"
            failure.message shouldContain "proxy"
        }
    }

    // --- env-wins strictness (MINOR-5): a PRESENT env key is authoritative — a malformed value fails fast, it
    //     never silently falls through to file/default (which is the opposite of env-always-wins) -------------

    test("the default bind host is loopback (out-of-the-box serve never silently exposes)") {
        PlainbaseConfig.fromEnv(emptyMap()).host shouldBe PlainbaseConfig.DEFAULT_HOST
        PlainbaseConfig.DEFAULT_HOST shouldBe "127.0.0.1"
    }

    test("a present-but-malformed PLAINBASE_PORT fails fast (never falls through to the file/default)") {
        withDataDir("port = 9000") { env ->
            val failure = shouldThrow<IllegalArgumentException> {
                PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_PORT" to "80x0"))
            }
            failure.message shouldContain "PLAINBASE_PORT"
        }
    }

    test("a present-but-malformed PLAINBASE_MAX_WRITE_BODY_BYTES fails fast") {
        shouldThrow<IllegalArgumentException> {
            PlainbaseConfig.fromEnv(mapOf("PLAINBASE_MAX_WRITE_BODY_BYTES" to "lots"))
        }.message shouldContain "PLAINBASE_MAX_WRITE_BODY_BYTES"
    }

    test("a present-but-non-positive PLAINBASE_MAX_ASSET_BYTES fails fast") {
        shouldThrow<IllegalArgumentException> {
            PlainbaseConfig.fromEnv(mapOf("PLAINBASE_MAX_ASSET_BYTES" to "0"))
        }.message shouldContain "PLAINBASE_MAX_ASSET_BYTES"
    }

    test("a present-but-malformed PLAINBASE_INSECURE_HTTP fails fast (no silent coercion to false)") {
        shouldThrow<IllegalArgumentException> {
            PlainbaseConfig.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "yes"))
        }.message shouldContain "PLAINBASE_INSECURE_HTTP"
    }

    test("PLAINBASE_INSECURE_HTTP=1 activates the override (the value the bind-guard message documents works)") {
        PlainbaseConfig.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "1")).auth.insecureHttp shouldBe true
        PlainbaseConfig.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "true")).auth.insecureHttp shouldBe true
        PlainbaseConfig.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "0")).auth.insecureHttp shouldBe false
    }
})
