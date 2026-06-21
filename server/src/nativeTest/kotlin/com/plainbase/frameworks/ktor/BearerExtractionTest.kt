package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A2 bearer extraction + the credential-conditional secure-context gate (WI 5). The gate decision is driven as
 * a pure function with controlled transport inputs (the test engine cannot easily spoof a socket peer); the
 * real Ktor accessor path is proven by a loopback `testApplication` round-trip that resolves a minted bearer to
 * [Principal.Agent]. Rides the native gate (kotlin.test, @Tag("native") — it exercises SHA-256 + SecureRandom +
 * SQLDelight + the Ktor request path under the image). Type checks use `is`/`when` (NOT `assertIs`, whose
 * `T::class` would hit unregistered `kotlin.reflect` metadata under the closed-world image).
 */
@Tag("native")
class BearerExtractionTest {

    private fun <T> withService(block: (ApiTokenService) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val hasher = TokenHasher()
            block(
                ApiTokenService(
                    minter = ApiTokenMinter(hasher),
                    hasher = hasher,
                    tokens = SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver)),
                    clock = Clock.System,
                ),
            )
        }

    /** The principal of a [PrincipalExtraction.Resolved], asserting the outcome was Resolved (not refused). */
    private fun PrincipalExtraction.resolvedPrincipal(): Principal {
        assertTrue(this is PrincipalExtraction.Resolved, "expected Resolved, got $this")
        return principal
    }

    private fun Principal.agentId(): String {
        assertTrue(this is Principal.Agent, "expected Agent, got $this")
        return tokenId
    }

    @Test
    fun `a valid bearer over loopback resolves to Agent with the bound id`() = withService { service ->
        val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
        val result = decidePrincipalExtraction(
            bearer = minted.plaintext,
            remoteHost = "127.0.0.1",
            forwardedProtoValues = emptyList(),
            trustedProxyCidrs = emptyList(),
            authenticate = service::authenticate,
        )
        assertEquals(minted.id, result.resolvedPrincipal().agentId())
    }

    @Test
    fun `a wrong-secret bearer over loopback resolves to Anonymous`() = withService { service ->
        val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
        val wrong = minted.plaintext.dropLast(2) + if (minted.plaintext.last() == 'A') "BB" else "AA"
        val result = decidePrincipalExtraction(wrong, "127.0.0.1", emptyList(), emptyList(), service::authenticate)
        assertEquals(Principal.Anonymous, result.resolvedPrincipal())
    }

    @Test
    fun `no bearer resolves to Anonymous - the gate does not fire`() {
        var calls = 0
        val result = decidePrincipalExtraction(null, "203.0.113.7", emptyList(), emptyList()) {
            calls++
            Principal.Anonymous
        }
        assertEquals(Principal.Anonymous, result.resolvedPrincipal())
        assertEquals(0, calls, "authenticate must not be called with no credential")
    }

    @Test
    fun `a non-pb bearer resolves to Anonymous, not misparsed`() {
        var calls = 0
        val result = decidePrincipalExtraction("abc", "127.0.0.1", emptyList(), emptyList()) {
            calls++
            Principal.Anonymous
        }
        assertEquals(Principal.Anonymous, result.resolvedPrincipal())
        assertEquals(0, calls, "a non-pb_ bearer is never authenticated")
    }

    @Test
    fun `a pb bearer over a non-secure transport is refused and the secret is never touched`() {
        var calls = 0
        val result = decidePrincipalExtraction(
            bearer = "pb_someid_c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2Vjcg",
            remoteHost = "203.0.113.7", // routable, non-loopback
            forwardedProtoValues = emptyList(), // no https
            trustedProxyCidrs = emptyList(), // no trusted proxy
        ) {
            calls++
            Principal.Anonymous
        }
        assertTrue(result is PrincipalExtraction.InsecureTransportRefused, "expected refusal, got $result")
        assertEquals(0, calls, "authenticate must NOT be called over a leaky transport")
    }

    @Test
    fun `a pb bearer over an allowlisted-proxy-https context is honored`() = withService { service ->
        val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
        val result = decidePrincipalExtraction(
            bearer = minted.plaintext,
            remoteHost = "10.1.2.3",
            forwardedProtoValues = listOf("https"),
            trustedProxyCidrs = listOf("10.0.0.0/8"),
            authenticate = service::authenticate,
        )
        assertEquals(minted.id, result.resolvedPrincipal().agentId())
    }

    @Test
    fun `the real Ktor extraction path resolves a minted bearer over loopback`() = withService { service ->
        val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
        testApplication {
            routing {
                get("/whoami") {
                    when (val result = call.extractPrincipal(service, trustedProxyCidrs = emptyList())) {
                        is PrincipalExtraction.Resolved ->
                            call.respondText((result.principal as? Principal.Agent)?.tokenId ?: "anonymous")
                        PrincipalExtraction.InsecureTransportRefused -> call.respondText("refused")
                    }
                }
            }
            val response = client.get("/whoami") { header("Authorization", "Bearer ${minted.plaintext}") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(minted.id, response.bodyAsText())
        }
    }
}
