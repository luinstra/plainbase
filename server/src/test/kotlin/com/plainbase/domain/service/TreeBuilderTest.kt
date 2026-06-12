package com.plainbase.domain.service

import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
        IndexHarness(Fixtures.demoDocs).use { harness -> block(TreeBuilder.build(harness.builder.rebuild())) }

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
            val treasure = node.children.filterIsInstance<TreeNode.Page>().single()
            treasure.url shouldBe "/docs/notes/deeply/nested/folder/treasure"
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
        val line = "${indent}folder name='${node.name}' title=${quoted(node.title)} path=${quoted(node.path?.value)}"
        val children = node.children.map { shapeDump(it, "$indent  ") }.sorted()
        (listOf(line) + children).joinToString("\n")
    }
    is TreeNode.Page ->
        "${indent}page path='${node.path.value}' title='${node.title}' slug='${node.slug}' " +
            "url=${quoted(node.url)} status='${node.status}'"
}

private fun quoted(value: String?): String = if (value == null) "-" else "'$value'"

private fun nameOf(node: TreeNode): String = when (node) {
    is TreeNode.Folder -> node.name
    is TreeNode.Page -> node.path.name
}
