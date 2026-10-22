package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.SessionService
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.protocol.RestJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.Called
import io.mockk.spyk
import io.mockk.verify
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReaderRouteSurfaceTest : FunSpec({
    test("live tree equals the reader surface contract") {
        readerTest(Fixtures.demoDocs) { harness ->
            routeInventory(harness.services) shouldBe ReaderSurfaceContract
        }
    }

    test("reader credentials preserve every probed response without touching credential services") {
        lateinit var tokenSpy: ApiTokenService
        lateinit var sessionSpy: SessionService
        val recorded = mutableListOf<PrincipalExtraction>()
        readerTest(
            Fixtures.demoDocs,
            decorateTokens = { spyk(it).also { tokenSpy = it } },
            decorateSessions = { spyk(it).also { sessionSpy = it } },
            context = { ctx -> ctx.withExtract({ ctx.extract(this).also(recorded::add) }) },
        ) { harness ->
            routeInventory(harness.services) shouldBe ReaderSurfaceContract
            val page = harness.builder.current.pages.single { it.path.value == "guides/deploy-guide.md" }
            val getProbes = listOf(
                ReaderCredentialProbe("GET /healthz", "/healthz", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /api/v1/session", "/api/v1/session", HttpStatusCode.OK),
                ReaderCredentialProbe(
                    "GET /api/v1/pages/by-path/{path...}",
                    "/api/v1/pages/by-path/docs/${requireNotNull(page.urlPath).value}",
                    HttpStatusCode.OK,
                ),
                ReaderCredentialProbe("GET /api/v1/pages/{id}", "/api/v1/pages/${page.id.value}", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /api/v1/pages/{id}/html", "/api/v1/pages/${page.id.value}/html", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /api/v1/pages/{id}/metadata", "/api/v1/pages/${page.id.value}/metadata", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /api/v1/tree", "/api/v1/tree", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /api/v1/search", "/api/v1/search?q=deploy", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /assets/{path...}", "/assets/docs/infra/assets/diagram.svg", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /p/{segments...}", "/p/docs/${page.id.value}", HttpStatusCode.Found),
                ReaderCredentialProbe("GET /browse/{path...}", "/browse/docs/${page.path.value}", HttpStatusCode.Found),
                ReaderCredentialProbe("GET /{root}", "/docs", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /{root}/{path...}", requireNotNull(page.url), HttpStatusCode.OK),
                ReaderCredentialProbe("GET /", "/", HttpStatusCode.Found),
                ReaderCredentialProbe("GET /apple-touch-icon.png", "/apple-touch-icon.png", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /favicon.svg", "/favicon.svg", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /index.html", "/index.html", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /plainbase-logo-dark.svg", "/plainbase-logo-dark.svg", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /plainbase-logo.svg", "/plainbase-logo.svg", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /plainbase-theme-generic.css", "/plainbase-theme-generic.css", HttpStatusCode.OK),
                ReaderCredentialProbe("GET /fonts/{file}", "/fonts/ibm-plex-sans-400.woff2", HttpStatusCode.OK),
            )
            getProbes.map { it.label }.toSet() shouldBe ReaderSurfaceContract.filter { it.startsWith("GET ") }.toSet()
            val anyProbes = listOf(
                ReaderCredentialProbe("ANY /api/{...}", "/api/x", HttpStatusCode.NotFound),
                ReaderCredentialProbe("ANY /api/{...}", "/api/x", HttpStatusCode.NotFound, HttpMethod.Post),
                ReaderCredentialProbe("ANY /api/{...}", "/api/x", HttpStatusCode.NotFound, HttpMethod.Options),
                ReaderCredentialProbe("ANY /_plainbase/theme-light.css", "/_plainbase/theme-light.css", HttpStatusCode.OK),
                ReaderCredentialProbe(
                    "ANY /_plainbase/theme-light.css", "/_plainbase/theme-light.css", HttpStatusCode.MethodNotAllowed, HttpMethod.Post,
                ),
                ReaderCredentialProbe(
                    "ANY /_plainbase/theme-light.css", "/_plainbase/theme-light.css", HttpStatusCode.MethodNotAllowed, HttpMethod.Options,
                ),
                ReaderCredentialProbe("ANY /_plainbase/theme-dark.css", "/_plainbase/theme-dark.css", HttpStatusCode.OK),
                ReaderCredentialProbe(
                    "ANY /_plainbase/theme-dark.css", "/_plainbase/theme-dark.css", HttpStatusCode.MethodNotAllowed, HttpMethod.Post,
                ),
                ReaderCredentialProbe(
                    "ANY /_plainbase/theme-dark.css", "/_plainbase/theme-dark.css", HttpStatusCode.MethodNotAllowed, HttpMethod.Options,
                ),
                ReaderCredentialProbe("ANY /_plainbase/custom.css", "/_plainbase/custom.css", HttpStatusCode.OK),
                ReaderCredentialProbe(
                    "ANY /_plainbase/custom.css", "/_plainbase/custom.css", HttpStatusCode.MethodNotAllowed, HttpMethod.Post,
                ),
                ReaderCredentialProbe(
                    "ANY /_plainbase/custom.css", "/_plainbase/custom.css", HttpStatusCode.MethodNotAllowed, HttpMethod.Options,
                ),
                ReaderCredentialProbe("ANY /_plainbase/{...}", "/_plainbase/x", HttpStatusCode.NotFound),
                ReaderCredentialProbe("ANY /_plainbase/{...}", "/_plainbase/x", HttpStatusCode.NotFound, HttpMethod.Post),
                ReaderCredentialProbe("ANY /_plainbase/{...}", "/_plainbase/x", HttpStatusCode.NotFound, HttpMethod.Options),
            )
            anyProbes.map { it.label }.toSet() shouldBe ReaderSurfaceContract.filter { it.startsWith("ANY ") }.toSet()
            val probes = getProbes.flatMap { listOf(it, it.copy(method = HttpMethod.Head)) } + anyProbes + listOf(
                ReaderCredentialProbe("unmounted page create", "/api/v1/pages", HttpStatusCode.NotFound, HttpMethod.Post),
                ReaderCredentialProbe("unmounted changes", "/api/v1/changes", HttpStatusCode.NotFound),
                ReaderCredentialProbe("unmounted admin shell", "/admin", HttpStatusCode.NotFound),
            )
            val token = harness.index.apiTokens.mint("probe", AgentMode.COMMIT)
            val session = harness.index.sessionService.create("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val credentials = mapOf(
                HttpHeaders.Authorization to "Bearer ${token.plaintext}",
                HttpHeaders.Cookie to "$SESSION_COOKIE_NAME=${session.plaintext}",
                "X-Forwarded-User" to "alice",
                PROXY_SECRET_HEADER to "s",
            )
            val variants = credentials.map { (name, value) -> name to mapOf(name to value) } + ("all credentials" to credentials)
            val http = restClient()
            probes.forEach { probe ->
                withClue("${probe.method.value} ${probe.path}") {
                    val bare = http.request(probe.path) { method = probe.method }
                    bare.status shouldBe probe.status
                    val bareBody = bare.bodyAsBytes()
                    bare.headers.getAll(HttpHeaders.SetCookie) shouldBe null
                    variants.forEach { (label, headers) ->
                        val response = http.request(probe.path) {
                            method = probe.method
                            headers.forEach { (name, value) -> header(name, value) }
                        }
                        val body = response.bodyAsBytes()
                        withClue(label) {
                            assertSoftly {
                                response.status shouldBe bare.status
                                response.headersWithoutDate() shouldBe bare.headersWithoutDate()
                                body shouldBe bareBody
                                response.headers.getAll(HttpHeaders.SetCookie) shouldBe null
                            }
                        }
                    }
                }
            }
            val tokenUntouched = runCatching { verify { tokenSpy wasNot Called } }.isSuccess
            val sessionUntouched = runCatching { verify { sessionSpy wasNot Called } }.isSuccess
            assertSoftly {
                recorded.toSet() shouldBe setOf(PrincipalExtraction.Resolved(Principal.Anonymous))
                withClue("token spy untouched") { tokenUntouched shouldBe true }
                withClue("session spy untouched") { sessionUntouched shouldBe true }
                requireNotNull(harness.index.apiTokenRepository.findById(token.id)).lastUsedAt shouldBe null
            }
        }
    }

    listOf("/docs", "/api/v1/tree", "/api/v1/session").forEach { path ->
        test("reader malformed session cookie preserves GET $path") {
            readerTest(Fixtures.demoDocs) { _ ->
                val http = restClient()
                val bare = http.get(path)
                bare.status shouldBe HttpStatusCode.OK
                val bareBody = bare.bodyAsBytes()
                val cookie = http.get(path) { header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=%") }
                val cookieBody = cookie.bodyAsBytes()
                assertSoftly {
                    cookie.status shouldBe bare.status
                    cookie.headers.getAll(HttpHeaders.ContentType) shouldBe bare.headers.getAll(HttpHeaders.ContentType)
                    cookieBody shouldBe bareBody
                    bare.headers.getAll(HttpHeaders.SetCookie) shouldBe null
                    cookie.headers.getAll(HttpHeaders.SetCookie) shouldBe null
                }
            }
        }
    }

    test("workspace malformed session cookie changes a response") {
        restTest(Fixtures.demoDocs) { _ ->
            val http = restClient()
            val changed = listOf("/docs", "/api/v1/tree", "/api/v1/session").map { path ->
                val bare = http.get(path)
                bare.status shouldBe HttpStatusCode.OK
                val bareBody = bare.bodyAsBytes()
                val cookie = http.get(path) { header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=%") }
                val cookieBody = cookie.bodyAsBytes()
                val differs = cookie.status != bare.status ||
                    cookie.headers.getAll(HttpHeaders.ContentType) != bare.headers.getAll(HttpHeaders.ContentType) ||
                    !cookieBody.contentEquals(bareBody)
                logger.info { "Malformed session cookie workspace $path: ${bare.status} -> ${cookie.status}; changed=$differs" }
                differs
            }
            changed.any { it } shouldBe true
        }
    }

    test("targeted negatives reach fallbacks with the audit snapshot unchanged") {
        readerTest(Fixtures.demoDocs) { harness ->
            val http = restClient()
            val before = harness.index.auditRepository.recent(Int.MAX_VALUE)
            val page = harness.builder.current.pages.first { it.url != null }
            val pageUrl = requireNotNull(page.url)
            val shells = listOf("/admin", "/new", "/review", "/discussions", "/review/x", "/discussions/docs", "/discussions/docs/x")
                .map { path ->
                    val response = http.get(path) { header(HttpHeaders.Accept, ContentType.Text.Html) }
                    Triple(path, response, response.bodyAsText())
                }
            val api = listOf(
                HttpMethod.Get to "/api/v1/mcp",
                HttpMethod.Post to "/api/v1/mcp",
                HttpMethod.Get to "/api/v1/pages/${page.id.value}/validate-links",
                HttpMethod.Post to "/api/v1/admin/rescan",
                HttpMethod.Post to "/api/v1/admin/reindex",
                HttpMethod.Get to "/api/v1/admin/tokens",
                HttpMethod.Put to "/api/v1/pages/${page.id.value}",
                HttpMethod.Post to "/api/v1/pages",
                HttpMethod.Post to "/api/v1/pages/${page.id.value}/assets",
                HttpMethod.Post to "/api/v1/changes",
                HttpMethod.Get to "/api/v1/changes",
            ).map { (method, path) ->
                val response = http.request(path) {
                    this.method = method
                    header(HttpHeaders.Host, "localhost")
                    if (method != HttpMethod.Get) {
                        contentType(ContentType.parse("text/markdown"))
                        setBody("# probe\n")
                    }
                }
                Triple("${method.value} $path", response, response.bodyAsText())
            }
            val options = listOf("/", pageUrl, "/api/v1/tree", "/api/v1/nope").map { path ->
                path to http.request(path) { method = HttpMethod.Options }
            }
            val after = harness.index.auditRepository.recent(Int.MAX_VALUE)
            assertSoftly {
                shells.forEach { (path, response, body) ->
                    withClue("GET $path") {
                        response.status.value shouldBe HttpStatusCode.NotFound.value
                        response.contentType()?.withoutParameters() shouldBe ContentType.Text.Html
                        body.isNotEmpty() shouldBe true
                    }
                }
                api.forEach { (label, response, body) ->
                    val error = runCatching { RestJson.parseToJsonElement(body).jsonObject.getValue("error").jsonObject }.getOrNull()
                    withClue("$label: $body") {
                        response.status.value shouldBe HttpStatusCode.NotFound.value
                        error?.get("code")?.jsonPrimitive?.content shouldBe "not_found"
                        error?.get("message")?.jsonPrimitive?.content.orEmpty() shouldStartWith "No such API endpoint"
                    }
                }
                options.forEach { (path, response) ->
                    withClue("OPTIONS $path") {
                        (response.status.value in 200..299) shouldBe false
                        (response.status.value >= 500) shouldBe false
                        response.headers[HttpHeaders.SetCookie] shouldBe null
                    }
                }
                withClue("audit snapshot pin") {
                    after shouldBe before
                }
            }
        }
    }
})

private data class ReaderCredentialProbe(
    val label: String,
    val path: String,
    val status: HttpStatusCode,
    val method: HttpMethod = HttpMethod.Get,
)

private fun HttpResponse.headersWithoutDate(): Map<String, List<String>> = headers.entries()
    .filterNot { it.key.equals(HttpHeaders.Date, ignoreCase = true) }
    .associate { it.key.lowercase() to it.value }

private val logger = KotlinLogging.logger {}
