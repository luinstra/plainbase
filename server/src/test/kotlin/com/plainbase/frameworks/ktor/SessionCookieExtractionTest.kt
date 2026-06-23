package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.SessionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The A4a cookie source through the SAME `decidePrincipalExtraction` gate as the bearer (WI-8): a valid cookie over
 * a secure transport → Human; a revoked/absent cookie → Anonymous; a cookie over insecure transport →
 * InsecureTransportRefused with the session authenticate NEVER invoked (the secret-not-touched property); the
 * pinned bearer-wins precedence when both are present.
 */
class SessionCookieExtractionTest : FunSpec({

    val csrf = ByteArray(32) { 7 }
    fun human(userId: String) = SessionService.Authenticated(Principal.Human("builtin", userId), csrf)

    test("a valid cookie over loopback resolves to Human with the session's CSRF token") {
        val result = decidePrincipalExtraction(
            bearer = null,
            cookie = "cookie-value",
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = { human("u1") },
        )
        val resolved = result.shouldBeInstanceOf<PrincipalExtraction.Resolved>()
        resolved.principal shouldBe Principal.Human("builtin", "u1")
        resolved.csrfToken!!.contentEquals(csrf) shouldBe true
    }

    test("a revoked/absent cookie resolves to Anonymous") {
        decidePrincipalExtraction(null, "garbage", "127.0.0.1", emptyList(), emptyList(), { Principal.Anonymous }, { null })
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
    }

    test("no credential at all resolves to Anonymous (the gate does not fire)") {
        var cookieCalls = 0
        decidePrincipalExtraction(null, null, "203.0.113.7", emptyList(), emptyList(), { Principal.Anonymous }, {
            cookieCalls++
            null
        })
            .shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
        (cookieCalls == 0).shouldBeTrue()
    }

    test("a cookie over a non-secure transport is refused and the session is NEVER authenticated") {
        var cookieCalls = 0
        val result = decidePrincipalExtraction(
            bearer = null,
            cookie = "cookie-value",
            remoteHost = "203.0.113.7", // routable, non-loopback
            forwardedProtoValues = emptyList(), // no https
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = {
                cookieCalls++
                human("u1")
            },
        )
        result.shouldBeInstanceOf<PrincipalExtraction.InsecureTransportRefused>()
        (cookieCalls == 0).shouldBeTrue() // the cookie is NEVER hashed/looked-up over a leaky transport
    }

    test("a cookie over an allowlisted-proxy-https context is honored") {
        decidePrincipalExtraction(
            bearer = null,
            cookie = "cookie-value",
            remoteHost = "10.1.2.3",
            forwardedProtoValues = listOf("https"),
            trustedProxyCidrs = listOf("10.0.0.0/8"),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = { human("u1") },
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Human("builtin", "u1")
    }

    test("BEARER WINS when both a valid bearer and a cookie are present (pinned precedence)") {
        var cookieCalls = 0
        val result = decidePrincipalExtraction(
            bearer = "pb_abc",
            cookie = "cookie-value",
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Agent("token-id") },
            authenticateCookie = {
                cookieCalls++
                human("u1")
            },
        )
        result.shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Agent("token-id")
        (cookieCalls == 0).shouldBeTrue() // a winning bearer means the cookie is never consulted
    }

    test("a stray cookie falls through to cookie auth when the bearer does NOT resolve") {
        decidePrincipalExtraction(
            bearer = "pb_badtoken",
            cookie = "cookie-value",
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous }, // bearer rejected
            authenticateCookie = { human("u1") },
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Human("builtin", "u1")
    }

    test("a pb_session cookie is NOT resolved to Human when builtin auth is disabled (OFF/PROXY) — ignored as absent") {
        var cookieCalls = 0
        decidePrincipalExtraction(
            bearer = null,
            cookie = "cookie-value", // a leftover session cookie from a prior BUILTIN run
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = {
                cookieCalls++
                human("u1")
            },
            builtinAuthEnabled = false, // OFF/PROXY: the builtin auth surface is not live
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Anonymous
        (cookieCalls == 0).shouldBeTrue() // the cookie is never even authenticated — dropped up-front
    }

    test("the SAME cookie IS resolved to Human when builtin auth is enabled (BUILTIN)") {
        decidePrincipalExtraction(
            bearer = null,
            cookie = "cookie-value",
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = { human("u1") },
            builtinAuthEnabled = true,
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Human("builtin", "u1")
    }

    test("a bearer still authenticates in OFF/PROXY — only the cookie source is mode-gated, not the bearer") {
        decidePrincipalExtraction(
            bearer = "pb_abc",
            cookie = "cookie-value",
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Agent("token-id") },
            authenticateCookie = { human("u1") },
            builtinAuthEnabled = false,
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().principal shouldBe Principal.Agent("token-id")
    }
})
