package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.config.PlainbaseConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * The chunk-6 MASTER criterion, end to end: adopt materializes a page's id into its frontmatter,
 * the file moves on disk, a rescan republishes — and the permalink survives the move (302 to the
 * NEW canonical URL serving the same content) while the old path URL 301s to the new one.
 *
 * Pre-materialization identity is path-keyed by accepted design (§5.2): durability is only
 * promised for MATERIALIZED ids — which is exactly why `adopt --write-ids` runs before the move
 * (a fresh index after the move would otherwise mint the moved file a new id).
 */
class MoveFileIntegrationTest : FunSpec({

    test("master criterion: a materialized id survives a file move across a rescan") {
        withTempTree(seed = { root ->
            writePage(root, "guides/portable.md", "---\ntitle: Portable Page\n---\n\n# Portable Page\n\nDistinctive body.\n")
            writePage(root, "guides/bystander.md", "---\ntitle: Bystander\n---\n\n# Bystander\n")
        }) { root ->
            val dataDir = Files.createTempDirectory("pb-move-data")
            try {
                // `adopt --write-ids` (the real CLI) materializes the page ids into the files.
                val config = PlainbaseConfig(contentDir = root, dataDir = dataDir, host = "127.0.0.1", port = 0)
                AdoptCommand.run(listOf("--write-ids"), config) shouldBe 0

                // A FRESH harness (in-memory id_map): identity must come from the materialized
                // frontmatter alone — the durability §5.2 actually promises.
                restTest(root) { harness ->
                    val client = restClient()

                    val before = Json.parseToJsonElement(client.get("/api/v1/pages/by-path/guides/portable").bodyAsText()).jsonObject
                    val id = before.getValue("id").jsonPrimitive.content
                    val markdownBefore = before.getValue("markdown").jsonPrimitive.content
                    val permalink = "/p/$id"
                    client.get(permalink).headers[HttpHeaders.Location] shouldBe "/docs/guides/portable"

                    // Move the file on disk, then rescan through the REST hook.
                    Files.createDirectories(root.resolve("manuals"))
                    Files.move(root.resolve("guides/portable.md"), root.resolve("manuals/portable.md"))
                    client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.OK

                    // Permalink -> 302 -> NEW canonical -> 200, same content.
                    val redirect = client.get(permalink)
                    redirect.status shouldBe HttpStatusCode.Found
                    redirect.headers[HttpHeaders.Location] shouldBe "/docs/manuals/portable"
                    val landing = client.get("/docs/manuals/portable")
                    landing.status shouldBe HttpStatusCode.OK
                    landing.bodyAsText() shouldContain "<div id=\"root\">"
                    val after = Json.parseToJsonElement(client.get("/api/v1/pages/by-path/manuals/portable").bodyAsText()).jsonObject
                    after.getValue("id").jsonPrimitive.content shouldBe id
                    after.getValue("markdown").jsonPrimitive.content shouldBe markdownBefore

                    // Old path URL -> 301 -> new (the move alias, one hop).
                    val old = client.get("/docs/guides/portable")
                    old.status shouldBe HttpStatusCode.MovedPermanently
                    old.headers[HttpHeaders.Location] shouldBe "/docs/manuals/portable"
                }
            } finally {
                dataDir.toFile().deleteRecursively()
            }
        }
    }
})
