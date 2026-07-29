package com.plainbase.frameworks.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native path-decoding gate for the post-S6 root route grammar.
 *
 * The client never follows redirects, because these rows pin the raw-path status and shell response.
 * The encoded-slash pair has a real in-chunk falsifier: relaxing PercentCoding's encoded-slash rejection
 * must make both rows red. The other rows are invariant or have their pre-S6 observation recorded by S5b.
 */
@Tag("native")
class RootPathDecodingNativeTest {

    private fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

    private suspend fun assertShell(response: io.ktor.client.statement.HttpResponse) {
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), response.contentType())
        assertTrue(response.bodyAsText().contains("<div id=\"root\">"))
    }

    @Test
    fun encodedRootSpellingIsAnInvariantAndServesTheSameShellPage() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }
            val client = noRedirectClient()
            val encoded = client.get("/%64ocs/guides/deploy-guide")
            val canonical = client.get("/docs/guides/deploy-guide")
            assertEquals(HttpStatusCode.OK, encoded.status)
            assertEquals(HttpStatusCode.OK, canonical.status)
            assertContentEquals(canonical.bodyAsBytes(), encoded.bodyAsBytes())
        }
    }

    @Test
    fun encodedSlashInARootTailIsRejected() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }
            assertShell(noRedirectClient().get("/docs%2Feng"))
        }
    }

    @Test
    fun encodedSlashInARootedPathIsRejected() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }
            assertShell(noRedirectClient().get("/docs/a%2Fb"))
        }
    }

    @Test
    fun invalidUtf8InARootedPathServesTheShell() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }
            assertShell(noRedirectClient().get("/docs/%C3%28"))
        }
    }

    @Test
    fun validMultiByteUtf8InARootedPathServesTheShell() = withRestServices { services ->
        testApplication {
            application { plainbaseModule(services) }
            val response = noRedirectClient().get("/docs/%E6%97%A5")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), response.contentType())
            assertTrue(response.bodyAsText().contains("<div id=\"root\">"))
        }
    }
}
