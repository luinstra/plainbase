package com.plainbase

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * **Main is chosen by the ROOT MODEL and never re-derived by its consumers.** No source may ask whether a `Root` is
 * main by comparing names, except in the four boundary files that DEFINE main (`RootRegistry`), PARSE and VALIDATE it
 * (`PlainbaseConfig`), GATE boot on it (`Application`) and PARSE OPERATOR ARGV (`RootCommand` - text from a command
 * line cannot be made to fail typecheck, so main's protection there is a runtime refusal). Per-root wiring takes main
 * from `registry.main` and folds `registry.extras`.
 *
 * The bug this is a regression guard for SHIPPED in C4: `HistoryModule`'s per-root provider map had a
 * `root.name == registry.main.name -> get<HistoryProvider>()` arm that short-circuited main back to a `single` which
 * had drifted mode-blind. `roots.main.history` was therefore ignored: `history = off` still committed, and
 * `history = native` BYPASSED the strict four-check `claimedRepo` guard - on the root where an operator is MOST likely
 * to be pointing Plainbase at somebody else's checkout. `ContentModule`, `AdoptCommand` and `ReindexCommand` all
 * repeated the same map shape. The fix was to delete the shape, and this pins it deleted.
 *
 * The scan splits the two comparison FAMILIES by hazard, because they are not equally dangerous:
 *
 *  - **Tier 1** - asking a Root whether it is THE REGISTRY'S main (`x.name == y.main.name`). This is the bug shape
 *    itself and it has ZERO legitimate uses: it only ever appears inside a per-root fold. Zero exemptions, forever.
 *  - **Tier 2** - comparing against the `RootName.MAIN` CONSTANT. This is how the root model and the policy layer
 *    legitimately identify main, so it is LEDGERED to the three boundary files at an exact COUNT rather than banned:
 *    a new comparison appearing inside a thousand-line policy file fails the build and forces someone to say why.
 *
 * **Honest limitation: this is a regression guard for a KNOWN BUG SHAPE, not a proof.** It does not catch a fold that
 * binds main to a local first (`val m = registry.main; if (root.name == m.name)`), and it deliberately permits by-NAME
 * RESOLVERS such as `AdoptCommand.adoptedTree` (`root == registry.main.name`, where the left operand is a `RootName`
 * the caller already resolved - not a Root's `.name` interrogated inside a fold). What actually protects the permalink
 * contract is the rank oracle: `RootRegistryTest`, `RootsConfigTest`, `IndexBuilderMultiRootTest`, `AdoptionPassTest`.
 */
class RootWiringArchitectureTest : FunSpec({

    val mainRoot = mainSourceRoot()
    val files = Files.walk(mainRoot).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    // THE HAZARD. Both operand orders. `\w+\.main\.name` rather than a hard-coded `registry`, so a renamed local
    // (`reg`, `topology`) does not slip through.
    val registryMainComparisons = listOf(
        Regex("""\.name\s*[!=]=\s*\w+\.main\.name"""),
        Regex("""\w+\.main\.name\s*[!=]=\s*\w+\.name"""),
    )

    val constantComparisons = listOf(
        Regex("""\.name\s*[!=]=\s*RootName\.MAIN"""),
        Regex("""RootName\.MAIN\s*[!=]=\s*\w+\.name"""),
    )

    // file -> exactly how many main-by-name comparisons it may contain, and why it may.
    val ledger = mapOf(
        // DEFINES main and the extras partition. The ONE place the model may derive them - so that nobody else has to,
        // and since `of` resolves main ONCE over the snapshot, one comparison is all it takes: `extras` partitions
        // against the RESOLVED main, and nothing else searches.
        "RootRegistry.kt" to 1,
        // PARSES and VALIDATES: main's path is fatal where an extra's degrades, the operator-facing required-main
        // refusal, and RootsConfig's own derivations - the config-side twin of the registry.
        //
        // 5 -> 6 in multi-root C5: the machine-managed roots.conf MUST NOT declare main (D-C5-2), and a new file with
        // a new rule needs its own operator-facing refusal. That refusal is the structural guarantee behind "the CLI
        // never manages main" - main's path keeps coming from CONTENT_DIR or from a block the operator wrote.
        "PlainbaseConfig.kt" to 6,
        // GATES boot: main gate-checks unconditionally, an extra is probed and degrades to 503 (ADR-0011 D5-over-D4).
        // Still ONE in C5: that loop MOVED into `evaluateBootGate` - in this same file, deliberately, since a new file
        // would take the comparison with it (drifting this count to 0 AND landing unledgered elsewhere). The C5
        // shadow warning folds `registry.extras` rather than re-filtering, precisely so it costs nothing here.
        "Application.kt" to 1,
        // PARSES OPERATOR ARGV - the fourth boundary, new in multi-root C5. `root add|remove main` must be refused at
        // RUNTIME because argv is TEXT and text cannot be made to fail typecheck. Everywhere else main's protection is
        // structural: the CLI has no code path that can write main's name into any file.
        "RootCommand.kt" to 1,
    )

    test("the scan sees the whole main source tree, the four wiring files included (anti-vacuous floor)") {
        files.size shouldBeGreaterThanOrEqual 100
        val names = files.map { it.name }.toSet()
        names.containsAll(
            setOf("HistoryModule.kt", "ContentModule.kt", "AdoptCommand.kt", "ReindexCommand.kt", "RootRegistry.kt"),
        ).shouldBeTrue()
    }

    test("no source re-selects the registry's main by name: a per-root fold takes main from registry.main and folds registry.extras") {
        val violations = files.flatMap { file ->
            val code = stripComments(file.readText())
            registryMainComparisons.flatMap { pattern -> pattern.findAll(code).map { it.value } }
                .map {
                    "${mainRoot.relativize(file)}: '$it' re-derives main inside a consumer. Take main from " +
                        "`registry.main` and fold `registry.extras` - a map arm that short-circuits main by name is " +
                        "the C4 HistoryModule bug, where main silently ignored its own `history` knob."
                }
        }
        violations.shouldBeEmpty()
    }

    // TIER 3 - the banned HOIST (multi-root C5, D-C5-4). `listOf(main) + extras` forces main to rank 0, and rank is
    // the cross-root duplicate-id winner (LOWEST index wins - PageIdentityService), i.e. WHICH ROOT'S PAGE KEEPS A
    // PERMALINK. So the hoist is a silent permalink reassignment: no error, no log line, the wrong page just answers.
    // An operator who deliberately declared `roots { zeta {…} main {…} }` - zeta first, because zeta's copy of a page
    // is the one that should keep the permalink - would have zeta demoted by a change that never touched zeta.
    //
    // ZERO exemptions: `main` is a typed ACCESSOR, never a promotion. Matched over COMMENT-STRIPPED code, because the
    // only correct place for this literal is a comment WARNING against it - which the merge in `PlainbaseConfig` and
    // the candidate build in `RootCommand` both carry. A plain grep for the pattern would eat its own teaching.
    val bannedHoist = Regex("""listOf\(\s*\w+(\.\w+)*\.main\s*\)\s*\+|listOf\(\s*main\s*\)\s*\+""")

    test("no source builds a root list by hoisting main to the front: `listOf(main) + extras` reassigns permalinks") {
        val violations = files.flatMap { file ->
            bannedHoist.findAll(stripComments(file.readText())).map {
                "${mainRoot.relativize(file)}: '${it.value}' hoists main to rank 0. Rank decides which root's page keeps " +
                    "a permalink on a cross-root duplicate id, so this silently reassigns every shared id to main's " +
                    "page. Preserve the declared order - main sits wherever config put it."
            }
        }
        violations.shouldBeEmpty()
    }

    test("every RootName.MAIN comparison lives in a ledgered boundary file, at exactly the recorded count") {
        val counts = files.associate { file ->
            val code = stripComments(file.readText())
            file.name to constantComparisons.sumOf { pattern -> pattern.findAll(code).count() }
        }.filterValues { it > 0 }

        val unledgered = counts.keys - ledger.keys
        val drifted = ledger.filter { (file, expected) -> counts.getOrDefault(file, 0) != expected }
            .map { (file, expected) -> "$file: ${counts.getOrDefault(file, 0)} comparison(s), ledger says $expected" }

        withClue(
            "main is identified by name ONLY where the root model defines it, config parses it, or boot gates on it. " +
                "A per-root fold must use `registry.main` / `registry.extras` instead; a genuinely new policy " +
                "comparison must bump the ledger above with a reason.",
        ) {
            unledgered.shouldBeEmpty()
            drifted.shouldBeEmpty()
        }
    }
})
