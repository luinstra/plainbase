package com.plainbase.frameworks.ktor

import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.theming.ThemeLeaf
import com.plainbase.domain.theming.ThemeSelection
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalThemeStylesheets
import com.plainbase.frameworks.ktor.routes.X_CONTENT_TYPE_OPTIONS
import com.plainbase.frameworks.ktor.routes.bundleResourceBytes
import com.plainbase.frameworks.ktor.routes.embeddedThemeBuiltins
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
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
import io.ktor.http.withCharset
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

private enum class ThemeResponseFamily { INVALID_PATH, NOT_FOUND, NAMESPACE_NOT_FOUND, SHELL_NOT_FOUND, SHELL_OK }

private enum class EncodedThemeFamily { NAMESPACE_NOT_FOUND, SELECTED_CSS, METHOD_NOT_ALLOWED }

private data class ThemeNamespaceBytes(val needles: List<ByteArray>, val paper: ByteArray)

private fun themeNamespaceTest(mode: AuthMode, block: suspend ApplicationTestBuilder.(ThemeNamespaceBytes) -> Unit) {
    val data = Files.createTempDirectory("pb-theme-namespace")
    try {
        val paper = "body { --paper: zz-paper-sentinel; }".encodeToByteArray()
        val custom = "body { --custom: zz-custom-sentinel; }".encodeToByteArray()
        Files.write(data.resolve("paper.css"), paper)
        Files.write(data.resolve("custom.css"), custom)
        val light = Files.readAllBytes(bundleDir().resolve("_plainbase/plainbase-theme-light.css"))
        val dark = Files.readAllBytes(bundleDir().resolve("_plainbase/plainbase-theme-dark.css"))
        val fixture = ThemeNamespaceBytes(listOf(light, dark, paper, custom), paper)
        fixture.needles.forEach { it.isNotEmpty() shouldBe true }
        val themes = LocalThemeStylesheets(
            data,
            ThemeSelection(requireNotNull(ThemeLeaf.of("paper.css")), null),
            embeddedThemeBuiltins(),
        )
        if (mode == AuthMode.OFF) {
            RestHarness(Fixtures.demoDocs, authMode = mode, themes = themes).use { harness ->
                testApplication {
                    application { plainbaseModule(harness.services) }
                    block(fixture)
                }
            }
        } else {
            IndexHarness(Fixtures.demoDocs).use { harness ->
                harness.builder.rebuild()
                val ctx = harness.testRouteContext(
                    searchProvider = mockk<SearchProvider>(),
                    authMode = mode,
                    proxySecret = if (mode == AuthMode.PROXY) "theme-namespace-secret" else null,
                    enforced = true,
                    themes = themes,
                )
                testApplication {
                    application { plainbaseModule(ctx) }
                    block(fixture)
                }
            }
        }
    } finally {
        Files.walk(data).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private fun ByteArray.containsFallback(needle: ByteArray): Boolean =
    (0..size - needle.size).any { offset -> needle.indices.all { this[offset + it] == needle[it] } }

private fun assertNoFallbackLeak(response: HttpResponse, bytes: ByteArray, needles: List<ByteArray>) {
    withClue("not served as CSS") {
        (response.contentType()?.withoutParameters() == ContentType.Text.CSS) shouldBe false
    }
    withClue("no fallback byte run") {
        needles.any(bytes::containsFallback) shouldBe false
    }
}

private fun assertEncodedThemeFamily(
    response: HttpResponse,
    bytes: ByteArray,
    method: HttpMethod,
    family: EncodedThemeFamily,
    paper: ByteArray,
) {
    assertSoftly {
        response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
        response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
        when (family) {
            EncodedThemeFamily.NAMESPACE_NOT_FOUND -> {
                response.status shouldBe HttpStatusCode.NotFound
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                if (method != HttpMethod.Head) assertJsonFamily(response, bytes, HttpStatusCode.NotFound, "not_found")
            }
            EncodedThemeFamily.SELECTED_CSS -> {
                response.status shouldBe HttpStatusCode.OK
                response.contentType() shouldBe ContentType.Text.CSS.withCharset(Charsets.UTF_8)
                bytes shouldBe paper
            }
            EncodedThemeFamily.METHOD_NOT_ALLOWED -> {
                response.status shouldBe HttpStatusCode.MethodNotAllowed
                response.headers.getAll(HttpHeaders.Allow) shouldBe listOf("GET")
            }
        }
    }
}

private fun assertJsonFamily(response: HttpResponse, bytes: ByteArray, status: HttpStatusCode, errorCode: String) {
    response.status shouldBe status
    response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
    val code = runCatching {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject["error"]?.jsonObject
            ?.get("code")?.jsonPrimitive?.content
    }.getOrNull()
    code shouldBe errorCode
}

private fun assertThemeFamily(response: HttpResponse, bytes: ByteArray, family: ThemeResponseFamily) {
    when (family) {
        ThemeResponseFamily.INVALID_PATH -> assertJsonFamily(response, bytes, HttpStatusCode.BadRequest, "invalid_path")
        ThemeResponseFamily.NOT_FOUND -> assertJsonFamily(response, bytes, HttpStatusCode.NotFound, "not_found")
        ThemeResponseFamily.NAMESPACE_NOT_FOUND -> {
            assertJsonFamily(response, bytes, HttpStatusCode.NotFound, "not_found")
            response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
            response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
        }
        ThemeResponseFamily.SHELL_NOT_FOUND, ThemeResponseFamily.SHELL_OK -> {
            response.status shouldBe if (family == ThemeResponseFamily.SHELL_OK) HttpStatusCode.OK else HttpStatusCode.NotFound
            assertShellCachePolicy(response)
            bytes shouldBe requireNotNull(bundleResourceBytes("index.html"))
        }
    }
}

class ThemeNamespaceTest : FunSpec({
    val paths = listOf(
        "/_plainbase", "/_plainbase/", "/_plainbase/plainbase-theme-light.css",
        "/_plainbase/plainbase-theme-dark.css",
    )
    val probes = listOf(
        "/assets/..%2F_plainbase%2Fplainbase-theme-light.css" to ThemeResponseFamily.INVALID_PATH,
        "/assets/_plainbase/plainbase-theme-light.css" to ThemeResponseFamily.NOT_FOUND,
        "/fonts/..%2F_plainbase%2Fplainbase-theme-dark.css" to ThemeResponseFamily.NOT_FOUND,
        "/plainbase-theme-light.css" to ThemeResponseFamily.SHELL_NOT_FOUND,
        "/plainbase-theme-dark.css" to ThemeResponseFamily.SHELL_NOT_FOUND,
        "/plainbase-theme-generic.css.map" to ThemeResponseFamily.SHELL_NOT_FOUND,
        "/plainbase-theme-generic.css/" to ThemeResponseFamily.SHELL_NOT_FOUND,
        "/_plainbase%2Fplainbase-theme-light.css" to ThemeResponseFamily.SHELL_NOT_FOUND,
        "/%5Fplainbase/plainbase-theme-light.css" to ThemeResponseFamily.NAMESPACE_NOT_FOUND,
        "/_PLAINBASE/plainbase-theme-light.css" to ThemeResponseFamily.SHELL_NOT_FOUND,
        "/docs/_plainbase/plainbase-theme-light.css" to ThemeResponseFamily.SHELL_OK,
        "/_plainbase/%2e/plainbase-theme-light.css" to ThemeResponseFamily.NAMESPACE_NOT_FOUND,
        "/browse/_plainbase/plainbase-theme-light.css" to ThemeResponseFamily.NOT_FOUND,
        "/index.html%2F..%2F_plainbase%2Fplainbase-theme-light.css" to ThemeResponseFamily.SHELL_NOT_FOUND,
    )
    val neighbours = listOf(
        "/_plainbase/theme-light.css/", "/_plainbase/theme-light.css.map", "/_plainbase/THEME-LIGHT.css",
        "/_plainbase/theme-light", "/_plainbase/themes/theme-light.css", "/_plainbase/custom.css/",
        "/_plainbase/Custom.css", "/_plainbase/paper.css",
    )
    val encoded = listOf(
        "/%5Fplainbase/theme-light.css", "/_plainbase/theme-light%2Ecss", "/_plainbase/%2e/theme-light.css",
    )
    val allMethods = listOf(
        HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Head, HttpMethod.Patch, HttpMethod.Options,
    )
    listOf(AuthMode.OFF, AuthMode.BUILTIN, AuthMode.PROXY).forEach { mode ->
        context("$mode") {
            listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Head).forEach { method ->
                paths.forEach { path ->
                    test("$method $path is fail-closed") {
                        themeNamespaceTest(mode) { fixture ->
                            val response = restClient().request(path) { this.method = method }
                            val bytes = response.bodyAsBytes()
                            assertSoftly {
                                assertNoFallbackLeak(response, bytes, fixture.needles)
                                response.status shouldBe HttpStatusCode.NotFound
                                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                                response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
                                if (method != HttpMethod.Head) assertJsonFamily(response, bytes, HttpStatusCode.NotFound, "not_found")
                            }
                        }
                    }
                }
            }
            probes.forEach { (path, family) ->
                test("$path cannot expose fallback bytes") {
                    themeNamespaceTest(mode) { fixture ->
                        val response = restClient().get(path)
                        val bytes = response.bodyAsBytes()
                        assertSoftly {
                            assertNoFallbackLeak(response, bytes, fixture.needles)
                            assertThemeFamily(response, bytes, family)
                        }
                    }
                }
            }
            allMethods.forEach { method ->
                neighbours.forEach { path ->
                    test("$method $path is a namespace neighbour") {
                        themeNamespaceTest(mode) { fixture ->
                            val response = restClient().request(path) { this.method = method }
                            val bytes = response.bodyAsBytes()
                            assertSoftly {
                                assertNoFallbackLeak(response, bytes, fixture.needles)
                                response.status shouldBe HttpStatusCode.NotFound
                                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                                response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
                                if (method != HttpMethod.Head) assertJsonFamily(response, bytes, HttpStatusCode.NotFound, "not_found")
                            }
                        }
                    }
                }
                encoded.forEach { path ->
                    val family = when {
                        path == "/_plainbase/%2e/theme-light.css" -> EncodedThemeFamily.NAMESPACE_NOT_FOUND
                        method == HttpMethod.Get -> EncodedThemeFamily.SELECTED_CSS
                        else -> EncodedThemeFamily.METHOD_NOT_ALLOWED
                    }
                    test("$method $path has its observed encoded routing family") {
                        themeNamespaceTest(mode) { fixture ->
                            val response = restClient().request(path) { this.method = method }
                            assertEncodedThemeFamily(
                                response, response.bodyAsBytes(), method, family, fixture.paper,
                            )
                        }
                    }
                }
            }
            test("the generic sheet has exact cache policy and canonical classpath bytes") {
                themeNamespaceTest(mode) {
                    val response = restClient().get("/plainbase-theme-generic.css")
                    response.status shouldBe HttpStatusCode.OK
                    response.contentType() shouldBe ContentType.Text.CSS.withCharset(Charsets.UTF_8)
                    response.headers.getAll(X_CONTENT_TYPE_OPTIONS) shouldBe listOf("nosniff")
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-cache")
                    response.headers[HttpHeaders.ETag].shouldBeNull()
                    response.headers[HttpHeaders.LastModified].shouldBeNull()
                    response.bodyAsBytes() shouldBe requireNotNull(bundleResourceBytes("plainbase-theme-generic.css"))
                }
            }
            listOf("/favicon.svg", "/plainbase-logo.svg", "/plainbase-logo-dark.svg").forEach { path ->
                test("$path keeps Cache-Control absent") {
                    themeNamespaceTest(mode) {
                        val response = restClient().get(path)
                        response.status shouldBe HttpStatusCode.OK
                        response.headers.getAll(HttpHeaders.CacheControl).shouldBeNull()
                    }
                }
            }
        }
    }
})
