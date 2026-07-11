package com.plainbase

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.localRoot
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * The C3 boot guard pair (ADR-0011 D3(a)), tested as pure functions over REAL built snapshots —
 * the [detachedRootsRefusal] pattern; the serve() call-site integration is deliberately NOT
 * process-tested (the settled C2 ruling: pure-refusal functions + the smoke chunk cover it).
 * [mainRootUrlCollisionRefusal] sweeps the SNAPSHOT (URL-segment level, so `Main`, `slug: main`,
 * and asset dirs are all caught, and object mode works); [deadLegacyAliasWarning] sweeps the alias
 * VIEW and only ever WARNs.
 */
class MainRootCollisionGuardTest : FunSpec({

    fun refusalFor(vararg files: Pair<String, String>): String? {
        var refusal: String? = null
        withTempTree(seed = { root ->
            files.forEach { (path, content) ->
                if (path.endsWith(".md")) {
                    writePage(root, path, content)
                } else {
                    Files.createDirectories(root.resolve(path).parent)
                    Files.write(root.resolve(path), content.toByteArray())
                }
            }
        }) { root ->
            IndexHarness(root).use { harness ->
                refusal = mainRootUrlCollisionRefusal(harness.builder.rebuild())
            }
        }
        return refusal
    }

    test("a top-level directory literally named main refuses, naming the offenders + the forfeiture fact") {
        val refusal = refusalFor("main/setup.md" to "---\ntitle: Setup\n---\n\n# Setup\n")
        refusal.shouldNotBeNull()
        refusal shouldContain "REFUSING TO SERVE"
        refusal shouldContain "main, main/setup" // the folder URL + the page URL, lexicographic
        refusal shouldContain "permanently forfeits"
        refusal shouldContain "Rename the directory"
    }

    test("a directory named Main slugifies to the same colliding segment and refuses (the equivalence class)") {
        refusalFor("Main/setup.md" to "---\ntitle: Setup\n---\n\n# Setup\n").shouldNotBeNull()
    }

    test("a top-level page with frontmatter slug: main refuses (the URL segment, not the filesystem name, decides)") {
        refusalFor("landing.md" to "---\ntitle: Landing\nslug: main\n---\n\n# Landing\n").shouldNotBeNull()
    }

    test("an asset directory named main refuses (assets mirror the redirect grammar)") {
        val refusal = refusalFor(
            "doc.md" to "---\ntitle: Doc\n---\n\n# Doc\n",
            "main/logo.png" to "png-bytes",
        )
        refusal.shouldNotBeNull()
        refusal shouldContain "main/logo.png"
    }

    test("a NESTED x/main/ directory is clean: only the FIRST URL segment is reserved") {
        refusalFor("x/main/setup.md" to "---\ntitle: Setup\n---\n\n# Setup\n").shouldBeNull()
    }

    test("the offender list is bounded and deterministic: first 10 lexicographic, then (+N more)") {
        val files = (1..11).map { "main/page-%02d.md".format(it) to "---\ntitle: P$it\n---\n\n# P\n" }
        val refusal = refusalFor(*files.toTypedArray())
        refusal.shouldNotBeNull()
        // 12 offenders total: the folder URL `main` sorts first, then the first 9 pages; 2 overflow.
        refusal shouldContain
            "main, main/page-01, main/page-02, main/page-03, main/page-04, main/page-05, main/page-06, " +
            "main/page-07, main/page-08, main/page-09 (+2 more)"
    }

    test("an EXTRA root's top-level main directory is harmless: its URLs carry the root segment first") {
        withTempTree(seed = { root -> writePage(root, "guides/page.md", "---\ntitle: P\n---\n\n# P\n") }) { mainDir ->
            val extraDir = Files.createTempDirectory("plainbase-guard-extra")
            try {
                writePage(extraDir, "main/setup.md", "---\ntitle: Setup\n---\n\n# Setup\n")
                val registry = RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("extra", extraDir)))
                val extra = requireNotNull(registry.byName(RootName.require("extra")))
                IndexHarness(
                    mainDir,
                    rootRegistry = registry,
                    sources = listOf(
                        IndexBuilder.Source(registry.main, LocalContentStore(mainDir), NoOpHistoryProvider),
                        IndexBuilder.Source(extra, LocalContentStore(extraDir), NoOpHistoryProvider),
                    ),
                ).use { harness ->
                    mainRootUrlCollisionRefusal(harness.builder.rebuild()).shouldBeNull()
                }
            } finally {
                extraDir.toFile().deleteRecursively()
            }
        }
    }

    test("the demo fixture corpus serves clean") {
        IndexHarness(Fixtures.demoDocs).use { harness ->
            mainRootUrlCollisionRefusal(harness.builder.rebuild()).shouldBeNull()
        }
    }

    context("deadLegacyAliasWarning") {
        val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        fun rooted(root: String, path: String) = RootedPath(RootName.require(root), TreePath.require(path))

        test("a main-root alias row under the reserved segment WARNS, naming the row with the hedged wording") {
            val warning = deadLegacyAliasWarning(mapOf(rooted("main", "main/x") to id))
            warning.shouldNotBeNull()
            warning shouldContain "main/x"
            warning shouldContain "predate the multi-root upgrade" // hedged: a post-C3 row is legitimately reachable
        }

        test("an extra-root main/... alias and a main x/main alias are both silent") {
            deadLegacyAliasWarning(mapOf(rooted("extra", "main/x") to id)).shouldBeNull()
            deadLegacyAliasWarning(mapOf(rooted("main", "x/main") to id)).shouldBeNull()
        }

        test("the row list shares the deterministic bound") {
            val rows = (1..12).associate { rooted("main", "main/a-%02d".format(it)) to id }
            val warning = deadLegacyAliasWarning(rows)
            warning.shouldNotBeNull()
            warning shouldContain "(+2 more)"
        }
    }
})
