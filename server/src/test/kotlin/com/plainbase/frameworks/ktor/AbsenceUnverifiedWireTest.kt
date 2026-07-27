package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * **C1 on the wire: a page we did not see is not a page that is gone.**
 *
 * > A read for a page the durable index HAS, whose bytes the store cannot produce, is **503**.
 * > A **404** only for a page the index does not have.
 *
 * The old rule asked the ADAPTER (`available()`), and an adapter cannot answer this: after an ext4 inode-reused
 * replacement the probe says LIVE, so a read for a page sitting safe on an unmounted disk answered **404** - "drop
 * your citations" - for a page that was coming back (ledger A4). The index knew better the whole time.
 *
 * Every row here drives the REAL route stack. The three answers a missing page can now get are pinned against each
 * other, because the whole value is in the DIFFERENCE between them:
 *  - **404** `page_not_found` - the index does not have it. Nothing is in doubt.
 *  - **503** `absence_unverified` - the index HAS it and we cannot read it. The root is FINE; one page is unknown.
 *  - **503** `root_unavailable` - the root itself is not serving. Nothing about it may be believed.
 *  - **410** `page_retired` - it was PROVEN gone. The permalink survives to say so.
 */
class AbsenceUnverifiedWireTest : FunSpec({

    val docId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val docPath = TreePath.require("doc.md")
    val page = "---\ntitle: Doc\n---\n\n# Doc\n\nBody.\n"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(RootedPath(RootName.PRIMARY, docPath), PageId.require(docId), materialized = false)
    }

    suspend fun HttpResponse.error(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject
    suspend fun HttpResponse.code(): String = error().getValue("code").jsonPrimitive.content

    /** The corpus, indexed - then its file removed and the pass re-run. The binding stands; the page is in LIMBO. */
    fun limboed(block: suspend ApplicationTestBuilder.(RestHarness, java.nio.file.Path) -> Unit): Unit =
        withTempTree(seed = { writePage(it, "doc.md", page) }) { root ->
            restTest(root, seed) { harness ->
                harness.builder.current.pageAt(RootedPageId(RootName.PRIMARY, PageId.require(docId))) shouldNotBe null
                Files.delete(root.resolve("doc.md"))
                harness.builder.rebuild()
                block(harness, root)
            }
        }

    test("a read for an indexed page whose bytes are unavailable is 503 absence_unverified, NEVER 404") {
        limboed { harness, _ ->
            withClue("the page left the SNAPSHOT - that is what makes this the 404 path, and it is the lie") {
                harness.builder.current.pageAt(RootedPageId(RootName.PRIMARY, PageId.require(docId))) shouldBe null
            }
            withClue("...but the durable index still BINDS it, and that is the fact that decides") {
                harness.idMap.livePathOf(PageId.require(docId)) shouldBe RootedPath(RootName.PRIMARY, docPath)
            }

            val get = client.get("/api/v1/pages/$docId")
            get.status shouldBe HttpStatusCode.ServiceUnavailable
            get.code() shouldBe "absence_unverified"
            withClue("limbo self-heals with no operator in the loop, so the retry window is SHORT") {
                get.headers["Retry-After"] shouldBe "30"
            }
        }
    }

    test("absence_unverified is NOT root_unavailable - two 503s, two facts, two remedies") {
        limboed { harness, _ ->
            val limbo = client.get("/api/v1/pages/$docId")
            limbo.status shouldBe HttpStatusCode.ServiceUnavailable
            limbo.code() shouldBe "absence_unverified"
            withClue("the ROOT is healthy - saying otherwise would send an operator to remount a mounted disk") {
                harness.availability.current().isAvailable(RootName.PRIMARY) shouldBe true
                limbo.error().getValue("message").jsonPrimitive.content shouldNotBe null
            }

            // The SAME page, the SAME missing bytes - and now the root really is gone. A different fact deserves a
            // different answer: nothing about this root may be believed, and recovery is an operator's job.
            harness.availability.markUnavailable(RootName.PRIMARY, UnavailableCause.VANISHED)
            val down = client.get("/api/v1/pages/$docId")
            down.status shouldBe HttpStatusCode.ServiceUnavailable
            down.code() shouldBe "root_unavailable"
            withClue("root loss waits on an operator restart, so its retry window is the long one") {
                down.headers["Retry-After"] shouldBe "300"
            }
        }
    }

    test("an id the index does NOT bind is still an honest 404 - the classifier narrows the 404, it does not abolish it") {
        limboed { _, _ ->
            val unknown = client.get("/api/v1/pages/0190aaaa-bbbb-7ccc-8ddd-00000000dead")
            unknown.status shouldBe HttpStatusCode.NotFound
            unknown.code() shouldBe "page_not_found"
        }
    }

    test("the permalink of a limbo page is 503, not the 404 that tells an agent its citation was never real") {
        limboed { _, _ ->
            val p = client.get("/p/$docId")
            p.status shouldBe HttpStatusCode.ServiceUnavailable
            p.code() shouldBe "absence_unverified"
        }
    }

    test("a retired permalink is an uncached 410 and same-root reappearance reclaims it as a 302") {
        limboed { harness, root ->
            val c = restClient()
            // The absence is PROVEN (C2's epoch / C4's git history mint these for real; OPERATOR stands in here).
            // Only now may the server say the page is gone - a 410 that NAMES where the page lived (not a 404
            // denying it existed). The 410 is uncached and reversible: this same test reclaims the id as a 302.
            harness.retirements.applyProofs(
                listOf(
                    AbsenceProof(
                        root = RootName.PRIMARY,
                        source = ProofSource.OPERATOR,
                        observationId = harness.retirements.observation(RootName.PRIMARY),
                        bindingEpoch = harness.retirements.bindingEpoch(RootName.PRIMARY),
                        covers = setOf(BindingRef(docPath, PageId.require(docId))),
                    ),
                ),
                witnessed = emptySet(), // setup: no scan ran, and OPERATOR is not an inference, so nothing refutes it
                unavailableNow = { emptySet() },
            )

            val p = c.get("/p/main/$docId")
            p.status shouldBe HttpStatusCode.Gone
            p.code() shouldBe "page_retired"
            p.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            withClue("the last-known path is the whole point: a human or an agent learns WHAT HAPPENED") {
                p.error().getValue("message").jsonPrimitive.content.contains("doc.md") shouldBe true
            }

            writePage(root, "doc.md", "---\nid: $docId\ntitle: Doc\n---\n\n# Doc\n\nBody.\n")
            harness.builder.rebuild()

            c.get("/p/main/$docId").status shouldBe HttpStatusCode.Found
        }
    }

    test("limbo SELF-HEALS on reappearance: the 503 becomes a 200 with no operator action and no code run") {
        limboed { harness, root ->
            client.get("/api/v1/pages/$docId").status shouldBe HttpStatusCode.ServiceUnavailable

            // The mount comes back. No reconcile, no restart, no ceremony: the page is witnessed again, so it drops
            // out of the derived limbo set - which is exactly what "derived, never stored" buys.
            writePage(root, "doc.md", page)
            harness.builder.rebuild()

            val healed = client.get("/api/v1/pages/$docId")
            healed.status shouldBe HttpStatusCode.OK
            withClue("and it comes back with the id it left with - the binding was never touched") {
                Json.parseToJsonElement(healed.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content shouldBe docId
            }
            harness.limbo.count(RootName.PRIMARY) shouldBe 0
        }
    }

    test("/healthz reports the per-root limbo count on a root that is otherwise perfectly healthy") {
        limboed { harness, root ->
            val health = Json.parseToJsonElement(client.get("/healthz").bodyAsText()).jsonObject
            val main = health.getValue("roots").jsonArray.single().jsonObject
            withClue("available: true AND limbo: 1 is the whole diagnosis - the disk is fine, one page is unknown") {
                main.getValue("available").jsonPrimitive.content shouldBe "true"
                main.getValue("limbo").jsonPrimitive.content shouldBe "1"
            }

            writePage(root, "doc.md", page)
            harness.builder.rebuild()
            val healed = Json.parseToJsonElement(client.get("/healthz").bodyAsText()).jsonObject
            healed.getValue("roots").jsonArray.single().jsonObject.getValue("limbo").jsonPrimitive.content shouldBe "0"
            harness.limbo.count(RootName.PRIMARY) shouldBe 0
        }
    }
})
