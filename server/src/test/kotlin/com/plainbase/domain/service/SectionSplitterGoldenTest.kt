package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.render.GoldenTsv
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The S1 sectioning golden: the EXACT search-section set (heading id, breadcrumb, body text) of
 * the two editorially frozen fixture pages — the same frozen inputs the PB-REST-1 goldens pin, so
 * editing either file already means deliberate, reviewed golden regeneration (rules in the TSV
 * header). Runs the REAL pipeline: IndexHarness rebuild → published snapshot → [SectionSplitter],
 * so it also proves `IndexBuilder` captured `rendered.sections` into the snapshot at all.
 */
class SectionSplitterGoldenTest : FunSpec({

    val golden = GoldenTsv.load("/golden/search/sections.tsv").groupBy { it[0] }

    test("the golden covers both frozen pages") {
        golden.keys shouldBe setOf("guides/deploy-guide.md", "index.md")
    }

    IndexHarness(Fixtures.demoDocs).use { harness ->
        val snapshot = harness.builder.rebuild()
        val splitter = SectionSplitter()

        golden.forEach { (path, rows) ->
            val page = snapshot.byPath.getValue(TreePath.require(path))
            val documents = splitter.split(page)

            test("$path: the exact section set matches the golden") {
                val actual = documents.sections.map {
                    listOf(it.headingId ?: "-", it.headingPath.joinToString("|"), it.body)
                }
                actual shouldContainExactly rows.map { it.drop(1) }
            }

            test("$path: every document repeats the page identity and metadata") {
                documents.pageId shouldBe page.id
                documents.contentHash shouldBe page.contentHash
                documents.path shouldBe page.path
                documents.sections.forEach {
                    it.pageId shouldBe page.id
                    it.path shouldBe page.path
                    it.title shouldBe page.title
                }
            }
        }

        test("deploy-guide metadata: §C2 flow-list tags, owner, no status key -> active default") {
            val page = snapshot.byPath.getValue(TreePath.require("guides/deploy-guide.md"))
            val pageDoc = splitter.split(page).sections.first()
            pageDoc.headingId shouldBe null
            pageDoc.heading shouldBe null
            pageDoc.headingPath shouldBe emptyList()
            pageDoc.title shouldBe "Deploy Guide"
            pageDoc.tags shouldBe listOf("infra", "guide")
            pageDoc.aliases shouldBe emptyList()
            pageDoc.owner shouldBe "ops"
            pageDoc.status shouldBe "active" // deploy-guide.md has no status key
        }

        test("index.md metadata: explicit status passes through; heading field is the OWN text only") {
            val page = snapshot.byPath.getValue(TreePath.require("index.md"))
            val sections = splitter.split(page).sections
            sections.first().status shouldBe "active" // explicit `status: active`
            val welcome = sections.single { it.headingId == "welcome-to-demo-docs" }
            welcome.heading shouldBe "Welcome to Demo Docs"
            welcome.headingPath shouldBe listOf("Welcome to Demo Docs")
        }
    }
})
