package com.plainbase.frameworks.config

import com.plainbase.domain.root.HistoryMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The TWO-SOURCE roots merge (multi-root C5, D-C5-1/3/5): the operator's `roots {}` block in `plainbase.conf`
 * and the machine-managed `DATA_DIR/roots.conf` that `plainbase root` owns. Ordering lives in
 * [RootRankStabilityTest] (it is a permalink contract, not a parse detail); filesystem rules stay in
 * [RootsValidationTest].
 */
class ManagedRootsConfigTest : FunSpec({

    fun withFiles(plainbaseConf: String? = null, rootsConf: String? = null, block: (Map<String, String>) -> Unit) {
        val data = Files.createTempDirectory("pb-managed-roots")
        try {
            plainbaseConf?.let { Files.writeString(data.resolve("plainbase.conf"), it) }
            rootsConf?.let { Files.writeString(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), it) }
            block(mapOf("DATA_DIR" to data.toString()))
        } finally {
            Files.walk(data).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    // --- T-CFG-1: roots.conf ALONE is a legal topology (D-C5-3) ---------------------------------------

    test("roots.conf alone yields a SYNTHESIZED main from CONTENT_DIR plus the managed extras, origin EXPLICIT") {
        withFiles(rootsConf = """roots { notes { path = "/roots/notes", editable = true, history = off } }""") { env ->
            val roots = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs")).roots
            roots.origin shouldBe RootsOrigin.EXPLICIT
            roots.list.map { it.name.value } shouldBe listOf("main", "notes")
            roots.main.localPath shouldBe Path.of("/roots/docs")
            // main was NOT hand-declared: it came from CONTENT_DIR. That distinction is what stops the
            // "CONTENT_DIR is ignored" warning from being a lie (T-CFG-9).
            roots.mainDeclared shouldBe false
            roots.managed.map { it.value } shouldBe listOf("notes")
            roots.extras.single().editable shouldBe true
        }
    }

    test("an absent roots.conf and an absent block stay SYNTHESIZED - byte-identical legacy behavior") {
        withFiles { env ->
            PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs")).roots.origin shouldBe RootsOrigin.SYNTHESIZED
        }
    }

    test("the two files MERGE: hand-declared roots and CLI-managed roots serve side by side, file 1 then file 2") {
        withFiles(
            // Separate LINES, deliberately: D7 sorts by origin line with a NAME tiebreak, so two roots sharing one
            // line would order alphabetically (`hand` before `main`) - correct, and beside the point here.
            plainbaseConf = """
                roots {
                  main { path = "/roots/m" }
                  hand { path = "/roots/h" }
                }
            """.trimIndent(),
            rootsConf = """roots { cli { path = "/roots/c" } }""",
        ) { env ->
            val roots = PlainbaseConfig.fromEnvAndFile(env).roots
            roots.list.map { it.name.value } shouldBe listOf("main", "hand", "cli")
            roots.managed.map { it.value } shouldBe listOf("cli")
            roots.mainDeclared shouldBe true
        }
    }

    // --- T-CFG-2 / T-CFG-3: the two refusals the second file introduces --------------------------------

    test("T-CFG-2: a root declared in BOTH files is a boot error naming both files and the root") {
        withFiles(
            plainbaseConf = """roots { main { path = "/roots/m" }, notes { path = "/roots/hand-notes" } }""",
            rootsConf = """roots { notes { path = "/roots/cli-notes" } }""",
        ) { env ->
            val failure = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            failure.message shouldContain "notes"
            failure.message shouldContain "plainbase.conf"
            failure.message shouldContain "roots.conf"
            // Never a merge and never a winner - the mainDirectCommitGlobs idiom: unioning would widen, and
            // picking a winner would drop a declaration the operator wrote.
            failure.message shouldContain "Declare each root ONCE"
        }
    }

    test("T-CFG-3: roots.conf declaring main is a boot error naming the file") {
        withFiles(rootsConf = """roots { main { path = "/roots/m" } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "roots.conf must not declare 'main'"
        }
    }

    // --- T-CFG-8: PRESENCE is the dispatch key, never emptiness (D-C5-3) -------------------------------

    context("T-CFG-8 - an explicitly EMPTY roots {} block is not an ABSENT one") {

        test("(a) an empty block in plainbase.conf still refuses with the required-main message") {
            // A truthiness dispatch (`declared.isEmpty()`) cannot tell an ABSENT block from an EMPTY one, so it
            // would route this into the synthesize arm and SILENTLY REVERT the install to legacy CONTENT_DIR
            // mode - dropping a refusal that fires today.
            withFiles(plainbaseConf = "roots {}") { env ->
                shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                    .message shouldContain "must declare a root named 'main'"
            }
        }

        test("(b) an empty block plus storage.backend=object still refuses with the object-mode message") {
            // The SECOND refusal that hangs off block presence, and the one a truthiness dispatch loses silently.
            withFiles(plainbaseConf = "roots {}") { env ->
                val objectEnv = env + mapOf(
                    "PLAINBASE_STORAGE_BACKEND" to "object",
                    "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                    "PLAINBASE_S3_BUCKET" to "docs",
                    "PLAINBASE_S3_ACCESS_KEY_ID" to "k",
                    "PLAINBASE_S3_SECRET_ACCESS_KEY" to "s",
                )
                shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(objectEnv) }
                    .message shouldContain "roots {} cannot be combined with storage.backend=object"
            }
        }

        test("(c) no roots key at all stays SYNTHESIZED") {
            withFiles(plainbaseConf = """contentDir = "/roots/docs"""") { env ->
                PlainbaseConfig.fromEnvAndFile(env).roots.origin shouldBe RootsOrigin.SYNTHESIZED
            }
        }

        test("(d) an empty block in the MANAGED file IS absence - the asymmetry is the point") {
            // No refusal hangs off the machine file's presence (its one rule, "must not declare main", is vacuous
            // when empty), so a leftover empty husk returns the install to SYNTHESIZED rather than forcing it into
            // the strict EXPLICIT matrix for nothing. (`root remove` of the last root DELETES the file, so this
            // state is only reachable by hand-editing a file whose header says not to.)
            withFiles(rootsConf = "roots {}") { env ->
                PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs")).roots.origin shouldBe RootsOrigin.SYNTHESIZED
            }
        }
    }

    // --- T-CFG-5: the per-root auth block validates against a CLI-added root ---------------------------

    test("T-CFG-5: an agentDirectCommit glob keyed by a MANAGED root resolves (it names a configured root now)") {
        withFiles(
            plainbaseConf = """auth { agentDirectCommit { roots { notes = ["drafts/**"] } } }""",
            rootsConf = """roots { notes { path = "/roots/notes", editable = true } }""",
        ) { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs"))
            config.auth.agentDirectCommitGlobsByRoot.mapKeys { it.key.value } shouldBe mapOf("notes" to listOf("drafts/**"))
        }
    }

    test("T-CFG-5 control: a glob keyed by a root in NEITHER file still refuses the boot") {
        withFiles(
            plainbaseConf = """auth { agentDirectCommit { roots { ghost = ["drafts/**"] } } }""",
            rootsConf = """roots { notes { path = "/roots/notes" } }""",
        ) { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs")) }
                .message shouldContain "names no configured root"
        }
    }

    // --- T-CFG-6: a legacy install that gains ONE managed root flips validation arms --------------------

    test("T-CFG-6: one managed root flips a legacy install to EXPLICIT, and the strict matrix SUBSUMES the legacy guards") {
        // The single most surprising consequence in this chunk, so it is pinned rather than assumed: a legacy
        // CONTENT_DIR install that runs one `root add` stops taking requireContentDir's two legacy `require`s and
        // starts taking validateExplicitRoots' full matrix. That is CORRECT - the matrix must run over the new
        // extras - and it must not LOSE anything: DATA_DIR == CONTENT_DIR is still refused, just with the
        // explicit arm's wording.
        val data = Files.createTempDirectory("pb-cfg6")
        try {
            Files.writeString(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), """roots { notes { path = "/roots/notes" } }""")
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to data.toString()) // DATA_DIR == CONTENT_DIR
            val config = PlainbaseConfig.fromEnvAndFile(env)
            config.roots.origin shouldBe RootsOrigin.EXPLICIT
            shouldThrow<IllegalArgumentException> { config.requireContentDir() }
                .message shouldContain "must be different directories"
        } finally {
            Files.walk(data).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    // --- T-CFG-9: the CONTENT_DIR warning, both directions (D-C5-3 consequence 2) -----------------------

    context("T-CFG-9 - the 'CONTENT_DIR is ignored' warning must not become a LIE") {

        test("a roots.conf-only topology does NOT warn: CONTENT_DIR is exactly where main's path comes from") {
            // Gating this on EXPLICIT rather than on mainDeclared would tell a docker/systemd operator their
            // CONTENT_DIR is ignored while it is still authoritative - and the natural remedy (delete the
            // "ignored" env var) silently repoints main at ./content.
            withFiles(rootsConf = """roots { notes { path = "/roots/notes" } }""") { env ->
                val config = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs"))
                config.rootsWarnings().any { it.contains("CONTENT_DIR") && it.contains("ignored") } shouldBe false
                config.mainContentRoot() shouldBe Path.of("/roots/docs")
            }
        }

        test("a hand-declared roots.main DOES warn - CONTENT_DIR really is ignored there") {
            withFiles(
                plainbaseConf = """roots { main { path = "/roots/m" } }""",
                rootsConf = """roots { notes { path = "/roots/notes" } }""",
            ) { env ->
                val config = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/legacy"))
                config.rootsWarnings().any { it.contains("CONTENT_DIR") && it.contains("ignored") } shouldBe true
                config.mainContentRoot() shouldBe Path.of("/roots/m")
            }
        }
    }

    // --- the managed file's own grammar ----------------------------------------------------------------

    test("the managed file's knobs parse exactly like the operator's: history native, editable false by default") {
        withFiles(rootsConf = """roots { repo { path = "/roots/repo", history = native } }""") { env ->
            val extra = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs")).roots.extras.single()
            extra.history shouldBe HistoryMode.NATIVE
            extra.editable shouldBe false
        }
    }

    test("history = auto in the managed file is still the D4 boot error - the loader owns that rule, not the CLI's flag grammar") {
        // The CLI's `--history off|native` grammar means IT cannot construct an AUTO extra. This is the other half:
        // a hand-edited managed file still meets the boot rule, because the rule lives in the loader.
        withFiles(rootsConf = """roots { repo { path = "/roots/repo", history = auto } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs")) }
                .message shouldContain "history = auto is not allowed on an extra root"
        }
    }
})
