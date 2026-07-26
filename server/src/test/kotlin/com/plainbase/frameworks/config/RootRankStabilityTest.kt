package com.plainbase.frameworks.config

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.localRoot
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * **INVARIANT R (rank stability), and it is a DATA-CORRECTNESS contract wearing a config-plumbing costume.**
 *
 * Root RANK is not cosmetic. It is the operator's declared SOURCE PRECEDENCE (ADR-0012), lowest index first:
 * which root a pass reads before another, and the order candidates are offered in when a bare id has several
 * holders. It no longer decides which root's page keeps a permalink - per-root identity made the same id in two
 * roots two legitimate pages, and a bare id with several holders answers 300 rather than picking a winner.
 * **A wrong rank still produces no error and no log line**, which is why it is pinned here.
 *
 * > Adding or removing a root in `roots.conf` never changes the RELATIVE rank of any other root. A newly added
 * > root always ranks LAST, so it never outranks an incumbent. A hand-declared root always outranks
 * > every CLI-added root, and **`main` keeps the rank its own declaration gave it.**
 *
 * The two ways to break it, both of which an earlier revision of this design actually wrote:
 *
 *  - **Hoisting main** (`listOf(main) + extras`) forces main to rank 0, demoting every root an operator
 *    deliberately declared ahead of it. Someone who wrote `roots { zeta {…} main {…} }` - zeta first, because
 *    zeta's copy is the one that should be read first - would have had zeta demoted by a CLI change that never
 *    touched zeta, silently re-ordering precedence they set on purpose.
 *  - **Sorting `(line, name)` ACROSS both files** (the literal reading of ADR-0011's own D7 aside) lets a
 *    CLI-added root at `roots.conf` line 4 outrank a hand-declared incumbent at `plainbase.conf` line 8. Worse,
 *    it is not even stable: adding a root rewrites `roots.conf` and shifts the line numbers of roots the
 *    operator never touched, RE-RANKING them.
 */
class RootRankStabilityTest : FunSpec({

    fun withFiles(plainbaseConf: String?, rootsConf: String?, block: (Map<String, String>) -> Unit) {
        val data = Files.createTempDirectory("pb-rank")
        try {
            plainbaseConf?.let { Files.writeString(data.resolve("plainbase.conf"), it) }
            rootsConf?.let { Files.writeString(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), it) }
            block(mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to "/roots/docs"))
        } finally {
            Files.walk(data).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun managedRoot(name: String) = Root(
        name = RootName.require(name),
        backend = RootBackend.Local(Path.of("/roots", name)),
        editable = false,
        history = HistoryMode.OFF,
    )

    fun namesOf(env: Map<String, String>): List<String> =
        PlainbaseConfig.fromEnvAndFile(env).roots.list.map { it.name.value }

    fun rankOf(env: Map<String, String>, name: String): Int =
        RootRegistry.of(PlainbaseConfig.fromEnvAndFile(env).roots.list).rank(RootName.require(name))

    test("(a) main keeps its DECLARED rank when roots.conf extras merge - it is NEVER hoisted to 0") {
        // The assertion this whole decision exists for. It goes RED under `listOf(main) + declaredExtras +
        // managedExtras`, which yields [main, zeta, alpha] and rank 0 - and so do RootsConfigTest's and
        // RootRegistryTest's shipped `rank(MAIN) shouldBe 1` pins, which is the safety net doing its job.
        withFiles(
            plainbaseConf = """
                roots {
                  zeta { path = "/roots/z" }
                  main { path = "/roots/m" }
                }
            """.trimIndent(),
            rootsConf = """roots { alpha { path = "/roots/a" } }""",
        ) { env ->
            namesOf(env) shouldBe listOf("zeta", "main", "alpha")
            rankOf(env, "main") shouldBe 1 // NOT 0. `main` is a typed ACCESSOR, never a promotion.
            rankOf(env, "zeta") shouldBe 0
        }
    }

    test("(b) a hand-declared root at a HIGH line number still outranks a CLI-added root at a LOW one") {
        // The case a naive cross-file `(line, name)` sort gets exactly backwards: `zebra` sits deep in
        // plainbase.conf while `notes` sits at the top of roots.conf, so that sort would rank the CLI-added
        // `notes` FIRST - letting it take permalinks from an incumbent the operator declared by hand. Line
        // numbers are compared WITHIN a file and never ACROSS files, so there is no collision to tiebreak.
        withFiles(
            plainbaseConf = """
                # a comment
                # another comment
                # and another
                # and another
                # and another
                roots {
                  main  { path = "/roots/m" }
                  zebra { path = "/roots/zebra" }
                }
            """.trimIndent(),
            rootsConf = """
                roots {
                  notes { path = "/roots/notes" }
                }
            """.trimIndent(),
        ) { env ->
            namesOf(env) shouldBe listOf("main", "zebra", "notes")
            (rankOf(env, "zebra") < rankOf(env, "notes")) shouldBe true
        }
    }

    test("(c) adding a root leaves the relative rank of EVERY existing root unchanged, and the newcomer lands LAST") {
        // Separate LINES, so the D7 ORIGIN-LINE sort is what puts zeta ahead of main - which is the whole point of
        // the case. (On one line the NAME tiebreak would decide instead, and `main` sorts before `zeta`.)
        val declared = """
            roots {
              zeta { path = "/roots/z" }
              main { path = "/roots/m" }
            }
        """.trimIndent()
        // Through the REAL writer, not a hand-written file: the ordering guarantee is the writer's ("regenerate in
        // parsed order, append the new one at the end"), so a hand-rolled fixture would be testing the fixture.
        // `aardvark` sorts FIRST alphabetically - one of the two things a broken sort would key on - and it must
        // still land last.
        val before = ManagedRootsFile.serialize(listOf(managedRoot("alpha")))
        val after = ManagedRootsFile.serialize(listOf(managedRoot("alpha"), managedRoot("aardvark")))

        var sequenceBefore: List<String> = emptyList()
        withFiles(declared, before) { env -> sequenceBefore = namesOf(env) }
        sequenceBefore shouldBe listOf("zeta", "main", "alpha")

        withFiles(declared, after) { env ->
            namesOf(env) shouldBe listOf("zeta", "main", "alpha", "aardvark")
            // The full ranked sequence of the roots that were ALREADY there, unchanged. The invariant is about
            // every PAIR of survivors, not just the newcomer's position.
            namesOf(env).filter { it in sequenceBefore } shouldBe sequenceBefore
            // And every hand-declared root still outranks every CLI-added one, which is what makes `D ++ M` the
            // rule rather than an accident of this fixture.
            listOf("zeta", "main").forEach { hand ->
                listOf("alpha", "aardvark").forEach { cli ->
                    (rankOf(env, hand) < rankOf(env, cli)) shouldBe true
                }
            }
        }
    }

    test("(c2) removing a root preserves the survivors' relative order") {
        // Separate LINES, so the D7 ORIGIN-LINE sort is what puts zeta ahead of main - which is the whole point of
        // the case. (On one line the NAME tiebreak would decide instead, and `main` sorts before `zeta`.)
        val declared = """
            roots {
              zeta { path = "/roots/z" }
              main { path = "/roots/m" }
            }
        """.trimIndent()
        val all = listOf(managedRoot("alpha"), managedRoot("beta"), managedRoot("gamma"))
        withFiles(declared, ManagedRootsFile.serialize(all.filterNot { it.name.value == "beta" })) { env ->
            namesOf(env) shouldBe listOf("zeta", "main", "alpha", "gamma")
        }
    }

    test("(d) a newcomer sharing an incumbent's frontmatter id is NOT a contest: BOTH roots keep the id (per-root identity, C5)") {
        // Post-flip a cross-root duplicate id is LEGAL: both pages keep the same id and each answers its OWN
        // /p/{root}/{id}. Rank no longer takes the id from the newcomer - it decides SOURCE ORDERING only (the
        // higher-ranked incumbent's section still precedes the newcomer's). This asserts BOTH roots survive, never
        // one; tests (a)-(c) still pin rank STABILITY, which is untouched by the flip.
        val contested = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        val page = "---\nid: ${contested.value}\ntitle: Doc\n---\n\n# Doc\n"

        val mainDir = Files.createTempDirectory("pb-rank-main")
        val incumbentDir = Files.createTempDirectory("pb-rank-incumbent")
        val newcomerDir = Files.createTempDirectory("pb-rank-newcomer")
        try {
            writePage(incumbentDir, "guides/doc.md", page)
            writePage(newcomerDir, "guides/doc.md", page)
            val incumbent = RootName.require("incumbent")
            val newcomer = RootName.require("newcomer")
            val registry = RootRegistry.of(
                listOf(localRoot("main", mainDir), localRoot("incumbent", incumbentDir), localRoot("newcomer", newcomerDir)),
            )
            val sources = registry.roots.map { root ->
                IndexBuilder.Source(root, LocalContentStore(requireNotNull(root.localPath)), NoOpHistoryProvider)
            }
            IndexHarness(mainDir, rootRegistry = registry, sources = sources).use { harness ->
                val snapshot = harness.builder.rebuild()
                // BOTH roots hold their own page under the SAME id, each at its own path.
                val incumbentPage = requireNotNull(snapshot.pageAt(RootedPageId(incumbent, contested)))
                val newcomerPage = requireNotNull(snapshot.pageAt(RootedPageId(newcomer, contested)))
                incumbentPage.path shouldBe TreePath.require("guides/doc.md")
                newcomerPage.path shouldBe TreePath.require("guides/doc.md")
                // Rank still decides SOURCE ORDERING: the incumbent's section precedes the newcomer's.
                snapshot.sections.map { it.root }.filter { it == incumbent || it == newcomer } shouldBe listOf(incumbent, newcomer)
            }
        } finally {
            listOf(mainDir, incumbentDir, newcomerDir).forEach { it.toFile().deleteRecursively() }
        }
    }

    test("a roots.conf-only topology ranks the synthesized main first by ARITHMETIC, not by policy") {
        // With no operator block, the synthesized main is the SOLE file-1 entry, so it ranks first because it is
        // the only thing there - which is exactly today's legacy behavior, and the only order that list could have.
        withFiles(plainbaseConf = null, rootsConf = """roots { notes { path = "/roots/n" } }""") { env ->
            namesOf(env) shouldBe listOf("main", "notes")
            rankOf(env, "main") shouldBe 0
        }
    }
})
