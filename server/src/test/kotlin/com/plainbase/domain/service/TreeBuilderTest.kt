package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Nav-tree criteria (chunk 5).
 *
 * **Shape golden (A4/M4 scoping):** `golden/tree-shape.txt` freezes node types, membership, and
 * per-node field values INCLUDING `url` — children are rendered in a neutral lexicographic order
 * so the golden deliberately CANNOT pin child ordering.
 *
 * **Ordering test (separate, updatable-with-review):** the documented-not-frozen collation —
 * `order` if present else MAX, then lowercased title-or-name by code point — pinned at the root:
 * `guides` first (order=1), `api` second (its `_folder.yaml` order=2), the rest by title. A diff
 * here is a reviewable collation change, not a forever-API break.
 */
class TreeBuilderTest : FunSpec({

    fun <T> withFixtureTree(block: (TreeNode.Folder) -> T): T =
        IndexHarness(Fixtures.demoDocs).use { harness -> block(TreeBuilder.build(harness.builder.rebuild(), RootName.PRIMARY)) }

    test("tree shape golden: types, membership, and field values (incl. url) match") {
        withFixtureTree { root ->
            val expected = checkNotNull(TreeBuilderTest::class.java.getResource("/golden/tree-shape.txt")) {
                "golden resource not found: /golden/tree-shape.txt"
            }.readText()
                .lineSequence()
                .filterNot { it.startsWith("#") }
                .joinToString("\n")
                .trim()
            shapeDump(root).trim() shouldBe expected
        }
    }

    test("ordering: guides first (order=1), api second (order=2), the rest by lowercased title") {
        withFixtureTree { root ->
            root.children.map { nameOf(it) } shouldBe listOf(
                "guides", // _folder.yaml order=1
                "api", // _folder.yaml order=2
                "README.md", // no order key -> by title: 'about this tree'
                "changelog",
                "infra",
                "notes",
                "scratch",
                "team",
                "index.md", // 'welcome to demo docs' sorts last
            )
        }
    }

    test("configured folder labels override sidecar titles without changing canonical urls") {
        IndexHarness(Fixtures.demoDocs).use { harness ->
            val index = harness.builder.rebuild()
            val baseline = TreeBuilder.build(index, RootName.PRIMARY)
            val labeled = TreeBuilder.build(
                index,
                RootName.PRIMARY,
                folderLabels = mapOf("guides" to "Planning", "missing" to "Inert"),
            )
            val baselineGuides = baseline.children.filterIsInstance<TreeNode.Folder>().single { it.name == "guides" }
            val labeledGuides = labeled.children.filterIsInstance<TreeNode.Folder>().single { it.name == "guides" }

            baselineGuides.title shouldBe "Guides"
            labeledGuides.title shouldBe "Planning"
            labeledGuides.url shouldBe baselineGuides.url
            labeled.children.none { it is TreeNode.Folder && it.name == "missing" } shouldBe true
        }
    }

    test("a folder containing only assets is omitted (infra/assets), and empty folders never appear") {
        withFixtureTree { root ->
            val infra = root.children.filterIsInstance<TreeNode.Folder>().single { it.name == "infra" }
            infra.children.filterIsInstance<TreeNode.Folder>() shouldBe emptyList()
        }
    }

    test("the deeply nested page sits four folders down with its full url") {
        withFixtureTree { root ->
            var node: TreeNode.Folder = root.children.filterIsInstance<TreeNode.Folder>().single { it.name == "notes" }
            for (name in listOf("deeply", "nested", "folder")) {
                node = node.children.filterIsInstance<TreeNode.Folder>().single { it.name == name }
            }
            node.url shouldBe "/docs/notes/deeply/nested/folder" // the folder's own landing prefix (ADR-0003)
            val treasure = node.children.filterIsInstance<TreeNode.Page>().single()
            treasure.url shouldBe "/docs/notes/deeply/nested/folder/treasure"
        }
    }

    test("pageCount counts DIRECT child pages only — guides=6 excludes advanced's 3, advanced=3") {
        withFixtureTree { root ->
            val guides = root.children.filterIsInstance<TreeNode.Folder>().single { it.name == "guides" }
            // guides holds 6 direct pages; its 3 nested 'advanced' pages must NOT be counted (direct, not recursive).
            guides.pageCount shouldBe 6
            val advanced = guides.children.filterIsInstance<TreeNode.Folder>().single { it.name == "advanced" }
            advanced.pageCount shouldBe 3
        }
    }

    test("updated: a valid frontmatter date passes verbatim; absent or malformed yields null; no other frontmatter leaks") {
        withTempTree({ root ->
            writePage(root, "valid.md", "---\ntitle: Valid\nupdated: 2026-05-30\n---\n# Valid\n")
            writePage(root, "absent.md", "---\ntitle: Absent\n---\n# Absent\n")
            writePage(root, "garbage.md", "---\ntitle: Garbage\nupdated: not-a-date\n---\n# Garbage\n")
            writePage(root, "range.md", "---\ntitle: Range\nupdated: 2026-13-45\n---\n# Range\n")
            // Impossible calendar date (Feb 30) — real calendar validation must reject it.
            writePage(root, "impossible.md", "---\ntitle: Impossible\nupdated: 2026-02-30\n---\n# Impossible\n")
            // Expanded/signed ISO years LocalDate.parse accepts on its own — the fixed-width shape gate must reject them.
            writePage(root, "expanded.md", "---\ntitle: Expanded\nupdated: +12020-08-30\n---\n# Expanded\n")
            writePage(root, "signed.md", "---\ntitle: Signed\nupdated: -2026-01-01\n---\n# Signed\n")
            // Arbitrary frontmatter that must never surface on a tree node.
            writePage(root, "extras.md", "---\ntitle: Extras\nowner: alice\ntags: [a, b]\nreview: pending\n---\n# Extras\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val tree = TreeBuilder.build(harness.builder.rebuild(), RootName.PRIMARY)
                val pages = tree.children.filterIsInstance<TreeNode.Page>().associateBy { it.path.name }
                pages.getValue("valid.md").updated shouldBe "2026-05-30"
                pages.getValue("absent.md").updated.shouldBeNull()
                pages.getValue("garbage.md").updated.shouldBeNull() // wrong shape -> null
                pages.getValue("range.md").updated.shouldBeNull() // out-of-range month/day -> null
                pages.getValue("impossible.md").updated.shouldBeNull() // 2026-02-30 is not a real calendar date
                pages.getValue("expanded.md").updated.shouldBeNull() // +12020-08-30 violates fixed-width YYYY-MM-DD
                pages.getValue("signed.md").updated.shouldBeNull() // -2026-01-01 violates fixed-width YYYY-MM-DD
                pages.getValue("extras.md").updated.shouldBeNull() // owner/tags/review never become 'updated'
            }
        }
    }

    test("the api/overview fixture carries its editorial updated date verbatim") {
        withFixtureTree { root ->
            val api = root.children.filterIsInstance<TreeNode.Folder>().single { it.name == "api" }
            val overview = api.children.filterIsInstance<TreeNode.Page>().single { it.path.name == "overview.md" }
            overview.updated shouldBe "2026-05-30"
        }
    }

    test("folder url is percent-encoded on emit; a collision-loser folder (and its subtree) carries null") {
        withTempTree({ root ->
            writePage(root, "café notes/page.md", "# Page\n")
            // Sibling folders 'a b' and 'a-b' both slugify to 'a-b'; 'a b' (0x20) wins the segment.
            writePage(root, "a b/page.md", "# Winner\n")
            writePage(root, "a-b/deep/page.md", "# Loser\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val tree = TreeBuilder.build(harness.builder.rebuild(), RootName.PRIMARY)
                val folders = tree.children.filterIsInstance<TreeNode.Folder>().associateBy { it.name }
                folders.getValue("café notes").url shouldBe "/docs/caf%C3%A9-notes"
                folders.getValue("a b").url shouldBe "/docs/a-b"
                val loser = folders.getValue("a-b")
                loser.url.shouldBeNull()
                loser.children.filterIsInstance<TreeNode.Folder>().single().url.shouldBeNull()
            }
        }
    }

    test("lowercase mmd files are typed leaves, retain exact source paths, and do not affect pageCount") {
        withTempTree({ root ->
            writePage(root, "diagrams/flow.mmd", "graph TD\n  A --> B\n")
            writePage(root, "diagrams/flow.md", "# Flow\n")
            writePage(root, "diagrams/ignored.MMD", "graph TD\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val tree = TreeBuilder.build(harness.builder.rebuild(), RootName.PRIMARY)
                val diagrams = tree.children.filterIsInstance<TreeNode.Folder>().single { it.name == "diagrams" }
                    .children.filterIsInstance<TreeNode.Diagram>()
                diagrams shouldBe listOf(
                    TreeNode.Diagram(
                        title = "flow.mmd",
                        path = TreePath.require("diagrams/flow.mmd"),
                        url = "/browse/docs/diagrams/flow.mmd",
                        sourceUrl = "/assets/docs/diagrams/flow.mmd",
                    ),
                )
                diagrams.first().path shouldBe TreePath.require("diagrams/flow.mmd")
                diagrams.first().let { Files.readString(root.resolve(it.path.value)) } shouldBe "graph TD\n  A --> B\n"
                tree.children.filterIsInstance<TreeNode.Folder>().single { it.name == "diagrams" }.pageCount shouldBe 1
            }
        }
    }
})

/**
 * Renders the SHAPE of a tree: every node with its field values, children as blocks sorted
 * lexicographically by first line — a neutral order, so the golden never asserts collation. Page
 * ids are minted per run and excluded.
 */
private fun shapeDump(node: TreeNode, indent: String = ""): String = when (node) {
    is TreeNode.Folder -> {
        val line = "${indent}folder name='${node.name}' title=${quoted(node.title)} " +
            "description=${quoted(node.description)} path=${quoted(node.path?.value)} url=${quoted(node.url)} " +
            "pageCount=${node.pageCount}"
        val children = node.children.map { shapeDump(it, "$indent  ") }.sorted()
        (listOf(line) + children).joinToString("\n")
    }
    is TreeNode.Page ->
        "${indent}page path='${node.path.value}' title='${node.title}' slug='${node.slug}' " +
            "url=${quoted(node.url)} status='${node.status}' updated=${quoted(node.updated)}"
    is TreeNode.Diagram ->
        "${indent}diagram path='${node.path.value}' title='${node.title}' url=${quoted(node.url)} " +
            "sourceUrl=${quoted(node.sourceUrl)}"
}

private fun quoted(value: String?): String = if (value == null) "-" else "'$value'"

private fun nameOf(node: TreeNode): String = when (node) {
    is TreeNode.Folder -> node.name
    is TreeNode.Page -> node.path.name
    is TreeNode.Diagram -> node.path.name
}
