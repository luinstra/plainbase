package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.SessionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A4b WI-1: the explicit credential [Source] on a resolved principal — the discriminator the CSRF guard branches on,
 * replacing the implicit "null csrf token ⇒ exempt" signal the A4a forward-flag warned about. A cookie-resolved Human
 * carries [Source.COOKIE]; a bearer Agent and an Anonymous carry `source == null`. The COOKIE ⟺ non-null csrf
 * invariant (the `csrfToken!!` in enforceCsrf's COOKIE branch is safe ONLY because of this pairing) is asserted so a
 * future cookie path returning a null token can't silently NPE.
 */
class PrincipalExtractionSourceTest : FunSpec({

    val csrf = ByteArray(32) { 7 }
    fun human(id: String) = SessionService.Authenticated(Principal.Human("builtin", id), csrf)

    test("a cookie-resolved Human carries Source.COOKIE AND a non-null csrf token (the enforceCsrf invariant)") {
        val resolved = decidePrincipalExtraction(
            bearer = null,
            cookie = "cookie-value",
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = { human("u1") },
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>()
        resolved.source shouldBe Source.COOKIE
        (resolved.csrfToken != null) shouldBe true
    }

    test("a bearer-resolved Agent carries source == null (CSRF-exempt)") {
        decidePrincipalExtraction(
            bearer = "pb_abc",
            cookie = null,
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Agent("token-id") },
            authenticateCookie = { null },
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().source shouldBe null
    }

    test("an Anonymous resolution carries source == null") {
        decidePrincipalExtraction(
            bearer = null,
            cookie = null,
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticateBearer = { Principal.Anonymous },
            authenticateCookie = { null },
        ).shouldBeInstanceOf<PrincipalExtraction.Resolved>().source shouldBe null
    }
})
