package com.plainbase.frameworks.ktor

import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.IndexHarness
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.routes.X_CONTENT_TYPE_OPTIONS
import com.plainbase.frameworks.ktor.routes.bundleResourceBytes
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

private fun themeNamespaceTest(mode: AuthMode, block: suspend ApplicationTestBuilder.() -> Unit) {
    if (mode == AuthMode.OFF) {
        RestHarness(Fixtures.demoDocs, authMode = mode).use { harness ->
            testApplication {
                application { plainbaseModule(harness.services) }
                block()
            }
        }
    } else {
        IndexHarness(Fixtures.demoDocs, contentStore = LocalContentStore(Fixtures.demoDocs)).use { harness ->
            harness.builder.rebuild()
            val ctx = harness.testRouteContext(searchProvider = mockk<SearchProvider>(), enforced = true)
            testApplication {
                application { plainbaseModule(ctx) }
                block()
            }
        }
    }
}

private fun ByteArray.containsFallback(needle: ByteArray): Boolean =
    (0..size - needle.size).any { offset -> needle.indices.all { this[offset + it] == needle[it] } }

private fun assertNoFallbackLeak(response: HttpResponse, bytes: ByteArray, light: ByteArray, dark: ByteArray) {
    withClue("not served as CSS") {
        (response.contentType()?.withoutParameters() == ContentType.Text.CSS) shouldBe false
    }
    withClue("no fallback byte run") {
        (bytes.containsFallback(light) || bytes.containsFallback(dark)) shouldBe false
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
        "/_plainbase/plainbase-theme-dark.css", "/_plainbase/theme-light.css",
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
    listOf(AuthMode.OFF, AuthMode.BUILTIN).forEach { mode ->
        context("$mode") {
            val light = Files.readAllBytes(bundleDir().resolve("_plainbase/plainbase-theme-light.css"))
            val dark = Files.readAllBytes(bundleDir().resolve("_plainbase/plainbase-theme-dark.css"))
            listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Head).forEach { method ->
                paths.forEach { path ->
                    test("$method $path is fail-closed") {
                        themeNamespaceTest(mode) {
                            val response = restClient().request(path) { this.method = method }
                            val bytes = response.bodyAsBytes()
                            assertSoftly {
                                assertNoFallbackLeak(response, bytes, light, dark)
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
                    themeNamespaceTest(mode) {
                        val response = restClient().get(path)
                        val bytes = response.bodyAsBytes()
                        assertSoftly {
                            assertNoFallbackLeak(response, bytes, light, dark)
                            assertThemeFamily(response, bytes, family)
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
