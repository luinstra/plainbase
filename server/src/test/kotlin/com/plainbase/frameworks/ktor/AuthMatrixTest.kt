package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.IndexHarness
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.time.Clock

/**
 * The A3 auth-behavior grid (WI 8): role × action × route 401/403/200 under ENFORCED (builtin) auth-on. Drives a
 * fixed-`Principal` source for the Human roles (a test-construction seam — auth is NEVER off; the harness presents
 * a REAL role-appropriate principal seeded in `subject_role`), AND a real Agent BEARER for one route of each class
 * (proving both identity sources resolve through `check()`). 401 = anonymous (no credential); 403 = an
 * authenticated-but-unauthorized principal.
 */
class AuthMatrixTest : FunSpec({

    // A tiny fixed tree so a gated read/redirect has something real to resolve (existence must NOT leak to anon).
    fun withApp(role: Role?, block: suspend (io.ktor.server.testing.ApplicationTestBuilder) -> Unit) {
        val root = Files.createTempDirectory("plainbase-authmatrix")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nbody.\n")
            IndexHarness(root, contentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root)).use { harness ->
                harness.builder.rebuild()
                val principal: Principal = when (role) {
                    null -> Principal.Anonymous
                    else -> {
                        harness.roleRepository.upsert("builtin", "subject", role, Clock.System.now())
                        Principal.Human("builtin", "subject")
                    }
                }
                val ctx = harness.testRouteContext(
                    contentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root),
                    searchProvider = harness.fts(root),
                    enforced = true,
                    extract = fixedPrincipal(principal),
                )
                testApplication {
                    application { plainbaseModule(ctx) }
                    block(this)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("anonymous is 401 on every gated route (no existence oracle: a read/redirect, not a 404)") {
        withApp(role = null) { app ->
            app.client.get("/api/v1/pages/by-path/doc").status shouldBe HttpStatusCode.Unauthorized
            app.client.get("/api/v1/tree").status shouldBe HttpStatusCode.Unauthorized
            app.client.get("/api/v1/search?q=body").status shouldBe HttpStatusCode.Unauthorized
            app.client.get("/browse/doc.md").status shouldBe HttpStatusCode.Unauthorized
            app.client.get("/assets/x.bin").status shouldBe HttpStatusCode.Unauthorized
            app.client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("viewer: 200 on reads, 403 on writes and manage") {
        withApp(role = Role.VIEWER) { app ->
            app.client.get("/api/v1/pages/by-path/doc").status shouldBe HttpStatusCode.OK
            app.client.get("/api/v1/tree").status shouldBe HttpStatusCode.OK
            app.client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.Forbidden
            app.client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"X"}""")
            }.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("editor: 200 on reads + create, 403 on manage") {
        withApp(role = Role.EDITOR) { app ->
            app.client.get("/api/v1/pages/by-path/doc").status shouldBe HttpStatusCode.OK
            app.client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"New Editor Page"}""")
            }.status shouldBe HttpStatusCode.Created
            app.client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("admin: 200 everywhere incl. manage") {
        withApp(role = Role.ADMIN) { app ->
            app.client.get("/api/v1/pages/by-path/doc").status shouldBe HttpStatusCode.OK
            app.client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.OK
        }
    }

    test("a denied mutating request audits the MUTATING action (not a swallowed read): PUT/create/asset/admin") {
        val root = Files.createTempDirectory("plainbase-authmatrix-audit")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nbody.\n")
            IndexHarness(root, contentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root)).use { harness ->
                harness.builder.rebuild()
                // A VIEWER may READ but not EDIT/CREATE/MANAGE — so each mutating route below is DENIED, and the
                // denied decision must be audited as the MUTATING action, never swallowed by a read-check that runs
                // first (BLOCKING 1: the write path's FIRST authorization is the audited edit/create/manage check).
                harness.roleRepository.upsert("builtin", "subject", Role.VIEWER, Clock.System.now())
                val ctx = harness.testRouteContext(
                    contentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root),
                    searchProvider = harness.fts(root),
                    enforced = true,
                    extract = fixedPrincipal(Principal.Human("builtin", "subject")),
                )
                val pageId = harness.builder.current.pages.single().id.value
                val validIfMatch = "\"sha256:${"0".repeat(64)}\"" // shape-valid so the PUT reaches the edit-check

                testApplication {
                    application { plainbaseModule(ctx) }

                    client.put("/api/v1/pages/$pageId") {
                        contentType(ContentType.parse("text/markdown"))
                        header(HttpHeaders.IfMatch, validIfMatch)
                        setBody("---\nid: $pageId\ntitle: Doc\n---\n\nedited.\n")
                    }.status shouldBe HttpStatusCode.Forbidden

                    client.post("/api/v1/pages") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Denied Create"}""")
                    }.status shouldBe HttpStatusCode.Forbidden

                    client.post("/api/v1/pages/$pageId/assets?filename=x.bin") {
                        setBody("bytes")
                    }.status shouldBe HttpStatusCode.Forbidden

                    client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.Forbidden
                }

                // Exactly the four mutating decisions were audited, each as its intrinsic action + denied (NOT a
                // read): the PUT and the asset upload are EDIT, the create is CREATE, the rescan is MANAGE.
                val denied = harness.auditRepository.recent(50)
                denied.map { it.decision }.toSet() shouldBe setOf("denied")
                denied.map { it.action } shouldContainExactlyInAnyOrder listOf("EDIT", "CREATE", "EDIT", "MANAGE")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("an Agent bearer resolves through check(): READ_ONLY agent reads (200) but cannot manage (403)") {
        val root = Files.createTempDirectory("plainbase-authmatrix-agent")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n")
            IndexHarness(root, contentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root)).use { harness ->
                harness.builder.rebuild()
                // A real loopback bearer for a READ_ONLY agent — the genuine extraction path (loopback is secure).
                val minted = harness.apiTokens.mint(label = "ci", mode = AgentMode.READ_ONLY)
                val ctx = harness.testRouteContext(
                    contentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root),
                    searchProvider = harness.fts(root),
                    enforced = true, // the REAL extractPrincipal over the bearer (no fixed-principal seam)
                )
                testApplication {
                    application { plainbaseModule(ctx) }
                    val read: HttpResponse = client.get("/api/v1/pages/by-path/doc") {
                        header(HttpHeaders.Authorization, "Bearer ${minted.plaintext}")
                    }
                    read.status shouldBe HttpStatusCode.OK
                    val manage = client.post("/api/v1/admin/rescan") {
                        header(HttpHeaders.Authorization, "Bearer ${minted.plaintext}")
                    }
                    manage.status shouldBe HttpStatusCode.Forbidden
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

/** A real FTS provider over a temp search.db so search/create exercise the engine; closed with the harness. */
private fun IndexHarness.fts(root: java.nio.file.Path): com.plainbase.domain.search.SearchProvider {
    val searchDb = com.plainbase.frameworks.search.SearchDb(Files.createTempDirectory("authmatrix-search").resolve("search.db"))
    return com.plainbase.frameworks.search.Fts5SearchProvider(searchDb)
}
