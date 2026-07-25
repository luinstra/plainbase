package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.CanonicalUrlBuilder
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.localRoot
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FrontmatterReader
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * **THE DIFFERENTIAL. Two structurally different computations of "main's top-level segment space" must agree.**
 *
 * `plainbase root add` derives the index from a plain `ScanResult` - no database, no DATA_DIR lock, no index
 * build. Boot derives it from a fully BUILT `PageIndex` plus the `url_alias` rows. They are the same map by
 * design, and if they ever stop being so, the CLI's shadow refusal starts disagreeing with the boot warning and
 * an operator gets one answer from the command and a different one on restart.
 *
 * **The corpus is the point.** A check that only listed directories would pass a `guides/` folder and sail
 * straight past a page whose frontmatter says `slug: guides` - so the corpus carries every collision kind, plus
 * the same-role slug collisions (WINNER and LOSER) that prove the "a loser cannot remove a segment from the set"
 * claim rather than merely asserting it, plus a non-ASCII directory name.
 *
 * **The ONE deliberate asymmetry is pinned, not hidden:** boot sees `redirect_from` alias rows and the CLI
 * cannot. Those rows live in the DB and outlive the frontmatter that minted them, so no filesystem scan can find
 * one. A future reader learns that gap from a test rather than from an incident.
 */
class RootShadowDifferentialTest : FunSpec({

    /** The CLI's side: a plain scan, through the SAME composition the indexer uses - no second slugification. */
    fun cliIndex(main: Path, dataDir: Path): Map<String, List<TreePath>> {
        val store = LocalContentStore(root = main, ignoreRules = IgnoreRules(), exclusions = listOf(dataDir))
        val scan = store.scan()
        val urls = CanonicalUrlBuilder.build(
            root = RootName.MAIN,
            pages = scan.files.filter { it.path.name.endsWith(".md") }.map { file ->
                CanonicalUrlBuilder.PageInput(
                    path = file.path,
                    rawName = file.rawName,
                    slugOverride = store.read(file.path)?.let { FrontmatterReader().parse(it).scalar("slug") },
                )
            },
            folders = scan.folders,
        )
        return RootShadow.topLevelIndex(
            urlPaths = urls.byPage.values.mapNotNull { it.urlPath } +
                CanonicalUrlBuilder.folderUrlPaths(scan.folders).values.filterNotNull(),
            contentPaths = scan.files.map { it.path } + scan.folders.map { it.path },
        )
    }

    /** Boot's side: the BUILT snapshot (plus, in production, the alias rows - see the asymmetry test below). */
    fun bootIndex(main: Path, aliases: List<TreePath> = emptyList()): Map<String, List<TreePath>> {
        val registry = RootRegistry.of(listOf(localRoot("main", main)))
        val sources = listOf(IndexBuilder.Source(registry.main, LocalContentStore(main), NoOpHistoryProvider))
        return IndexHarness(main, rootRegistry = registry, sources = sources).use { harness ->
            val section = harness.builder.rebuild().section(RootName.MAIN)
            RootShadow.topLevelIndex(
                urlPaths = section.pages.mapNotNull { it.urlPath } +
                    CanonicalUrlBuilder.folderUrlPaths(section.folders).values.filterNotNull() +
                    aliases,
                contentPaths = section.pages.map { it.path } + section.folders.map { it.path } + section.assets,
            )
        }
    }

    fun withCorpus(block: (main: Path, dataDir: Path) -> Unit) {
        val base = Files.createTempDirectory("pb-shadow-diff")
        try {
            val main = Files.createDirectory(base.resolve("main"))
            val data = Files.createDirectory(base.resolve("data"))

            // (a) a plain top-level DIRECTORY
            Files.createDirectories(main.resolve("guides"))
            Files.writeString(main.resolve("guides/deploy.md"), "---\ntitle: Deploy\n---\n\n# D\n")

            // (b) a top-level PAGE whose frontmatter mints a top-level segment
            Files.writeString(main.resolve("anything.md"), "---\ntitle: A\nslug: handbook\n---\n\n# A\n")

            // (c) a top-level FOLDER whose _folder.yaml mints one
            Files.createDirectories(main.resolve("Whatever"))
            Files.writeString(main.resolve("Whatever/_folder.yaml"), "slug: policies\n")
            Files.writeString(main.resolve("Whatever/p.md"), "---\ntitle: P\n---\n\n# P\n")

            // an ASSET directory - content-path space only, never the URL space
            Files.createDirectories(main.resolve("media"))
            Files.write(main.resolve("media/logo.png"), "png".toByteArray())

            // a NESTED page, to prove only the FIRST segment is keyed
            Files.createDirectories(main.resolve("deep/inner"))
            Files.writeString(main.resolve("deep/inner/page.md"), "---\ntitle: N\n---\n\n# N\n")

            // a same-role SLUG COLLISION: two sibling pages contesting one segment. The LOSER carries a null url
            // path - and its WINNER carries the identical segment, so the loser cannot remove a segment from the
            // set. That is the claim; these two files are what prove it.
            Files.writeString(main.resolve("a-winner.md"), "---\ntitle: W\nslug: contested\n---\n\n# W\n")
            Files.writeString(main.resolve("b-loser.md"), "---\ntitle: L\nslug: contested\n---\n\n# L\n")

            // a NON-ASCII directory name (NFC normalization is a real divergence surface)
            Files.createDirectories(main.resolve("ünïcode"))
            Files.writeString(main.resolve("ünïcode/p.md"), "---\ntitle: U\n---\n\n# U\n")

            block(main, data)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    test("the CLI's scan-derived index and BOOT's snapshot-derived index have EQUAL KEY SETS") {
        withCorpus { main, data ->
            val cli = cliIndex(main, data)
            val boot = bootIndex(main)
            withClue("the CLI would refuse a name the boot warn ignores, or vice versa - two answers, one question") {
                cli.keys shouldBe boot.keys
            }
            withClue("every collision kind must actually be IN the corpus, or this differential is vacuous") {
                listOf("guides", "handbook", "policies", "media", "contested", "ünïcode").forEach { segment ->
                    withClue(segment) { (segment in cli.keys) shouldBe true }
                }
            }
            withClue("only the FIRST segment is keyed - a nested deep/inner is not a top-level shadow") {
                ("inner" in cli.keys) shouldBe false
                ("deep" in cli.keys) shouldBe true
            }
        }
    }

    test("a same-role slug LOSER cannot remove a segment from the set - its WINNER carries the identical one") {
        withCorpus { main, data ->
            // Worth pinning rather than asserting in prose: a loser's null url path is exactly the kind of thing a
            // future reader "simplifies" by filtering losers out earlier, and the segment would survive anyway.
            cliIndex(main, data).keys.contains("contested") shouldBe true
            bootIndex(main).keys.contains("contested") shouldBe true
        }
    }

    test("THE KNOWN GAP, pinned: boot sees a redirect_from ALIAS row and the CLI structurally cannot") {
        withCorpus { main, data ->
            val alias = TreePath.require("legacy-name/old-page")
            val boot = bootIndex(main, aliases = listOf(alias))
            val cli = cliIndex(main, data)

            withClue("the alias mints a top-level segment boot can see...") {
                ("legacy-name" in boot.keys) shouldBe true
            }
            withClue("...and the CLI cannot, because the row lives in the DB and the CLI opens no database") {
                ("legacy-name" in cli.keys) shouldBe false
            }
            // This is the single strongest reason the boot WARN exists at all, rather than being "the CLI's check,
            // run again later". A root name that shadows ONLY an alias is added silently, and the warn is what
            // tells the operator on restart.
        }
    }
})
