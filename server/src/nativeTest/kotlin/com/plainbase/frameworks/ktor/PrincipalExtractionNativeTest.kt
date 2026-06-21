package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import io.ktor.client.request.get
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

/**
 * The A1 principal-extraction seam returns [Principal.Anonymous] (no identity source is registered until
 * A2/A4a). Rides the native gate (kotlin.test, @Tag("native")) since it exercises the Ktor request path the
 * seam plugs into. Also asserts the existing open surface is unchanged by the seam's presence.
 */
@Tag("native")
class PrincipalExtractionNativeTest {

    @Test
    fun `a credential-less request resolves to Anonymous`() {
        testApplication {
            routing {
                get("/whoami") {
                    val principal = call.extractPrincipal()
                    call.respondText(if (principal is Principal.Anonymous) "anonymous" else principal.toString())
                }
            }
            val response = client.get("/whoami")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("anonymous", response.bodyAsText())
        }
    }

    @Test
    fun `the existing open surface still serves`() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }
            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        }
    }
}
