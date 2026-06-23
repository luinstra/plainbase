package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The debate-REQUIRED test (synthesis §"WIDEN" 5): a credential presented over a non-secure transport is refused
 * 421 `transport_insecure` with the credential-GENERIC message (the latent-bug reword — the body must NO LONGER say
 * "bearer", now that a cookie is also a credential). Driven via an extraction that returns
 * [PrincipalExtraction.InsecureTransportRefused] (testApplication cannot spoof a non-loopback socket peer; the pure
 * gate's refusal decision is covered in `SessionCookieExtractionTest`). A secure-context request reaches the
 * handler (here: a login that 400s on a bad body — never the 421).
 */
class CookieRefusal421Test : FunSpec({

    test("an insecure-transport credential → 421 transport_insecure with a credential-generic message (not 'bearer')") {
        authRouteTest(extract = { PrincipalExtraction.InsecureTransportRefused }) {
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"pw"}""")
            }
            response.status.value shouldBe 421
            val body = response.bodyAsText()
            val error = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "transport_insecure"
            error["message"]!!.jsonPrimitive.content shouldNotContain "bearer" // the credential-generic reword
        }
    }

    test("over a secure transport the same login reaches the handler (no 421)") {
        authRouteTest { harness ->
            harness.seedUser("alice", "secret-pw", com.plainbase.domain.repository.Role.EDITOR)
            val response = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"secret-pw"}""")
            }
            (response.status.value != 421) shouldBe true
        }
    }
})
