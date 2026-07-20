package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.PageRootResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The edit-proposal optional `root` pin (C4, 6e/section 7): durable-validated AFTER `checkEdit`. A pin that HOLDS the
 * id proceeds; one that does NOT answers StaleBase; omitting it resolves id_map-first; a FAKE-ambiguous id with no
 * pin is 409.
 */
class EditProposalRootPinTest : FunSpec({

    val dupId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val id = PageId.require(dupId)
    val main = RootName.MAIN
    val notes = RootName.require("notes")

    fun twoRoots(block: (Path, Path) -> Unit) {
        val parent = Files.createTempDirectory("pb-editproposal")
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

    fun baseHash(harness: MultiRootRestHarness) =
        harness.builder.current.byPath.getValue(RootedPath(main, TreePath.require("a.md"))).contentHash

    suspend fun io.ktor.client.statement.HttpResponse.code() =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    fun body(root: String?, base: String) = buildString {
        append("""{"operation":"edit","page_id":"$dupId",""")
        if (root != null) append(""""root":"$root",""")
        append(""""base_hash":"$base","proposed_content":"---\nid: $dupId\ntitle: A\n---\n\n# A\n\nedited.\n","rationale":"r"}""")
    }

    test("edit proposal root=main (holds it) proceeds -> 201 Created") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(listOf(testRoot("main", mainDir), testRoot("notes", notesDir))) { harness ->
                val res = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(body("main", baseHash(harness)))
                }
                res.status shouldBe HttpStatusCode.Created
            }
        }
    }

    test("edit proposal root=notes (does NOT hold the id) -> StaleBase, durable-validated after checkEdit") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(listOf(testRoot("main", mainDir), testRoot("notes", notesDir))) { harness ->
                val res = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(body("notes", baseHash(harness)))
                }
                res.status shouldBe HttpStatusCode.BadRequest
                res.code() shouldBe "stale_base"
            }
        }
    }

    test("omitting root resolves id_map-first and proceeds -> 201") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(listOf(testRoot("main", mainDir), testRoot("notes", notesDir))) { harness ->
                client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(body(null, baseHash(harness)))
                }.status shouldBe HttpStatusCode.Created
            }
        }
    }

    test("a FAKE-ambiguous id with NO root -> 409 ambiguous_page_id") {
        twoRoots { mainDir, notesDir ->
            multiRootTest(
                listOf(testRoot("main", mainDir), testRoot("notes", notesDir)),
                resolverFactory = { idx ->
                    PageRootResolver(AmbiguousIdMap(idx.idMap, id, liveRoots = listOf(main, notes)), idx.rootRegistry)
                },
                absenceFactory = { idx -> AbsenceClassifier(AmbiguousIdMap(idx.idMap, id, liveRoots = listOf(main, notes))) },
            ) { harness ->
                val res = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(body(null, baseHash(harness)))
                }
                withClue("no pin -> resolve is Ambiguous -> 409") {
                    res.status shouldBe HttpStatusCode.Conflict
                    // The ambiguous body nests under `error` like every other REST error shape.
                    Json.parseToJsonElement(res.bodyAsText()).jsonObject
                        .getValue("error").jsonObject
                        .getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
                }
            }
        }
    }
})
