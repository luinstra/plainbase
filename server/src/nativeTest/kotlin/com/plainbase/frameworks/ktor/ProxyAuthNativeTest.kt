package com.plainbase.frameworks.ktor

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The A4b end-to-end native gate: over `withRestServices(proxyMode = true)`, a request with the secret header + a
 * clean `X-Forwarded-User` (the loopback peer counts as secure) resolves to a proxy-Human; `GET /api/v1/session`
 * returns 200 + a `pb_proxy_csrf` token; a proxy-Human mutation with the double-submit passes; a request with NO
 * secret → not authenticated (401 on a gated route). Proves the HMAC `Mac`, the `Source` branch, and the `app_meta`
 * HMAC-key load all work in the closed-world image. kotlin.test + @Tag("native") only.
 */
@Tag("native")
class ProxyAuthNativeTest {

    private val secret = "native-proxy-secret"

    @Test
    fun `proxy identity authenticates + the double-submit CSRF round-trips natively`() {
        withRestServices(proxyMode = true, seedProxyAdmin = "alice") { services ->
            testApplication {
                application { plainbaseModule(services) }
                val client = createClient { install(HttpCookies) }

                // The proxy-Human GET /session issues a token + sets pb_proxy_csrf, authMode=proxy.
                val session = client.get("/api/v1/session") {
                    header("X-Forwarded-User", "alice")
                    header(PROXY_SECRET_HEADER, secret)
                }
                assertEquals(HttpStatusCode.OK, session.status)
                val body = session.bodyAsText()
                assertTrue(body.contains("\"authenticated\":true"), "proxy human should be authenticated")
                assertTrue(body.contains("\"auth_mode\":\"proxy\""), "authMode should be proxy")
                val token = Regex("\"csrf_token\":\"([^\"]+)\"").find(body)!!.groupValues[1]

                // A proxy-Human mutation with the matching double-submit (cookie held by the client + the header) passes.
                val mint = client.post("/api/v1/admin/tokens") {
                    header("X-Forwarded-User", "alice")
                    header(PROXY_SECRET_HEADER, secret)
                    header("X-CSRF-Token", token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"label":"native-ci","mode":"read-only"}""")
                }
                assertEquals(HttpStatusCode.Created, mint.status)
                assertTrue(mint.bodyAsText().contains("pb_"), "mint returns the one-time plaintext")
            }
        }
    }

    @Test
    fun `a request with NO secret is not authenticated (401 on a gated route)`() {
        withRestServices(proxyMode = true, seedProxyAdmin = "alice") { services ->
            testApplication {
                application { plainbaseModule(services) }
                val response = client.get("/api/v1/admin/tokens") { header("X-Forwarded-User", "alice") }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }
    }
}
