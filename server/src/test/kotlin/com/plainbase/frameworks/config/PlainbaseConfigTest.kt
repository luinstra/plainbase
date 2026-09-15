package com.plainbase.frameworks.config

import com.typesafe.config.ConfigException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The CONTENT_DIR startup guard: serve must fail fast with an operator-actionable message that
 * NAMES the offending path - never the scan's bare `NoSuchFileException`, never a silently empty
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
            ConfigLoader.fromEnvAndFile(env).auth.mode shouldBe AuthMode.BUILTIN
        }
    }

    test("env overrides the file for a string key (auth.mode)") {
        withDataDir("auth { mode = builtin }") { env ->
            val config = ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_AUTH_MODE" to "proxy"))
            config.auth.mode shouldBe AuthMode.PROXY
        }
    }

    test("env overrides the file for a list key (auth.trustedProxy)") {
        withDataDir("""auth { trustedProxy = ["10.0.0.0/8"] }""") { env ->
            val fileOnly = ConfigLoader.fromEnvAndFile(env)
            fileOnly.auth.trustedProxyCidrs shouldBe listOf("10.0.0.0/8")

            val overridden = ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_TRUSTED_PROXY" to "192.168.0.0/16, 172.16.0.0/12"))
            overridden.auth.trustedProxyCidrs shouldBe listOf("192.168.0.0/16", "172.16.0.0/12")
        }
    }

    test("a key absent from both env and file falls to the default") {
        withDataDir(conf = null) { env ->
            val config = ConfigLoader.fromEnvAndFile(env)
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
        ConfigLoader.fromEnv(mapOf("PLAINBASE_PROXY_SECRET" to "s3cr3t")).auth.proxySecret shouldBe "s3cr3t"
        ConfigLoader.fromEnv(emptyMap()).auth.proxyIdentityHeader shouldBe "X-Forwarded-User"
    }

    test("PLAINBASE_PROXY_IDENTITY_HEADER env-wins over file over the default; a blank config falls back") {
        withDataDir("""auth { proxyIdentityHeader = "X-Auth-Request-User" }""") { env ->
            ConfigLoader.fromEnvAndFile(env).auth.proxyIdentityHeader shouldBe "X-Auth-Request-User"
            ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_PROXY_IDENTITY_HEADER" to "X-SSO-Subject"))
                .auth.proxyIdentityHeader shouldBe "X-SSO-Subject"
            // A blank env value falls back to the default, never an empty header name.
            ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_PROXY_IDENTITY_HEADER" to "   "))
                .auth.proxyIdentityHeader shouldBe "X-Forwarded-User"
        }
    }

    test("proxy secret falls back to the file and env overrides it") {
        withDataDir("""auth { proxySecret = "file-secret" }""") { env ->
            ConfigLoader.fromEnvAndFile(env).auth.proxySecret shouldBe "file-secret"
            ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_PROXY_SECRET" to "env-secret"))
                .auth.proxySecret shouldBe "env-secret"
        }
    }

    test("a missing plainbase.conf is a clean no-op: fromEnvAndFile equals fromEnv field-for-field") {
        withDataDir(conf = null) { env ->
            ConfigLoader.fromEnvAndFile(env) shouldBe ConfigLoader.fromEnv(env)
        }
    }

    test("a malformed auth.mode fails fast naming the legal values") {
        withDataDir("auth { mode = nonsense }") { env ->
            val failure = shouldThrow<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
            failure.message shouldContain "auth.mode"
            failure.message shouldContain "off"
            failure.message shouldContain "builtin"
            failure.message shouldContain "proxy"
        }
    }

    test("loader preserves contentDir, insecure, storage, roots/history, port, then auth failure order") {
        val base = Files.createTempDirectory("pb-config-order")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("content").toString())
            val file = data.resolve("plainbase.conf")

            Files.writeString(file, "contentDir = []")
            val contentFailure = shouldThrow<ConfigException.WrongType> {
                ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_INSECURE_HTTP" to "yes"))
            }
            contentFailure.message!!.replace(data.toString(), "<DATA_DIR>") shouldBe
                "<DATA_DIR>/plainbase.conf: 1: contentDir has type LIST rather than STRING"

            Files.writeString(
                file,
                """
                contentDir = "${base.resolve("content")}"
                storage.backend = "object"
                roots { notes { path = "${base.resolve("notes")}" } }
                """.trimIndent(),
            )
            val insecureFailure = shouldThrow<IllegalArgumentException> {
                ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_INSECURE_HTTP" to "yes"))
            }
            insecureFailure.message shouldBe "PLAINBASE_INSECURE_HTTP must be one of 1/0/true/false, got 'yes'"

            val storageFailure = shouldThrow<IllegalArgumentException> {
                ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_INSECURE_HTTP" to "0"))
            }
            storageFailure.message shouldBe
                "storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)"

            val rootsFailure = shouldThrow<IllegalArgumentException> {
                ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_STORAGE_BACKEND" to "local") + ("PLAINBASE_PORT" to "bad"))
            }
            rootsFailure.message shouldBe
                "roots {} must declare a root named 'docs' (the required, reserved primary): roots.docs { path = ... }"

            Files.writeString(
                file,
                """
                roots { docs { path = "${base.resolve("docs")}", history = native } }
                """.trimIndent(),
            )
            val historyFailure = shouldThrow<IllegalArgumentException> {
                ConfigLoader.fromEnvAndFile(
                    env +
                        ("PLAINBASE_GIT_ENABLED" to "false") +
                        ("PLAINBASE_PORT" to "bad") +
                        ("PLAINBASE_AUTH_MODE" to "not-a-mode"),
                )
            }
            historyFailure.message shouldBe
                "roots.docs.history = native and git.enabled = false contradict each other: one claims primary's git " +
                "repository, the other turns git off. Set exactly one of them."

            Files.writeString(
                file,
                """
                roots {
                  docs {
                    path = "${base.resolve("docs")}"
                    history = off
                  }
                }
                """.trimIndent(),
            )
            val portFailure = shouldThrow<IllegalArgumentException> {
                ConfigLoader.fromEnvAndFile(
                    env + ("PLAINBASE_PORT" to "bad") + ("PLAINBASE_AUTH_MODE" to "not-a-mode"),
                )
            }
            portFailure.message shouldBe "PLAINBASE_PORT must be an integer, got 'bad'"
            shouldThrow<IllegalArgumentException> {
                ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_PORT" to "8080") + ("PLAINBASE_AUTH_MODE" to "not-a-mode"))
            }.message shouldBe "Unknown auth.mode 'not-a-mode' - legal values: off, builtin, proxy"
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    test("auth mode precedes CIDRs, which precede main direct-commit globs") {
        val env = emptyMap<String, String>()
        val modeFailure = shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(
                env +
                    ("PLAINBASE_AUTH_MODE" to "not-a-mode") +
                    ("PLAINBASE_TRUSTED_PROXY" to "not-a-cidr") +
                    ("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "bad/../glob"),
            )
        }
        modeFailure.message shouldBe "Unknown auth.mode 'not-a-mode' - legal values: off, builtin, proxy"

        val cidrFailure = shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(
                env +
                    ("PLAINBASE_TRUSTED_PROXY" to "not-a-cidr") +
                    ("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "bad/../glob"),
            )
        }
        cidrFailure.message shouldBe
            "PLAINBASE_TRUSTED_PROXY contains an unparseable CIDR: 'not-a-cidr' (expected a.b.c.d/n or IPv6/n)"

        val globFailure = shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(
                env +
                    ("PLAINBASE_TRUSTED_PROXY" to "10.0.0.0/8") +
                    ("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "bad/../glob"),
            )
        }
        globFailure.message shouldBe "agentDirectCommit glob must not contain a '.' or '..' segment: 'bad/../glob'"
    }

    test("an env main-glob override still eagerly reads a wrong-typed file glob spelling") {
        val base = Files.createTempDirectory("pb-config-glob-eager")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            listOf("auth.agentDirectCommit.globs", "auth.agentDirectCommit.roots.docs").forEach { spelling ->
                Files.writeString(data.resolve("plainbase.conf"), "$spelling = 17")
                val failure = shouldThrow<ConfigException.WrongType> {
                    ConfigLoader.fromEnvAndFile(
                        mapOf(
                            "DATA_DIR" to data.toString(),
                            "PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "docs/**",
                        ),
                    )
                }
                failure.message!!.replace(data.toString(), "<DATA_DIR>") shouldBe
                    "<DATA_DIR>/plainbase.conf: 1: $spelling has type NUMBER rather than LIST"
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    // --- env-wins strictness (MINOR-5): a PRESENT env key is authoritative - a malformed value fails fast, it
    //     never silently falls through to file/default (which is the opposite of env-always-wins) -------------

    test("the default bind host is loopback (out-of-the-box serve never silently exposes)") {
        ConfigLoader.fromEnv(emptyMap()).host shouldBe PlainbaseConfig.DEFAULT_HOST
        PlainbaseConfig.DEFAULT_HOST shouldBe "127.0.0.1"
    }

    test("a present-but-malformed PLAINBASE_PORT fails fast (never falls through to the file/default)") {
        withDataDir("port = 9000") { env ->
            val failure = shouldThrow<IllegalArgumentException> {
                ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_PORT" to "80x0"))
            }
            failure.message shouldContain "PLAINBASE_PORT"
        }
    }

    test("a present-but-malformed PLAINBASE_MAX_WRITE_BODY_BYTES fails fast") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_MAX_WRITE_BODY_BYTES" to "lots"))
        }.message shouldContain "PLAINBASE_MAX_WRITE_BODY_BYTES"
    }

    test("a present-but-non-positive PLAINBASE_MAX_ASSET_BYTES fails fast") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_MAX_ASSET_BYTES" to "0"))
        }.message shouldContain "PLAINBASE_MAX_ASSET_BYTES"
    }

    test("a present-but-malformed PLAINBASE_INSECURE_HTTP fails fast (no silent coercion to false)") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "yes"))
        }.message shouldContain "PLAINBASE_INSECURE_HTTP"
    }

    test("PLAINBASE_INSECURE_HTTP=1 activates the override (the value the bind-guard message documents works)") {
        ConfigLoader.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "1")).auth.insecureHttp shouldBe true
        ConfigLoader.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "true")).auth.insecureHttp shouldBe true
        ConfigLoader.fromEnv(mapOf("PLAINBASE_INSECURE_HTTP" to "0")).auth.insecureHttp shouldBe false
    }

    // P5: agentDirectCommit.globs are validated at LOAD (the requireParseableCidrs idiom) - a malformed pattern fails
    // fast naming it, and a valid set survives unchanged + parses to a CommitGlob via the accessor.
    test("a malformed PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS entry fails fast at load naming the bad pattern") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "docs/**, secrets/../etc"))
        }.message shouldContain "secrets/../etc"
    }

    test("valid agentDirectCommit.globs survive load and parse to CommitGlobs via the accessor") {
        val config = ConfigLoader.fromEnv(mapOf("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "docs/**, guides/*.md"))
        config.auth.agentDirectCommitGlobs shouldBe listOf("docs/**", "guides/*.md")
        config.agentDirectCommitGlobs().size shouldBe 2
        config.agentDirectCommitGlobs().first().matches(com.plainbase.domain.content.TreePath.require("docs/a/b.md")) shouldBe true
    }

    // --- B3: HOCON substitutions resolve (ADR-0009). ConfigResolveOptions.defaults() resolves within-file refs and
    //     falls back to the JVM system ENVIRONMENT (not system properties); the optional `${?…}` form drops silently
    //     when its var is unset (a bare `${…}` would throw by design - the supported form is the optional one) ------

    test("an optional \${?…} substitution for an UNSET var parses without throwing and falls to the default") {
        // PLAINBASE_HOST_FROM_FILE is not set in the test env, so the optional substitution drops to absent; before
        // .resolve() this threw ConfigException.NotResolved at the first typed getter.
        withDataDir("host = \${?PLAINBASE_HOST_FROM_FILE}") { env ->
            ConfigLoader.fromEnvAndFile(env).host shouldBe PlainbaseConfig.DEFAULT_HOST
        }
    }

    test("a WITHIN-FILE substitution resolves: the value flows through (proves .resolve() ran)") {
        // A within-file ref needs no env/props (parseFile().resolve(defaults()) falls back to ENV, not properties),
        // so this exercises the resolve() call path the absent-\${?…} case alone may skip.
        withDataDir("proxyHost = \"127.0.0.1\"\nhost = \${proxyHost}") { env ->
            ConfigLoader.fromEnvAndFile(env).host shouldBe "127.0.0.1"
        }
    }

    // --- A1-amber: a malformed trustedProxyCidrs entry fails fast at config load (never silently dropped, which
    //     would shrink/empty the allowlist and flip the fail-closed bind guard) ----------------------------------

    test("a mix of valid + invalid CIDRs fails fast (not filtered), naming the offending value") {
        val failure = shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_TRUSTED_PROXY" to "10.0.0.0/8, not-a-cidr"))
        }
        failure.message shouldContain "PLAINBASE_TRUSTED_PROXY"
        failure.message shouldContain "not-a-cidr"
    }

    test("a no-prefix address (no /n) fails fast: a bare address is not a CIDR") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_TRUSTED_PROXY" to "10.0.0.0"))
        }.message shouldContain "10.0.0.0"
    }

    test("an out-of-range prefix (/33) fails fast") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_TRUSTED_PROXY" to "10.0.0.0/33"))
        }.message shouldContain "10.0.0.0/33"
    }

    test("a single valid CIDR parses; an empty/absent PLAINBASE_TRUSTED_PROXY loads with emptyList") {
        ConfigLoader.fromEnv(mapOf("PLAINBASE_TRUSTED_PROXY" to "192.168.0.0/16")).auth.trustedProxyCidrs shouldBe
            listOf("192.168.0.0/16")
        ConfigLoader.fromEnv(emptyMap()).auth.trustedProxyCidrs shouldBe emptyList()
    }

    test("a malformed CIDR in the FILE also fails fast at load") {
        withDataDir("""auth { trustedProxy = ["bad-cidr"] }""") { env ->
            shouldThrow<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }.message shouldContain "bad-cidr"
        }
    }

    // --- file-side boolStrict parity (A1 minor): a typo'd bool in the FILE throws, like the env path (no swallow) ---

    test("a typo'd bool in plainbase.conf fails fast (parity with the env boolStrict)") {
        withDataDir("""auth { insecureHttp = "yes" }""") { env ->
            shouldThrow<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }.message shouldContain "auth.insecureHttp"
        }
    }

    test("a well-formed bool in plainbase.conf still parses (1/0/true/false)") {
        withDataDir("""auth { insecureHttp = "1" }""") { env ->
            ConfigLoader.fromEnvAndFile(env).auth.insecureHttp shouldBe true
        }
        withDataDir("""git { enabled = "false" }""") { env ->
            ConfigLoader.fromEnvAndFile(env).git.enabled shouldBe false
        }
    }

    // --- Q9 storage matrix: strict backend parse, object-mode required keys (tabled messages verbatim),
    //     local-mode ignored+warn tracking (never fatal), Q10 CONTENT_DIR source tracking --------------------

    fun objectEnv(vararg overrides: Pair<String, String>): Map<String, String> = mapOf(
        "PLAINBASE_STORAGE_BACKEND" to "object",
        "PLAINBASE_S3_ENDPOINT" to "https://acct.r2.cloudflarestorage.com",
        "PLAINBASE_S3_BUCKET" to "docs",
        "PLAINBASE_S3_ACCESS_KEY_ID" to "AKIA",
        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "shh",
    ) + overrides

    test("storage.backend defaults to local with the Q9 defaults (region auto, no prefix, path-style, 60s poll)") {
        val storage = ConfigLoader.fromEnv(emptyMap()).storage
        storage.backend shouldBe StorageBackend.LOCAL
        storage.region shouldBe "auto"
        storage.prefix shouldBe ""
        storage.pathStyle shouldBe true
        storage.pollSeconds shouldBe 60L
    }

    test("an unknown storage.backend fails fast naming the legal values (verbatim)") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(mapOf("PLAINBASE_STORAGE_BACKEND" to "nfs"))
        }.message shouldBe "Unknown storage.backend 'nfs' - legal values: local, object"
    }

    test("object mode with the full required set loads; credentials come from env") {
        val storage = ConfigLoader.fromEnv(objectEnv()).storage
        storage.backend shouldBe StorageBackend.OBJECT
        storage.endpoint shouldBe "https://acct.r2.cloudflarestorage.com"
        storage.bucket shouldBe "docs"
        storage.accessKeyId shouldBe "AKIA"
        storage.secretAccessKey shouldBe "shh"
    }

    test(
        "storage.backend=object set ONLY in plainbase.conf resolves through fromEnvAndFile (the loader the " +
            "DATA_DIR-sharing CLIs adopt/reindex now share with serve) - the env-only fast path would read it as local (B1)",
    ) {
        val conf = """
            storage {
              backend = object
              object {
                endpoint = "https://acct.r2.cloudflarestorage.com"
                bucket = "docs"
              }
            }
        """.trimIndent()
        withDataDir(conf) { env ->
            // Credentials stay env-only (secrets never in the file); backend/endpoint/bucket come from the file.
            val creds = env + ("PLAINBASE_S3_ACCESS_KEY_ID" to "k") + ("PLAINBASE_S3_SECRET_ACCESS_KEY" to "s")
            val fromFile = ConfigLoader.fromEnvAndFile(creds)
            fromFile.storage.backend shouldBe StorageBackend.OBJECT
            fromFile.storage.endpoint shouldBe "https://acct.r2.cloudflarestorage.com"
            fromFile.storage.bucket shouldBe "docs"
            // The old CLI entry point (fromEnv) ignores the file entirely and silently falls back to LOCAL -
            // exactly the wrong-authority regression B1 fixes by switching adopt/reindex to fromEnvAndFile.
            ConfigLoader.fromEnv(creds).storage.backend shouldBe StorageBackend.LOCAL
        }
    }

    test("object mode without an endpoint fails with the tabled message (verbatim)") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv() - "PLAINBASE_S3_ENDPOINT")
        }.message shouldBe "storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)"
    }

    test("a non-absolute / non-http endpoint fails with the tabled message (verbatim)") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv("PLAINBASE_S3_ENDPOINT" to "acct.r2.cloudflarestorage.com"))
        }.message shouldBe "storage.object.endpoint is not an absolute http(s) URL: 'acct.r2.cloudflarestorage.com'"
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv("PLAINBASE_S3_ENDPOINT" to "ftp://acct.example.com"))
        }.message shouldBe "storage.object.endpoint is not an absolute http(s) URL: 'ftp://acct.example.com'"
    }

    test("object mode without a bucket fails with the tabled message (verbatim)") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv() - "PLAINBASE_S3_BUCKET")
        }.message shouldBe "storage.object.bucket is required when storage.backend=object"
    }

    test("object mode without env credentials fails with the combined env-only message (verbatim, either half)") {
        val expected = "PLAINBASE_S3_ACCESS_KEY_ID and PLAINBASE_S3_SECRET_ACCESS_KEY are required when " +
            "storage.backend=object (secrets stay in env, never plainbase.conf)"
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv() - "PLAINBASE_S3_SECRET_ACCESS_KEY")
        }.message shouldBe expected
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv() - "PLAINBASE_S3_ACCESS_KEY_ID")
        }.message shouldBe expected
    }

    test("credentials are ENV-ONLY: endpoint+bucket from plainbase.conf still demand the env credentials") {
        val conf = """
            storage {
              backend = object
              object {
                endpoint = "https://acct.r2.cloudflarestorage.com"
                bucket = "docs"
              }
            }
        """.trimIndent()
        withDataDir(conf) { env ->
            shouldThrow<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
                .message shouldContain "PLAINBASE_S3_ACCESS_KEY_ID"
            // With the env credentials supplied, the same file-backed config loads.
            val loaded = ConfigLoader.fromEnvAndFile(
                env + ("PLAINBASE_S3_ACCESS_KEY_ID" to "k") + ("PLAINBASE_S3_SECRET_ACCESS_KEY" to "s"),
            )
            loaded.storage.bucket shouldBe "docs"
        }
    }

    test("loadForCommand funnels a bad object-mode config (IAE) into a clean <cmd>: message + null, never a stack trace (R2-2)") {
        val errors = mutableListOf<String>()
        val expected = "storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)"
        val result = ConfigLoader.loadForCommand("serve", err = errors::add) {
            // backend=object with no endpoint -> IllegalArgumentException out of build(); the funnel must catch it.
            ConfigLoader.fromEnv(mapOf("PLAINBASE_STORAGE_BACKEND" to "object"))
        }
        result shouldBe null
        errors.single() shouldBe "serve: $expected"
    }

    test("loadForCommand also funnels a malformed plainbase.conf (HOCON ConfigException), not just IAE (R2-2)") {
        withDataDir("storage { backend = ") { env ->
            // unclosed brace -> ConfigException.Parse from Typesafe Config
            val failure = shouldThrow<ConfigException.Parse> { ConfigLoader.fromEnvAndFile(env) }
            val errors = mutableListOf<String>()
            val result = ConfigLoader.loadForCommand("admin", err = errors::add) { ConfigLoader.fromEnvAndFile(env) }
            result shouldBe null
            errors.single() shouldBe "admin: ${failure.message}"
        }
    }

    test("loadForCommand lets unrelated IO failures escape and preserves successful resolver identity") {
        val ioFailure = IOException("injected loader IO failure")
        val errors = mutableListOf<String>()
        val escaped = shouldThrow<IOException> {
            ConfigLoader.loadForCommand("serve", err = errors::add) { throw ioFailure }
        }
        (escaped === ioFailure) shouldBe true
        errors shouldBe emptyList()

        val expected = ConfigLoader.fromEnv(emptyMap())
        var calls = 0
        val resolved = ConfigLoader.loadForCommand("serve", err = errors::add) {
            calls += 1
            expected
        }
        (resolved === expected) shouldBe true
        calls shouldBe 1
    }

    test("a malformed storage.object.prefix fails the TreePath funnel naming the key") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv("PLAINBASE_S3_PREFIX" to "docs/../etc"))
        }.message shouldContain "storage.object.prefix"
    }

    test("prefix/pathStyle/pollSeconds parse strictly in object mode") {
        val storage = ConfigLoader.fromEnv(
            objectEnv("PLAINBASE_S3_PREFIX" to "team/docs", "PLAINBASE_S3_PATH_STYLE" to "0", "PLAINBASE_S3_POLL_SECONDS" to "30"),
        ).storage
        storage.prefix shouldBe "team/docs"
        storage.pathStyle shouldBe false
        storage.pollSeconds shouldBe 30L
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv("PLAINBASE_S3_POLL_SECONDS" to "0"))
        }.message shouldContain "PLAINBASE_S3_POLL_SECONDS"
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv("PLAINBASE_S3_PATH_STYLE" to "yes"))
        }.message shouldContain "PLAINBASE_S3_PATH_STYLE"
    }

    test("local mode ignores storage.object.* keys, naming them in the one startup warning (never fatal)") {
        // Note the MALFORMED pathStyle: in local mode nothing object-side is parsed, so it cannot throw.
        val config = ConfigLoader.fromEnv(
            mapOf("PLAINBASE_S3_ENDPOINT" to "https://x.example.com", "PLAINBASE_S3_PATH_STYLE" to "definitely-not-a-bool"),
        )
        config.storage.backend shouldBe StorageBackend.LOCAL
        config.storage.ignoredObjectKeys shouldBe listOf("PLAINBASE_S3_ENDPOINT", "PLAINBASE_S3_PATH_STYLE")
        val warning = config.storageWarnings().single()
        warning shouldBe
            "storage.backend=local ignores the configured object-storage key(s): PLAINBASE_S3_ENDPOINT, " +
            "PLAINBASE_S3_PATH_STYLE (set storage.backend=object to use them)"
    }

    test("valid local-mode object keys remain ignored and are recorded by their source") {
        withDataDir(
            """
            storage {
              object {
                endpoint = "https://acct.example.com"
                bucket = "docs"
                region = "auto"
                prefix = "team/docs"
                pathStyle = true
                pollSeconds = 30
              }
            }
            """.trimIndent(),
        ) { env ->
            val config = ConfigLoader.fromEnvAndFile(env)
            config.storage.backend shouldBe StorageBackend.LOCAL
            config.storage.ignoredObjectKeys shouldBe listOf(
                "storage.object.endpoint",
                "storage.object.bucket",
                "storage.object.region",
                "storage.object.prefix",
                "storage.object.pathStyle",
                "storage.object.pollSeconds",
            )
            config.storageWarnings() shouldBe listOf(
                "storage.backend=local ignores the configured object-storage key(s): " +
                    "storage.object.endpoint, storage.object.bucket, storage.object.region, storage.object.prefix, " +
                    "storage.object.pathStyle, storage.object.pollSeconds (set storage.backend=object to use them)",
            )
        }
    }

    test("credentials present in local mode are ignored SILENTLY (never named, no warning)") {
        val config = ConfigLoader.fromEnv(mapOf("PLAINBASE_S3_ACCESS_KEY_ID" to "k", "PLAINBASE_S3_SECRET_ACCESS_KEY" to "s"))
        config.storage.ignoredObjectKeys shouldBe emptyList<String>()
        config.storageWarnings() shouldBe emptyList<String>()
    }

    test("file-side storage.object.* keys in local mode are tracked by their HOCON path") {
        withDataDir("""storage { object { bucket = "docs" } }""") { env ->
            ConfigLoader.fromEnvAndFile(env).storage.ignoredObjectKeys shouldBe listOf("storage.object.bucket")
        }
    }

    test("contentDirSource tracks env over file over default (Q10)") {
        ConfigLoader.fromEnv(mapOf("CONTENT_DIR" to "/tmp/pb-env-tree")).contentDirSource shouldBe ConfigSource.ENV
        withDataDir("""contentDir = "/tmp/pb-file-tree"""") { env ->
            ConfigLoader.fromEnvAndFile(env).contentDirSource shouldBe ConfigSource.FILE
            ConfigLoader.fromEnvAndFile(env + ("CONTENT_DIR" to "/tmp/pb-env-tree")).contentDirSource shouldBe ConfigSource.ENV
        }
        ConfigLoader.fromEnv(emptyMap()).contentDirSource shouldBe ConfigSource.DEFAULT
    }

    test("object mode warns when CONTENT_DIR was explicitly set; stays silent on the default") {
        ConfigLoader.fromEnv(objectEnv("CONTENT_DIR" to "/tmp/pb-tree")).storageWarnings().single() shouldContain "CONTENT_DIR"
        ConfigLoader.fromEnv(objectEnv()).storageWarnings() shouldBe emptyList<String>()
    }

    test("default roots are constructed once; copy contentDir retains them without validation") {
        val originalContent = Path.of("/missing/original-content")
        val replacementContent = Path.of("/missing/replacement-content")
        val original = PlainbaseConfig(
            contentDir = originalContent,
            dataDir = Path.of("/missing/data"),
            host = "127.0.0.1",
            port = PlainbaseConfig.DEFAULT_PORT,
        )

        original.roots.origin shouldBe RootsOrigin.SYNTHESIZED
        original.roots.primary.localPath shouldBe originalContent
        (original.roots === original.roots) shouldBe true
        val copied = original.copy(contentDir = replacementContent)
        copied.contentDir shouldBe replacementContent
        (copied.roots === original.roots) shouldBe true
        copied.roots.primary.localPath shouldBe originalContent

        val explicitRoots = RootsConfig.synthesized(replacementContent, copied.storage)
        copied.copy(roots = explicitRoots).roots.primary.localPath shouldBe replacementContent
    }

    test("an http object endpoint is refused (cleartext SigV4) unless the insecure override is set") {
        shouldThrow<IllegalArgumentException> {
            ConfigLoader.fromEnv(objectEnv("PLAINBASE_S3_ENDPOINT" to "http://acct.example.com"))
        }.message shouldContain "must be https"
        // The SAME PLAINBASE_INSECURE_HTTP override the bind guard honors relaxes it (a loopback test proxy).
        ConfigLoader.fromEnv(objectEnv("PLAINBASE_S3_ENDPOINT" to "http://acct.example.com", "PLAINBASE_INSECURE_HTTP" to "1"))
            .storage.endpoint shouldBe "http://acct.example.com"
        // https is always accepted.
        ConfigLoader.fromEnv(objectEnv()).storage.endpoint shouldBe "https://acct.r2.cloudflarestorage.com"
    }

    test("requireContentDir in object mode ignores the directory and validates the Q9 matrix instead") {
        // A CONTENT_DIR that does not exist must NOT fail in object mode (it is ignored, Q10)...
        ConfigLoader.fromEnv(objectEnv("CONTENT_DIR" to "/definitely/not/here")).requireContentDir()
        // ...while a directly-constructed object config missing its required keys is re-asserted here.
        val bare = PlainbaseConfig(
            contentDir = Path.of("/tmp"),
            dataDir = Path.of("/tmp/pb-data"),
            host = "127.0.0.1",
            port = PlainbaseConfig.DEFAULT_PORT,
            storage = StorageConfig(backend = StorageBackend.OBJECT),
        )
        shouldThrow<IllegalArgumentException> { bare.requireContentDir() }.message shouldContain "storage.object.endpoint"
    }

    // --- P3 MCP DNS-rebinding allowlist (WI-5): fail-closed to the bind host, never empty, never a wildcard ---

    test("no MCP keys → mcpHostAllowlist defaults to the bind host (not empty, not a wildcard)") {
        val allowlist = ConfigLoader.fromEnv(mapOf("PLAINBASE_HOST" to "127.0.0.1")).mcpHostAllowlist()
        allowlist.shouldNotBeEmpty()
        allowlist shouldContain "127.0.0.1"
        allowlist.none { it == "*" || it == "0.0.0.0" } shouldBe true // fail-closed: never a wildcard
    }

    test("a non-loopback bind defaults the MCP host allowlist to that bind host (+ loopback), still no wildcard") {
        val allowlist = ConfigLoader.fromEnv(mapOf("PLAINBASE_HOST" to "docs.example.com")).mcpHostAllowlist()
        allowlist shouldContain "docs.example.com"
        allowlist.none { it == "*" || it == "0.0.0.0" } shouldBe true
    }

    test("an explicit PLAINBASE_MCP_ALLOWED_HOSTS overrides the default") {
        val config = ConfigLoader.fromEnv(mapOf("PLAINBASE_MCP_ALLOWED_HOSTS" to "docs.example.com, proxy.example.com"))
        config.auth.mcpAllowedHosts shouldBe listOf("docs.example.com", "proxy.example.com")
        config.mcpHostAllowlist() shouldContain "docs.example.com"
    }

    test("no MCP keys → mcpOriginAllowlist defaults to the bind-host origins (not empty, not a wildcard)") {
        val allowlist = ConfigLoader.fromEnv(mapOf("PLAINBASE_HOST" to "127.0.0.1")).mcpOriginAllowlist()
        allowlist.shouldNotBeEmpty()
        allowlist.none { it == "*" } shouldBe true // fail-closed: never a wildcard
    }

    test("an explicit PLAINBASE_MCP_ALLOWED_ORIGINS overrides the default") {
        val config = ConfigLoader.fromEnv(
            mapOf("PLAINBASE_MCP_ALLOWED_ORIGINS" to "https://docs.example.com, https://proxy.example.com"),
        )
        config.auth.mcpAllowedOrigins shouldBe listOf("https://docs.example.com", "https://proxy.example.com")
        config.mcpOriginAllowlist() shouldContain "https://docs.example.com"
    }

    test("MCP defaults preserve host and origin order for loopback and routable binds") {
        val loopback = PlainbaseConfig(
            contentDir = Path.of("/tmp/content"),
            dataDir = Path.of("/tmp/data"),
            host = "127.0.0.1",
            port = 8080,
        )
        loopback.mcpHostAllowlist() shouldBe listOf("127.0.0.1", "localhost")
        loopback.mcpOriginAllowlist() shouldBe listOf(
            "http://127.0.0.1:8080",
            "https://127.0.0.1:8080",
            "http://localhost:8080",
        )

        val routable = loopback.copy(host = "docs.example.com")
        routable.mcpHostAllowlist() shouldBe listOf("docs.example.com", "127.0.0.1", "localhost")
        routable.mcpOriginAllowlist() shouldBe listOf(
            "http://docs.example.com:8080",
            "https://docs.example.com:8080",
            "http://127.0.0.1:8080",
            "http://localhost:8080",
        )

        val localhost = loopback.copy(host = "localhost")
        localhost.mcpHostAllowlist() shouldBe listOf("localhost", "127.0.0.1")
        localhost.mcpOriginAllowlist() shouldBe listOf(
            "http://localhost:8080",
            "https://localhost:8080",
            "http://127.0.0.1:8080",
        )
    }

    test("directly constructed MCP overrides preserve order and duplicates") {
        val config = PlainbaseConfig(
            contentDir = Path.of("/tmp/content"),
            dataDir = Path.of("/tmp/data"),
            host = "127.0.0.1",
            port = 8080,
            auth = AuthConfig(
                mcpAllowedHosts = listOf("proxy.example.com", "proxy.example.com", "docs.example.com"),
                mcpAllowedOrigins = listOf("https://proxy.example.com", "https://proxy.example.com", "https://docs.example.com"),
            ),
        )
        config.mcpHostAllowlist() shouldBe listOf("proxy.example.com", "proxy.example.com", "docs.example.com")
        config.mcpOriginAllowlist() shouldBe listOf(
            "https://proxy.example.com",
            "https://proxy.example.com",
            "https://docs.example.com",
        )
    }
})
