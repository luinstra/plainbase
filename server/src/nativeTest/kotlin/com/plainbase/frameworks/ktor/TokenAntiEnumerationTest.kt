package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Anti-enumeration at the HTTP boundary (A2 WI 6, §0.2 / R2-finding-4): an unknown id and a valid-id-but-wrong
 * secret produce a BYTE-IDENTICAL response (no oracle reveals which prefixes are live), AND both paths run the
 * constant-time compare exactly once (the unknown-id path does NOT early-out — it compares against the dummy
 * hash). The both-paths-once proof is STRUCTURAL (a counting spy on the hasher), never a flaky wall-clock
 * measurement. Rides the native gate.
 */
@Tag("native")
class TokenAntiEnumerationTest {

    /** Counts the constant-time compares, delegating to the real scheme. */
    private class CountingHasher(private val delegate: TokenSecretHasher) : TokenSecretHasher {
        val verifies = AtomicInteger(0)
        override fun hash(secret: ByteArray): ByteArray = delegate.hash(secret)
        override fun verify(secret: ByteArray, storedHash: ByteArray?): Boolean {
            verifies.incrementAndGet()
            return delegate.verify(secret, storedHash)
        }
    }

    /** The test route mirrors how A3 will map the extraction outcome to a uniform response. */
    private fun Application.tokenRoute(service: ApiTokenService) {
        routing {
            get("/whoami") {
                when (val result = call.extractPrincipal(service, trustedProxyCidrs = emptyList())) {
                    is PrincipalExtraction.Resolved ->
                        if (result.principal is Principal.Agent) {
                            call.respondText("ok")
                        } else {
                            // Unknown id AND wrong secret BOTH land here → identical status+body.
                            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    PrincipalExtraction.InsecureTransportRefused ->
                        call.respondText("insecure", status = HttpStatusCode.UpgradeRequired)
                    is PrincipalExtraction.ProxyIdentityRejected ->
                        call.respondText("proxy-rejected", status = HttpStatusCode.BadRequest)
                }
            }
        }
    }

    @Test
    fun `unknown-id and wrong-secret are HTTP-indistinguishable and both run the compare once`() {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val counting = CountingHasher(TokenHasher())
            val service = ApiTokenService(
                minter = ApiTokenMinter(TokenHasher()),
                hasher = counting,
                tokens = SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver)),
                clock = Clock.System,
            )
            val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
            counting.verifies.set(0)

            testApplication {
                application { tokenRoute(service) }

                // A valid-format wrong secret / unknown id (16-hex id + 43-char base64url) so both reach the compare.
                val validSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

                // (a) a syntactically valid but UNKNOWN id
                val unknown = client.get("/whoami") {
                    header("Authorization", "Bearer pb_00112233445566ff_$validSecret")
                }
                val afterUnknown = counting.verifies.get()

                // (b) the VALID id with a WRONG secret
                val wrongSecretToken = "pb_${minted.id}_$validSecret"
                val wrong = client.get("/whoami") { header("Authorization", "Bearer $wrongSecretToken") }

                assertEquals(unknown.status, wrong.status)
                assertContentEquals(unknown, wrong)
                // The compare ran exactly once per request (no early-out on the unknown-id path).
                assertEquals(1, afterUnknown, "unknown-id path must run the compare once")
                assertEquals(2, counting.verifies.get(), "wrong-secret path must also run the compare once")
            }
        }
    }
}

private suspend fun assertContentEquals(a: HttpResponse, b: HttpResponse) {
    assertTrue(a.bodyAsBytes().contentEquals(b.bodyAsBytes()), "responses differ in body bytes")
}
