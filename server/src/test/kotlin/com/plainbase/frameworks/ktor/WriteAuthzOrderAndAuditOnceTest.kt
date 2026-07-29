package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.PageRootResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import java.nio.file.Path

/**
 * The 5.2a AUTH-DEFER sweep, ENFORCED mode (C4, 6d): every id+root surface GRAMMAR-parses the pin at the route
 * (malformed -> 400 pre-auth) but DEFERS registration/ownership to AFTER the auth gate. So a DENIED caller naming a
 * `?root=ghost` (or a bare ambiguous id) answers 401 - NEVER the 404/409/invalid_root that would leak registration
 * or existence pre-auth. Only a malformed SLUG is answered pre-auth.
 */
class WriteAuthzOrderAndAuditOnceTest : FunSpec({

    val dupId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val id = PageId.require(dupId)
    val notes = RootName.require("notes")
    val main = RootName.PRIMARY

    fun oneRoot(block: (Path) -> Unit) {
        val parent = Files.createTempDirectory("pb-authdefer")
        try {
            val mainDir = Files.createDirectory(parent.resolve("main-root"))
            val target = mainDir.resolve("a.md")
            Files.writeString(target, "---\nid: $dupId\ntitle: A\n---\n\n# A\n\nbody.\n")
            block(mainDir)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    fun twoRoots(block: (Path, Path) -> Unit) {
        val parent = Files.createTempDirectory("pb-authdefer2")
        try {
            val mainDir = Files.createDirectory(parent.resolve("main-root"))
            val notesDir = Files.createDirectory(parent.resolve("notes-root"))
            val target = mainDir.resolve("a.md")
            Files.writeString(target, "---\nid: $dupId\ntitle: A\n---\n\n# A\n\nbody.\n")
            block(mainDir, notesDir)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    val markdown = ContentType.parse("text/markdown")
    val dummyIfMatch = "\"sha256:${"0".repeat(64)}\""

    test("denied anonymous naming ?root=ghost gets 401 (deferred registration), never 404/invalid_root") {
        oneRoot { mainDir ->
            multiRootTest(listOf(testRoot("docs", mainDir)), enforced = true) { harness ->
                withClue("READ ?root=ghost -> 401, not 404") {
                    client.get("/api/v1/pages/$dupId?root=ghost").status shouldBe HttpStatusCode.Unauthorized
                }
                withClue("READ history ?root=ghost -> 401") {
                    client.get("/api/v1/pages/$dupId/history?root=ghost").status shouldBe HttpStatusCode.Unauthorized
                }
                withClue("PUT ?root=ghost -> 401, not 404") {
                    client.put("/api/v1/pages/$dupId?root=ghost") {
                        contentType(markdown)
                        header(HttpHeaders.IfMatch, dummyIfMatch)
                        setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n")
                    }.status shouldBe HttpStatusCode.Unauthorized
                }
                withClue("POST assets ?root=ghost -> 401") {
                    client.post("/api/v1/pages/$dupId/assets?filename=x.png&root=ghost") {
                        setBody(ByteArray(3))
                    }.status shouldBe HttpStatusCode.Unauthorized
                }
                withClue("POST /changes edit root=ghost -> 401 (grammar-parsed, NOT invalid_root pre-auth)") {
                    client.post("/api/v1/changes") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"operation":"edit","page_id":"$dupId","root":"ghost","base_hash":"sha256:${"0".repeat(64)}",""" +
                                """"proposed_content":"# X","rationale":"r"}""",
                        )
                    }.status shouldBe HttpStatusCode.Unauthorized
                }
                withClue("the ledger: denied READS record NOTHING (checkRead is unaudited); each denied WRITE exactly ONE denied row") {
                    val rows = harness.audit.recent(50)
                    rows.map { it.decision }.toSet() shouldBe setOf("denied")
                    rows shouldHaveSize 3 // the PUT, the asset write, and the propose - one row each, and nothing else
                }
            }
        }
    }

    test("audits-exactly-once: a degrading agent PUT records ONE allowed EDIT row (the inner propose's), never a second EDIT@page row") {
        oneRoot { mainDir ->
            // The extract lambda runs per REQUEST, so it can hand back an agent minted only after the harness booted.
            var caller: Principal = Principal.Anonymous
            val extract: io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction = { PrincipalExtraction.Resolved(caller) }
            multiRootTest(listOf(testRoot("docs", mainDir)), enforced = true, extract = extract) { harness ->
                caller = Principal.Agent(harness.index.apiTokens.mint(label = "ci", mode = AgentMode.PROPOSE).id)
                val baseHash = harness.builder.current.byPath.getValue(RootedPath(main, TreePath.require("a.md"))).contentHash
                val res = client.put("/api/v1/pages/$dupId") {
                    contentType(markdown)
                    header(HttpHeaders.IfMatch, "\"$baseHash\"")
                    setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n\nedited.\n")
                }
                res.status shouldBe HttpStatusCode.Accepted
                withClue("the decide-first agent path gates only the CHOSEN branch - a pre-gate would audit a degrading agent TWICE") {
                    val rows = harness.audit.recent(50)
                    rows shouldHaveSize 1
                    with(rows.single()) {
                        action shouldBe "EDIT"
                        resource shouldBe "docs:proposal"
                        decision shouldBe "allowed"
                    }
                }
            }
        }
    }

    test("a MALFORMED root slug is answered pre-auth (400 invalid_root), the sole exception") {
        oneRoot { mainDir ->
            multiRootTest(listOf(testRoot("docs", mainDir)), enforced = true) { _ ->
                client.get("/api/v1/pages/$dupId?root=a/b").status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    test("a denied bare PUT of a FAKE-ambiguous id is 401, NEVER 409 (auth gate before the ambiguity throw)") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(
                listOf(testRoot("docs", mainDir), testRoot("notes", notesDir)),
                enforced = true,
                resolverFactory = { idx ->
                    PageRootResolver(AmbiguousIdMap(idx.idMap, id, liveRoots = listOf(main, notes)), idx.rootRegistry)
                },
                absenceFactory = { idx -> AbsenceClassifier(AmbiguousIdMap(idx.idMap, id, liveRoots = listOf(main, notes))) },
            ) { _ ->
                client.put("/api/v1/pages/$dupId") {
                    contentType(markdown)
                    header(HttpHeaders.IfMatch, dummyIfMatch)
                    setBody("---\nid: $dupId\ntitle: A\n---\n\n# A\n")
                }.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    }
})
