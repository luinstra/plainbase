package com.plainbase.frameworks.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

private const val UNPROTECTED_BIND_REFUSAL =
    "binds 0.0.0.0 with auth.mode=builtin but no TLS/trusted-proxy and no insecure override. " +
        "Remedies: (1) front with a TLS proxy and set PLAINBASE_TRUSTED_PROXY CIDRs; " +
        "(2) bind loopback (PLAINBASE_HOST=127.0.0.1) behind the proxy; " +
        "(3) set PLAINBASE_INSECURE_HTTP=1 to knowingly serve plaintext."

private const val INCOMPLETE_PROXY_REFUSAL =
    "auth.mode=proxy requires both a trusted-proxy allowlist and a shared secret. " +
        "Remedies: set PLAINBASE_TRUSTED_PROXY to the proxy's /32; set PLAINBASE_PROXY_SECRET to a shared value the proxy stamps."

/**
 * The ADR-0008 fail-closed bind guard, driven as the pure [PlainbaseConfig.bindGuardRefusal] predicate (no
 * socket bound, mirroring how [PlainbaseConfig.requireContentDir] is unit-tested). A non-null return is a
 * start refusal; null permits start.
 */
class BindGuardTest : FunSpec({

    fun config(host: String, auth: AuthConfig) = PlainbaseConfig(
        contentDir = Path.of("/tmp/content"),
        dataDir = Path.of("/tmp/data"),
        host = host,
        port = PlainbaseConfig.DEFAULT_PORT,
        auth = auth,
    )

    test("non-loopback bind + builtin + no proxy/override refuses, naming all three remedies") {
        val message = config("0.0.0.0", AuthConfig(mode = AuthMode.BUILTIN)).bindGuardRefusal()
        message shouldBe UNPROTECTED_BIND_REFUSAL
    }

    test("loopback bind + builtin is allowed") {
        config("127.0.0.1", AuthConfig(mode = AuthMode.BUILTIN)).bindGuardRefusal().shouldBeNull()
    }

    test("non-loopback bind + builtin + trusted-proxy CIDRs is allowed") {
        config("0.0.0.0", AuthConfig(mode = AuthMode.BUILTIN, trustedProxyCidrs = listOf("10.0.0.0/8")))
            .bindGuardRefusal().shouldBeNull()
    }

    test("non-loopback bind + builtin + insecure override is allowed") {
        config("0.0.0.0", AuthConfig(mode = AuthMode.BUILTIN, insecureHttp = true)).bindGuardRefusal().shouldBeNull()
    }

    // auth.mode=off is the MOST dangerous mode (fully unauthenticated): a non-loopback off bind without an
    // explicit override is the open internet serving an open surface, so the guard must NOT exempt it.
    test("non-loopback bind + auth off + no override refuses (off is not exempt — it is the most dangerous mode)") {
        val message = config("0.0.0.0", AuthConfig(mode = AuthMode.OFF)).bindGuardRefusal()
        message.shouldNotBeNull()
        message shouldContain "PLAINBASE_INSECURE_HTTP"
    }

    test("non-loopback bind + auth off + insecure override is allowed (explicit, knowing plaintext)") {
        config("0.0.0.0", AuthConfig(mode = AuthMode.OFF, insecureHttp = true)).bindGuardRefusal().shouldBeNull()
    }

    test("loopback bind + auth off is allowed (the open dev surface is preserved)") {
        config("127.0.0.1", AuthConfig(mode = AuthMode.OFF)).bindGuardRefusal().shouldBeNull()
    }

    test("proxy mode + non-loopback + no CIDRs still refuses (proxy declared but not allowlisted)") {
        config("0.0.0.0", AuthConfig(mode = AuthMode.PROXY)).bindGuardRefusal().shouldNotBeNull()
    }

    // A4b WI-2: a PROXY mode without BOTH a CIDR allowlist AND a secret is refused — even on a loopback bind (a
    // loopback proxy with no secret still trusts any loopback sibling). The refusal names both env vars.
    test("proxy mode + empty trustedProxyCidrs refuses before insecure override, with exact refusal") {
        val message = config(
            "127.0.0.1",
            AuthConfig(mode = AuthMode.PROXY, proxySecret = "fixture-proxy-secret", insecureHttp = true),
        ).bindGuardRefusal()
        message shouldBe INCOMPLETE_PROXY_REFUSAL
        message shouldNotContain "fixture-proxy-secret"
    }

    test("proxy mode + CIDRs but a blank/absent secret (even loopback) → refuse") {
        config("127.0.0.1", AuthConfig(mode = AuthMode.PROXY, trustedProxyCidrs = listOf("10.0.0.0/8")))
            .bindGuardRefusal().shouldNotBeNull()
        config("127.0.0.1", AuthConfig(mode = AuthMode.PROXY, trustedProxyCidrs = listOf("10.0.0.0/8"), proxySecret = "  "))
            .bindGuardRefusal().shouldNotBeNull()
    }

    test("proxy mode + CIDRs + a secret → null (boot permitted)") {
        config(
            "127.0.0.1",
            AuthConfig(
                mode = AuthMode.PROXY,
                trustedProxyCidrs = listOf("10.0.0.0/8"),
                proxySecret = "fixture-proxy-secret",
            ),
        )
            .bindGuardRefusal().shouldBeNull()
    }

    // The default host must be loopback so out-of-the-box `serve` is dev/off-safe; exposing demands an explicit
    // non-loopback PLAINBASE_HOST, which then trips the guard above (BLOCKING-1 fix).
    test("the default bind host is loopback (out-of-the-box serve never silently exposes)") {
        PlainbaseConfig.DEFAULT_HOST shouldBe "127.0.0.1"
        PlainbaseConfig.fromEnv(emptyMap()).host shouldBe "127.0.0.1"
        config(PlainbaseConfig.DEFAULT_HOST, AuthConfig(mode = AuthMode.OFF)).bindGuardRefusal().shouldBeNull()
        config(PlainbaseConfig.DEFAULT_HOST, AuthConfig(mode = AuthMode.BUILTIN)).bindGuardRefusal().shouldBeNull()
    }

    // WI-8: the session cookie's `Secure` mirrors the secure context — TLS-fronted iff non-loopback OR a trusted
    // proxy is declared (the canonical prod deployment is loopback-behind-TLS-proxy, which yields Secure=true). Only
    // pure loopback-dev with no proxy stays false (so http://localhost dev login still works).
    test("secureCookie: loopback + no proxy → false (dev http://localhost login works)") {
        config("127.0.0.1", AuthConfig(mode = AuthMode.BUILTIN)).secureCookie() shouldBe false
    }

    test("secureCookie: loopback + trusted proxy → true (canonical prod: loopback behind a TLS proxy)") {
        config("127.0.0.1", AuthConfig(mode = AuthMode.BUILTIN, trustedProxyCidrs = listOf("10.0.0.0/8")))
            .secureCookie() shouldBe true
    }

    test("non-loopback + insecure override permits bind but keeps secureCookie true") {
        val config = config("0.0.0.0", AuthConfig(mode = AuthMode.BUILTIN, insecureHttp = true))
        config.bindGuardRefusal().shouldBeNull()
        config.secureCookie() shouldBe true
    }

    // A1-amber: the guard returns null (PERMITS) whenever trustedProxyCidrs.isNotEmpty(), so a GARBAGE CIDR used to
    // defeat it — a plaintext bind to the world while no request ever matched the garbage CIDR. The fix moves the
    // refusal EARLIER: config LOAD fails fast on an unparseable CIDR, so the bind guard never even runs.
    test("a garbage CIDR + a non-loopback host → config load throws (the guard can no longer be defeated)") {
        val failure = shouldThrow<IllegalArgumentException> {
            PlainbaseConfig.fromEnv(mapOf("PLAINBASE_HOST" to "0.0.0.0", "PLAINBASE_TRUSTED_PROXY" to "not-a-cidr"))
        }
        failure.message shouldContain "not-a-cidr"
    }

    test("a no-prefix address as the only CIDR → config load throws (a bare address is not a CIDR)") {
        shouldThrow<IllegalArgumentException> {
            PlainbaseConfig.fromEnv(mapOf("PLAINBASE_HOST" to "0.0.0.0", "PLAINBASE_TRUSTED_PROXY" to "10.0.0.0"))
        }.message shouldContain "10.0.0.0"
    }

    test("an out-of-range prefix (/33) → config load throws") {
        shouldThrow<IllegalArgumentException> {
            PlainbaseConfig.fromEnv(mapOf("PLAINBASE_HOST" to "0.0.0.0", "PLAINBASE_TRUSTED_PROXY" to "10.0.0.0/33"))
        }.message shouldContain "10.0.0.0/33"
    }

    test("a garbage CIDR in PROXY mode also throws at load (before the bind guard runs)") {
        shouldThrow<IllegalArgumentException> {
            PlainbaseConfig.fromEnv(
                mapOf("PLAINBASE_AUTH_MODE" to "proxy", "PLAINBASE_PROXY_SECRET" to "s", "PLAINBASE_TRUSTED_PROXY" to "garbage"),
            )
        }.message shouldContain "garbage"
    }

    test("a valid /32 loads + the guard permits a non-loopback bind") {
        val cfg = PlainbaseConfig.fromEnv(mapOf("PLAINBASE_HOST" to "0.0.0.0", "PLAINBASE_TRUSTED_PROXY" to "10.0.0.1/32"))
        cfg.auth.trustedProxyCidrs shouldBe listOf("10.0.0.1/32")
        cfg.bindGuardRefusal().shouldBeNull()
    }
})
