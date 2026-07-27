package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The permalink READ column of the endpoint-status matrix (C4, 6b): both the root-pinned `/p/{root}/{id}` and the
 * bare `/p/{id}` against present / retired / detached / absent / malformed states. The CLASS-A line-280 flip is
 * pinned here: a sole tombstone under a DETACHED root answers 404 (never the old 410), while a tombstone under a
 * REGISTERED root stays 410.
 */
class RootedPermalinkMatrixTest : FunSpec({

    val presentId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val retiredMainId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b")
    val retiredDetachedId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5c")
    val absentId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5d"
    val main = RootName.PRIMARY

    fun oneRoot(block: (Path) -> Unit) {
        val parent = Files.createTempDirectory("pb-permalink-matrix")
        try {
            val mainDir = Files.createDirectory(parent.resolve("main-root"))
            val target = mainDir.resolve("guides/a.md")
            Files.createDirectories(target.parent)
            Files.writeString(target, "---\nid: $presentId\ntitle: A\n---\n\n# A\n\nbody.\n")
            block(mainDir)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    suspend fun io.ktor.client.statement.HttpResponse.code(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    test("the permalink READ matrix: present/retired/detached/absent/malformed on rooted and bare /p") {
        oneRoot { mainDir ->
            multiRootTest(listOf(testRoot("main", mainDir))) { harness ->
                // Post-boot durable state: a tombstone under REGISTERED main, and a tombstone under a DETACHED root.
                val p = RootedPath(main, TreePath.require("gone.md"))
                harness.idMap.bind(p, retiredMainId, materialized = false)
                harness.idMap.bind(p, PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b60"), materialized = false)
                harness.detachedTombstone("extra", "notes/x.md", retiredDetachedId)

                val c = createClient { followRedirects = false }

                withClue("PRESENT: /p/main/{id} -> 302") {
                    c.get("/p/main/$presentId").status shouldBe HttpStatusCode.Found
                }
                withClue("PRESENT: bare /p/{id} -> 302") {
                    c.get("/p/$presentId").status shouldBe HttpStatusCode.Found
                }
                withClue("DETACHED root pin: /p/ghost/{id} -> 404 (unregistered, AFTER checkRead)") {
                    c.get("/p/ghost/$presentId").status shouldBe HttpStatusCode.NotFound
                }
                withClue("MALFORMED root slug: /p/BadRoot/{id} -> 400 invalid_root (the sole pre-auth exception)") {
                    val res = c.get("/p/BadRoot/$presentId")
                    res.status shouldBe HttpStatusCode.BadRequest
                    res.code() shouldBe "invalid_root"
                }
                withClue("ABSENT: /p/main/{unknown} -> 404") {
                    c.get("/p/main/$absentId").status shouldBe HttpStatusCode.NotFound
                }
                withClue("RETIRED under REGISTERED main: /p/main/{id} -> 410, and bare /p/{id} -> 410") {
                    c.get("/p/main/${retiredMainId.value}").status shouldBe HttpStatusCode.Gone
                    c.get("/p/${retiredMainId.value}").status shouldBe HttpStatusCode.Gone
                }
                withClue("CLASS-A flip: a sole tombstone under a DETACHED root -> bare /p/{id} is 404, NEVER 410") {
                    c.get("/p/${retiredDetachedId.value}").status shouldBe HttpStatusCode.NotFound
                }
                withClue("MALFORMED id: the 32-hex hyphenless form is 400 invalid_page_id on rooted /p") {
                    val res = c.get("/p/main/${presentId.replace("-", "")}")
                    res.status shouldBe HttpStatusCode.BadRequest
                    res.code() shouldBe "invalid_page_id"
                }
                withClue("canonical hyphenated UPPERCASE still resolves on rooted /p (any-case is canonical-shape)") {
                    c.get("/p/main/${presentId.uppercase()}").status shouldBe HttpStatusCode.Found
                }
                withClue("interior and leading empty segments are malformed") {
                    val interior = c.get("/p/main//$presentId")
                    interior.status shouldBe HttpStatusCode.BadRequest
                    interior.code() shouldBe "invalid_page_id"
                    val leading = c.get("/p//$presentId")
                    leading.status shouldBe HttpStatusCode.BadRequest
                    leading.code() shouldBe "invalid_page_id"
                }
            }
        }
    }

    // Behavior-pins (no back-out exists for these arms): the DOWN-root rooted column + the limbo cell. The tombstone
    // cell pins the TRACED arm order - live None is decided BEFORE the availability gate can fire, because
    // `bindsLive` is false for a retired id and only the live arms call `requireAvailable`. Retired is a settled
    // durable fact; availability gates only LIVE serving, so 410 under a down root is right per the pinned matrix.
    test(
        "the DOWN-root rooted column: live-bound -> 503 root_unavailable; tombstone-only -> 410; absent -> 404; limbo under UP main -> 503",
    ) {
        oneRoot { mainDir ->
            val missing = mainDir.resolveSibling("extra-root-gone")
            val liveDownId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b61")
            val retiredDownId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b62")
            val limboId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b63")
            MultiRootRestHarness(listOf(testRoot("main", mainDir), testRoot("extra", missing))).use { harness ->
                harness.idMapOnly("extra", "notes/live.md", liveDownId) // bound BEFORE boot - the boot arm
                harness.boot()
                // Post-boot durable rows: a tombstone under the DOWN extra (the displacing double-bind), and a LIMBO
                // binding under the UP main (bound, no file, in no snapshot section).
                val goneExtra = RootedPath(RootName.require("extra"), TreePath.require("notes/gone.md"))
                harness.idMap.bind(goneExtra, retiredDownId, materialized = false)
                harness.idMap.bind(goneExtra, PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b64"), materialized = false)
                harness.idMap.bind(RootedPath(main, TreePath.require("limbo.md")), limboId, materialized = false)
                io.ktor.server.testing.testApplication {
                    application { plainbaseModule(harness.services) }
                    val c = createClient { followRedirects = false }
                    withClue("LIVE binding under a DOWN root -> 503 root_unavailable (availability gates live serving)") {
                        val res = c.get("/p/extra/${liveDownId.value}")
                        res.status shouldBe HttpStatusCode.ServiceUnavailable
                        res.code() shouldBe "root_unavailable"
                    }
                    withClue("TOMBSTONE-only under a DOWN root -> 410 (retired is settled; the gate sits on the live arms only)") {
                        c.get("/p/extra/${retiredDownId.value}").status shouldBe HttpStatusCode.Gone
                    }
                    withClue("absent everywhere under a DOWN root -> 404") {
                        c.get("/p/extra/$absentId").status shouldBe HttpStatusCode.NotFound
                    }
                    withClue("LIMBO under an UP root (bound, not in the snapshot) -> 503 absence_unverified, never the 404 lie") {
                        val res = c.get("/p/main/${limboId.value}")
                        res.status shouldBe HttpStatusCode.ServiceUnavailable
                        res.code() shouldBe "absence_unverified"
                    }
                }
            }
        }
    }
})
