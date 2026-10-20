package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.header
import io.ktor.server.routing.param
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import io.mockk.mockk

class RouteInventoryTest : FunSpec({
    AuthMode.entries.forEach { authMode ->
        test("workspace route inventory matches the golden for $authMode") {
            RestHarness(Fixtures.demoDocs, authMode = authMode, discussions = mockk(relaxed = true)).use { harness ->
                routeInventory(harness.services) shouldBe workspaceRouteGolden(authMode)
                harness.services.reader shouldBe null
            }
        }
    }

    test("workspace inventory without a discussion facade lacks discussion registrations") {
        RestHarness(Fixtures.demoDocs, authMode = AuthMode.OFF).use { harness ->
            routeInventory(harness.services) shouldNotContain "GET /api/v1/pages/{id}/discussions"
        }
    }

    test("reader harness forwards the reader surface through the context hook") {
        var observed: ReaderSurface? = null
        readerTest(Fixtures.demoDocs, context = { ctx ->
            observed = ctx.reader
            ctx.withExtract(ctx.extract)
        }) { harness ->
            observed shouldBe ReaderSurface(release = null)
            harness.services.reader shouldBe observed
        }
    }

    test("header selectors fail loud") {
        unknownSelector { header("X-Probe", "1") { get("/x") {} } }
    }

    test("query parameter selectors fail loud") {
        unknownSelector { param("q") { get("/x") {} } }
    }
})

private fun unknownSelector(routes: Route.() -> Unit) {
    testApplication {
        application { routing(routes) }
        startApplication()
        shouldThrow<IllegalStateException> { mountedRoutes(application.routingRoot) }
            .message shouldContain "unrecognized route selector"
    }
}

private fun workspaceRouteGolden(authMode: AuthMode): Set<String> = when (authMode) {
    AuthMode.OFF -> WORKSPACE_COMMON_ROUTES
    AuthMode.PROXY -> WORKSPACE_COMMON_ROUTES + WORKSPACE_AUTH_ROUTES
    AuthMode.BUILTIN -> WORKSPACE_COMMON_ROUTES + WORKSPACE_AUTH_ROUTES + WORKSPACE_BUILTIN_ROUTES
}

// Captured workspace registrations with discussions enabled; auth-mode additions are explicit.
private val WORKSPACE_COMMON_ROUTES = """
    ANY /_plainbase/custom.css
    ANY /_plainbase/theme-dark.css
    ANY /_plainbase/theme-light.css
    ANY /_plainbase/{...}
    ANY /api/v1/mcp
    ANY /api/{...}
    GET /
    GET /admin
    GET /api/v1/changes
    GET /api/v1/changes/{id}
    GET /api/v1/discussions
    GET /api/v1/discussions/{id}
    GET /api/v1/pages/by-path/{path...}
    GET /api/v1/pages/{id}
    GET /api/v1/pages/{id}/diff
    GET /api/v1/pages/{id}/discussions
    GET /api/v1/pages/{id}/history
    GET /api/v1/pages/{id}/html
    GET /api/v1/pages/{id}/metadata
    GET /api/v1/pages/{id}/validate-links
    GET /api/v1/search
    GET /api/v1/tree
    GET /apple-touch-icon.png
    GET /assets/{path...}
    GET /browse/{path...}
    GET /discussions
    GET /discussions/{root}
    GET /discussions/{root}/{id}
    GET /favicon.svg
    GET /fonts/{file}
    GET /healthz
    GET /index.html
    GET /new
    GET /p/{segments...}
    GET /plainbase-logo-dark.svg
    GET /plainbase-logo.svg
    GET /plainbase-theme-generic.css
    GET /review
    GET /review/{id}
    GET /{root}
    GET /{root}/{path...}
    POST /api/v1/admin/reindex
    POST /api/v1/admin/rescan
    POST /api/v1/changes
    POST /api/v1/changes/{id}/approve
    POST /api/v1/changes/{id}/rebase
    POST /api/v1/changes/{id}/reject
    POST /api/v1/discussions/{id}/comments
    POST /api/v1/discussions/{id}/comments/{commentId}/edit
    POST /api/v1/discussions/{id}/comments/{commentId}/purge
    POST /api/v1/discussions/{id}/comments/{commentId}/retract
    POST /api/v1/discussions/{id}/reattach
    POST /api/v1/discussions/{id}/reopen
    POST /api/v1/discussions/{id}/resolve
    POST /api/v1/mcp
    POST /api/v1/pages
    POST /api/v1/pages/{id}/assets
    POST /api/v1/pages/{id}/discussions
    POST /api/v1/pages/{id}/discussions/anchor-preview
    POST /api/v1/preview
    PUT /api/v1/pages/{id}
""".trimIndent().lines().toSet()

private val WORKSPACE_AUTH_ROUTES = """
    GET /api/v1/admin/audit
    GET /api/v1/admin/roles
    GET /api/v1/admin/tokens
    GET /api/v1/session
    POST /api/v1/admin/roles
    POST /api/v1/admin/tokens
    POST /api/v1/admin/tokens/{id}/revoke
""".trimIndent().lines().toSet()

private val WORKSPACE_BUILTIN_ROUTES = """
    GET /api/v1/admin/users
    POST /api/v1/admin/sessions/revoke
    POST /api/v1/admin/users
    POST /api/v1/admin/users/{id}/disable
    POST /api/v1/admin/users/{id}/reset
    POST /api/v1/login
    POST /api/v1/logout
    POST /api/v1/password/change
    POST /api/v1/password/reset/consume
    POST /api/v1/setup/consume
""".trimIndent().lines().toSet()
