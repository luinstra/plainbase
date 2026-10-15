package com.plainbase.frameworks.ktor

import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.theming.BundledThemeStylesheets
import com.plainbase.domain.theming.ThemeLeaf
import com.plainbase.domain.theming.ThemeMode
import com.plainbase.domain.theming.ThemeSelection
import com.plainbase.domain.theming.ThemeStylesheets
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.filesystem.LocalThemeStylesheets
import com.plainbase.frameworks.ktor.routes.X_CONTENT_TYPE_OPTIONS
import com.plainbase.frameworks.ktor.routes.bundleResourceBytes
import com.plainbase.frameworks.ktor.routes.embeddedThemeBuiltins
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files

private val themeEndpointPaths = listOf("/_plainbase/theme-light.css", "/_plainbase/theme-dark.css", "/_plainbase/custom.css")

private data class ThemeEndpointBytes(val expected: Map<String, ByteArray>, val needles: List<ByteArray>)

private fun themeEndpointTest(
    mode: AuthMode,
    bundled: Boolean = false,
    themes: ThemeStylesheets? = null,
    block: suspend ApplicationTestBuilder.(ThemeEndpointBytes) -> Unit,
) {
    val temporary = Files.createTempDirectory("pb-theme-endpoint")
    try {
        val content = Files.createDirectory(temporary.resolve("content"))
        val data = Files.createDirectory(temporary.resolve("data"))
        val paper = "body { --selected-paper: sentinel; }".encodeToByteArray()
        val custom = "body { --custom-override: sentinel; }".encodeToByteArray()
        Files.write(data.resolve("paper.css"), paper)
        Files.write(data.resolve("custom.css"), custom)
        val light = requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-light.css"))
        val dark = requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-dark.css"))
        val bytes = ThemeEndpointBytes(
            expected = mapOf(
                themeEndpointPaths[0] to if (bundled) light else paper,
                themeEndpointPaths[1] to dark,
                themeEndpointPaths[2] to if (bundled) ByteArray(0) else custom,
            ),
            needles = listOf(light, dark, paper, custom),
        )
        val port = themes ?: if (bundled) {
            BundledThemeStylesheets(embeddedThemeBuiltins())
        } else {
            LocalThemeStylesheets(data, ThemeSelection(requireNotNull(ThemeLeaf.of("paper.css")), null), embeddedThemeBuiltins())
        }
        if (mode == AuthMode.OFF) {
            RestHarness(content, authMode = mode, themes = port).use { harness ->
                testApplication {
                    application { plainbaseModule(harness.services) }
                    block(bytes)
                }
            }
        } else {
            IndexHarness(content).use { harness ->
                harness.builder.rebuild()
                val context = harness.testRouteContext(
                    searchProvider = mockk<SearchProvider>(),
                    authMode = mode,
                    proxySecret = if (mode == AuthMode.PROXY) "theme-endpoint-secret" else null,
                    enforced = true,
                    themes = port,
                )
                testApplication {
                    application { plainbaseModule(context) }
                    block(bytes)
                }
            }
        }
    } finally {
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private fun assertThemeCss(response: HttpResponse, bytes: ByteArray, expected: ByteArray) {
    assertSoftly {
        response.status shouldBe HttpStatusCode.OK
        response.headers.getAll(HttpHeaders.ContentType) shouldBe listOf("text/css; charset=UTF-8")
        response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
        response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
        response.headers.getAll(HttpHeaders.ETag) shouldBe null
        response.headers.getAll(HttpHeaders.LastModified) shouldBe null
        bytes shouldBe expected
    }
}

private fun assertThemeMethodNotAllowed(response: HttpResponse, bytes: ByteArray, needles: List<ByteArray>) {
    assertSoftly {
        response.status shouldBe HttpStatusCode.MethodNotAllowed
        response.headers.getAll(HttpHeaders.Allow) shouldBe listOf("GET")
        response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
        response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
        response.headers.getAll(HttpHeaders.ContentLength) shouldBe listOf("0")
        bytes shouldBe ByteArray(0)
        withClue("not served as CSS") {
            (response.contentType()?.withoutParameters() == ContentType.Text.CSS) shouldBe false
        }
        withClue("no fallback or sentinel byte run") {
            needles.any { needle ->
                (0..bytes.size - needle.size).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } }
            } shouldBe false
        }
    }
}

class ThemeEndpointTest : FunSpec({
    listOf(AuthMode.OFF, AuthMode.BUILTIN, AuthMode.PROXY).forEach { mode ->
        themeEndpointPaths.forEach { path ->
            test("$mode GET $path serves exact CSS") {
                themeEndpointTest(mode) { fixture ->
                    val response = restClient().get(path)
                    assertThemeCss(response, response.bodyAsBytes(), fixture.expected.getValue(path))
                }
            }
            test("$mode GET $path with a query serves the same exact CSS") {
                themeEndpointTest(mode) { fixture ->
                    val response = restClient().get("$path?v=1")
                    assertThemeCss(response, response.bodyAsBytes(), fixture.expected.getValue(path))
                }
            }
        }
    }

    listOf(AuthMode.OFF, AuthMode.BUILTIN, AuthMode.PROXY).forEach { mode ->
        themeEndpointPaths.forEach { path ->
            listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch, HttpMethod.Options).forEach { method ->
                test("$mode $method $path allows only GET with an empty 405") {
                    themeEndpointTest(mode) { fixture ->
                        val response = restClient().request(path) { this.method = method }
                        assertThemeMethodNotAllowed(response, response.bodyAsBytes(), fixture.needles)
                    }
                }
            }
        }
    }

    listOf(AuthMode.OFF, AuthMode.BUILTIN, AuthMode.PROXY).forEach { mode ->
        themeEndpointPaths.forEach { path ->
            test("$mode HEAD $path allows only GET with an empty 405") {
                themeEndpointTest(mode) { fixture ->
                    val response = restClient().request(path) { method = HttpMethod.Head }
                    assertThemeMethodNotAllowed(response, response.bodyAsBytes(), fixture.needles)
                }
            }
        }
    }

    themeEndpointPaths.forEach { path ->
        test("OFF bundled GET $path serves its complete definition or empty override") {
            themeEndpointTest(AuthMode.OFF, bundled = true) { fixture ->
                val response = restClient().get(path)
                assertThemeCss(response, response.bodyAsBytes(), fixture.expected.getValue(path))
            }
        }
    }

    test("OFF GET a throwing theme port keeps both response headers on the 500") {
        val themes = mockk<ThemeStylesheets>()
        every { themes.definition(ThemeMode.LIGHT) } throws IllegalStateException("injected theme failure")
        themeEndpointTest(AuthMode.OFF, themes = themes) {
            val response = restClient().get("/_plainbase/theme-light.css")
            response.bodyAsBytes()
            assertSoftly {
                response.status shouldBe HttpStatusCode.InternalServerError
                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
            }
        }
        verify(exactly = 1) { themes.definition(ThemeMode.LIGHT) }
    }
})
