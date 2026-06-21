package com.plainbase.frameworks.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

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
        message.shouldNotBeNull()
        message shouldContain "loopback"
        message shouldContain "PLAINBASE_INSECURE_HTTP"
        message shouldContain "proxy"
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

    // The default host must be loopback so out-of-the-box `serve` is dev/off-safe; exposing demands an explicit
    // non-loopback PLAINBASE_HOST, which then trips the guard above (BLOCKING-1 fix).
    test("the default bind host is loopback (out-of-the-box serve never silently exposes)") {
        PlainbaseConfig.DEFAULT_HOST shouldBe "127.0.0.1"
        PlainbaseConfig.fromEnv(emptyMap()).host shouldBe "127.0.0.1"
        config(PlainbaseConfig.DEFAULT_HOST, AuthConfig(mode = AuthMode.OFF)).bindGuardRefusal().shouldBeNull()
        config(PlainbaseConfig.DEFAULT_HOST, AuthConfig(mode = AuthMode.BUILTIN)).bindGuardRefusal().shouldBeNull()
    }
})
