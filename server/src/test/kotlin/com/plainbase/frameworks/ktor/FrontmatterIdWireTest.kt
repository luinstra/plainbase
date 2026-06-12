package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The frozen §A4 sentence (plan line 353), pinned BOTH ways: "`id` appears here only when
 * materialized." A materialized in-file id echoes through the wire `frontmatter` object; a
 * REJECTED one (someone else's claim — or any id the indexer refused) must NOT — it is garbage or
 * another page's identity, the exact field an agent would otherwise trust. The verbatim block
 * stays visible in `markdown` either way.
 */
class FrontmatterIdWireTest : FunSpec({

    val claimedId = "0197c4d5-1234-7abc-8def-0123456789ab"

    suspend fun HttpResponse.jsonBody(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    test("a materialized in-file id is present in frontmatter and equals the top-level id") {
        withTempTree(seed = { root ->
            writePage(root, "owner.md", "---\nid: $claimedId\ntitle: Owner\n---\n\n# Owner\n")
        }) { root ->
            restTest(root) {
                val body = client.get("/api/v1/pages/by-path/owner").jsonBody()
                body.getValue("id_materialized").jsonPrimitive.boolean shouldBe true
                body.getValue("id").jsonPrimitive.content shouldBe claimedId
                body.getValue("frontmatter").jsonObject.getValue("id").jsonPrimitive.content shouldBe claimedId
            }
        }
    }

    test("a REJECTED in-file id (duplicate claim) is omitted from frontmatter; the top-level id is the resolved one") {
        withTempTree(seed = { root ->
            // Path order decides the duplicate: `a-owner.md` claims the id first, `b-thief.md`'s
            // identical claim is rejected and the page minted a fresh id (id_materialized false).
            writePage(root, "a-owner.md", "---\nid: $claimedId\ntitle: Owner\n---\n\n# Owner\n")
            writePage(root, "b-thief.md", "---\nid: $claimedId\ntitle: Thief\n---\n\n# Thief\n")
        }) { root ->
            restTest(root) {
                val thief = client.get("/api/v1/pages/by-path/b-thief").jsonBody()
                thief.getValue("id_materialized").jsonPrimitive.boolean shouldBe false
                thief.getValue("id").jsonPrimitive.content shouldNotBe claimedId // the resolved (minted) id
                thief.getValue("frontmatter").jsonObject.containsKey("id") shouldBe false
                // Raw truth preserved: the rejected claim is still verbatim in the markdown.
                thief.getValue("markdown").jsonPrimitive.content shouldContain "id: $claimedId"

                // And the legitimate owner still echoes it.
                val owner = client.get("/api/v1/pages/by-path/a-owner").jsonBody()
                owner.getValue("id").jsonPrimitive.content shouldBe claimedId
                owner.getValue("frontmatter").jsonObject.getValue("id").jsonPrimitive.content shouldBe claimedId
            }
        }
    }
})
