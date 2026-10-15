package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.routes.X_CONTENT_TYPE_OPTIONS
import com.plainbase.frameworks.ktor.routes.bundleResourceBytes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Native path-decoding coverage for the fail-closed theme namespace and alternate bundle readers. */
@Tag("native")
class ThemeNamespaceNativeTest {

    @Test
    fun fallbackResourceIsEmbeddedInNativeImage() {
        val light = requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-light.css"))
        assertTrue(light.isNotEmpty())
    }

    @Test
    fun encodedAssetTraversalCannotReachFallback() = assertUnreachable(
        "/assets/..%2F_plainbase%2Fplainbase-theme-light.css",
        HttpStatusCode.BadRequest,
        "invalid_path",
    )

    @Test
    fun encodedFontTraversalCannotReachFallback() = assertUnreachable(
        "/fonts/..%2F_plainbase%2Fplainbase-theme-dark.css",
        HttpStatusCode.NotFound,
        "not_found",
    )

    @Test
    fun encodedNamespaceRemainsFailClosed() = assertUnreachable(
        "/%5Fplainbase/plainbase-theme-light.css",
        HttpStatusCode.NotFound,
        "not_found",
        namespace = true,
    )

    @Test
    fun namespaceDotSegmentCannotReachFallback() = assertUnreachable(
        "/_plainbase/%2e/plainbase-theme-light.css",
        HttpStatusCode.NotFound,
        "not_found",
        namespace = true,
    )

    @Test
    fun plainFallbackNameRemainsFailClosed() = assertUnreachable(
        "/_plainbase/plainbase-theme-light.css",
        HttpStatusCode.NotFound,
        "not_found",
        namespace = true,
    )

    private fun assertUnreachable(path: String, status: HttpStatusCode, errorCode: String, namespace: Boolean = false) {
        val light = requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-light.css"))
        val dark = requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-dark.css"))
        withRestServices { services ->
            testApplication {
                application { plainbaseModule(services) }
                val response = createClient { followRedirects = false }.get(path)
                val bytes = response.bodyAsBytes()
                val failures = mutableListOf<String>()
                fun check(label: String, assertion: () -> Unit) {
                    try {
                        assertion()
                    } catch (failure: AssertionError) {
                        failures += "$label: ${failure.message}"
                    }
                }

                check("not served as CSS") {
                    assertFalse(response.contentType()?.withoutParameters() == ContentType.Text.CSS)
                }
                val containsLight = bytes.containsContiguous(light)
                val containsDark = bytes.containsContiguous(dark)
                check("no fallback byte run") {
                    assertFalse(containsLight || containsDark, "light=$containsLight, dark=$containsDark")
                }
                check("status family") { assertEquals(status, response.status) }
                check("JSON content type") {
                    assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
                }
                val code = runCatching {
                    Json.parseToJsonElement(bytes.decodeToString()).jsonObject["error"]?.jsonObject
                        ?.get("code")?.jsonPrimitive?.content
                }.getOrNull()
                check("error envelope") { assertEquals(errorCode, code) }
                if (namespace) {
                    check("namespace cache policy") {
                        assertEquals(listOf("no-store"), response.headers.getAll(HttpHeaders.CacheControl))
                    }
                    check("namespace MIME defense") {
                        assertEquals(listOf("nosniff"), response.headers.getAll(X_CONTENT_TYPE_OPTIONS))
                    }
                }
                assertTrue(failures.isEmpty(), "$path\n${failures.joinToString("\n")}")
            }
        }
    }

    private fun ByteArray.containsContiguous(needle: ByteArray): Boolean =
        (0..size - needle.size).any { offset -> needle.indices.all { index -> this[offset + index] == needle[index] } }
}
