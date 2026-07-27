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
 * **Primary is chosen by the ROOT MODEL and never re-derived by its consumers.** No source may ask whether a `Root` is
 * primary by comparing names, except in the three boundary files that DEFINE primary (`RootRegistry`), PARSE and VALIDATE it
 * (`PlainbaseConfig`) and PARSE OPERATOR ARGV (`RootCommand` - text from a command line cannot be made to fail typecheck,
 * so primary's protection there is a runtime refusal). `Application` is deliberately absent: the boot gate must not know
 * primary's name, so any comparison there fails this test. Per-root wiring takes primary from `registry.primary` and folds
 * `registry.extras`.
 *
 * The bug this is a regression guard for SHIPPED in C4: `HistoryModule`'s per-root provider map had a
 * `root.name == registry.primary.name -> get<HistoryProvider>()` arm that short-circuited the primary root back to a
 * `single` which had drifted mode-blind. `roots.main.history` was therefore ignored: `history = off` still committed, and
 * `history = native` BYPASSED the strict four-check `claimedRepo` guard - on the root where an operator is MOST likely
 * to be pointing Plainbase at somebody else's checkout. `ContentModule`, `AdoptCommand` and `ReindexCommand` all
 * repeated the same map shape. The fix was to delete the shape, and this pins it deleted.
 *
 * The scan splits the two comparison FAMILIES by hazard, because they are not equally dangerous:
 *
 *  - **Tier 1** - asking a Root whether it is THE REGISTRY'S primary (`x.name == y.primary.name`). This is the bug shape
 *    itself and it has ZERO legitimate uses: it only ever appears inside a per-root fold. Zero exemptions, forever.
 *  - **Tier 2** - comparing against the `RootName.PRIMARY` CONSTANT. This is how the root model and the policy layer
 *    legitimately identify the primary root, so it is LEDGERED to the three boundary files at an exact COUNT rather than banned:
 *    a new comparison appearing inside a thousand-line policy file fails the build and forces someone to say why.
 *
 * **Honest limitation: this is a regression guard for a KNOWN BUG SHAPE, not a proof.** It does not catch a fold that
 * binds primary to a local first (`val p = registry.primary; if (root.name == p.name)`), and it deliberately permits by-NAME
 * RESOLVERS such as `AdoptCommand.adoptedTree` (`root == registry.primary.name`, where the left operand is a `RootName`
 * the caller already resolved - not a Root's `.name` interrogated inside a fold). The declared ORDER, which Tier 3
 * below guards, is pinned independently by `RootRegistryTest`, `RootsConfigTest`, `IndexBuilderMultiRootTest` and
 * `AdoptionPassTest`.
 */
class RootWiringArchitectureTest : FunSpec({

    val mainRoot = mainSourceRoot()
    val files = Files.walk(mainRoot).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    // THE HAZARD. Both operand orders. `\w+\.primary\.name` rather than a hard-coded `registry`, so a renamed local
    // (`reg`, `topology`) does not slip through.
    val registryPrimaryComparisons = listOf(
        Regex("""\.name\s*[!=]=\s*\w+\.primary\.name"""),
        Regex("""\w+\.primary\.name\s*[!=]=\s*\w+\.name"""),
    )

    val constantComparisons = listOf(
        Regex("""\.name\s*[!=]=\s*RootName\.PRIMARY"""),
        Regex("""RootName\.PRIMARY\s*[!=]=\s*\w+\.name"""),
    )

    // file -> exactly how many primary-by-name comparisons it may contain, and why it may.
    val ledger = mapOf(
        // DEFINES primary and the extras partition. The ONE place the model may derive them - so that nobody else has to,
        // and since `of` resolves primary ONCE over the snapshot, one comparison is all it takes: `extras` partitions
        // against the RESOLVED primary, and nothing else searches.
        "RootRegistry.kt" to 1,
        // PARSES and VALIDATES: the primary root's path is fatal where an extra's degrades, the operator-facing required-primary
        // refusal, and RootsConfig's own derivations - the config-side twin of the registry.
        //
        // 5 -> 6 in multi-root C5: the machine-managed roots.conf MUST NOT declare main (D-C5-2), and a new file with
        // a new rule needs its own operator-facing refusal. That refusal is the structural guarantee behind "the CLI
        // never manages main" - main's path keeps coming from CONTENT_DIR or from a block the operator wrote.
        "PlainbaseConfig.kt" to 6,
        // Application.kt is DELIBERATELY ABSENT, and its absence is a fix rather than an omission. The boot gate used
        // to hold exactly one comparison - `root.name != RootName.PRIMARY` - which exempted the primary root from the availability
        // probe every other root took, so a late mount refused the whole boot for the primary root and degraded to 503 for an
        // extra: two behaviors for one condition, chosen by which root it happened to be. `rootGateVerdicts` now
        // probes EVERY root, and the gate no longer knows main's name at all. A comparison reappearing here is that
        // special case coming back, and it should fail this test.
        //
        // PARSES OPERATOR ARGV - the third boundary, new in multi-root C5. `root add|remove main` must be refused at
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

    test(
        "no source re-selects the registry's primary by name: a per-root fold takes primary from registry.primary and " +
            "folds registry.extras",
    ) {
        val violations = files.flatMap { file ->
            val code = stripComments(file.readText())
            registryPrimaryComparisons.flatMap { pattern -> pattern.findAll(code).map { it.value } }
                .map {
                    "${mainRoot.relativize(file)}: '$it' re-derives primary inside a consumer. A map arm that " +
                        "short-circuits the primary root by name is the C4 HistoryModule bug, where the primary root " +
                        "silently ignored its own `history` knob. For WIRING, take primary from `registry.primary` and " +
                        "fold `registry.extras`. For an order-preserving PROJECTION, that advice does not apply (it " +
                        "yields `listOf(primary) + extras`, which Tier 3 below bans): ask `registry.isPrimary(root)`, " +
                        "which is ledgered by the next test."
                }
        }
        violations.shouldBeEmpty()
    }

    // `isPrimary` answers the Tier-1 question through the MODEL, which is legitimate for a projection and is why
    // TreeJsonCache may emit `RootTreeDto.primary`. But it is also a per-root boolean, so `if (registry.isPrimary(root))
    // ... else ...` re-spells the C4 arm in a shape Tier 1 cannot see: its regexes need a qualified `.primary.name`
    // receiver and this call has none. Two review seats filed that as blocking. The capability therefore carries the
    // same LEDGER discipline as the Tier-2 constant: allowed where recorded, at an exact count, and a new caller has to
    // come here and say why. The DEFINITION is excluded - only call sites are counted.
    val isPrimaryCallSiteLedger = mapOf("TreeJsonCache.kt" to 1)
    val isPrimaryCall = Regex("""\bisPrimary\s*\(""")
    val isPrimaryDefinition = Regex("""fun\s+isPrimary\s*\(""")

    test("every `registry.isPrimary(...)` call site is ledgered: the projection may ask, a per-root fold may not") {
        val counts = files.associate { file ->
            val code = stripComments(file.readText())
            file.name to isPrimaryCall.findAll(code).count() - isPrimaryDefinition.findAll(code).count()
        }.filterValues { it > 0 }

        withClue(
            "asking the model is fine for a PROJECTION that must emit primary-ness. It is NOT fine for a per-root " +
                "fold: `if (registry.isPrimary(root)) ... else ...` is the C4 HistoryModule arm wearing a different " +
                "spelling, and Tier 1 cannot see it. A new call site must be added here with a reason.",
        ) {
            (counts.keys - isPrimaryCallSiteLedger.keys).shouldBeEmpty()
            isPrimaryCallSiteLedger.filter { (file, expected) -> counts.getOrDefault(file, 0) != expected }
                .map { (file, expected) -> "$file: ${counts.getOrDefault(file, 0)} call(s), ledger says $expected" }
                .shouldBeEmpty()
        }
    }

    // TIER 3 - the banned HOIST (multi-root C5, D-C5-4). `listOf(primary) + extras` forces primary to rank 0, silently
    // re-ordering every other root. Rank no longer decides which root's page keeps a permalink (per-root identity,
    // ADR-0012: both roots keep their own page and a bare id with several holders answers 300, never a rank pick),
    // but it IS an operator-visible ordering contract: source precedence and the order candidates are offered in.
    // An operator who deliberately declared `roots { zeta {…} main {…} }` - zeta first, because zeta's copy is the
    // one that should be read first - would have zeta demoted by a change that never touched zeta.
    //
    // ZERO exemptions: `primary` is a typed ACCESSOR, never a promotion. Matched over COMMENT-STRIPPED code, because the
    // only correct place for this literal is a comment WARNING against it - which the merge in `PlainbaseConfig` and
    // the candidate build in `RootCommand` both carry. A plain grep for the pattern would eat its own teaching.
    // The three receiver spellings share ONE `,?`, deliberately. Written as three separate alternatives each
    // carrying its own, the row below would pin the trailing comma for whichever alternative it happened to
    // exercise and leave the other two free to be narrowed back: `listOf(main,)` escaped exactly that way.
    // One optional comma means one back-out breaks all three, so one row is a total falsifier.
    val bannedHoist = Regex("""listOf\(\s*(?:\w+(?:\.\w+)*\.primary|main|primary)\s*,?\s*\)\s*\+""")

    test("the Tier-3 pattern catches the trailing-comma form, for every receiver spelling") {
        // `main` is not legacy residue: four locals still bind the primary root under that name
        // (ContentModule, HistoryModule, PlainbaseConfig), so it is the spelling likeliest to appear.
        listOf("registry.primary", "main", "primary").forEach { receiver ->
            withClue("a hoist through `$receiver` with a trailing comma must not evade the guard") {
                bannedHoist.containsMatchIn("listOf(\n    $receiver,\n) + extras").shouldBeTrue()
            }
        }
    }

    test("no source builds a root list by hoisting primary to the front: `listOf(primary) + extras` re-orders every root") {
        val violations = files.flatMap { file ->
            bannedHoist.findAll(stripComments(file.readText())).map {
                "${mainRoot.relativize(file)}: '${it.value}' hoists primary to rank 0, silently re-ordering every other " +
                    "root. Rank is the operator's declared source precedence, not an id winner. Preserve the declared " +
                    "order - primary sits wherever config put it."
            }
        }
        violations.shouldBeEmpty()
    }

    test("every RootName.PRIMARY comparison lives in a ledgered boundary file, at exactly the recorded count") {
        val counts = files.associate { file ->
            val code = stripComments(file.readText())
            file.name to constantComparisons.sumOf { pattern -> pattern.findAll(code).count() }
        }.filterValues { it > 0 }

        val unledgered = counts.keys - ledger.keys
        val drifted = ledger.filter { (file, expected) -> counts.getOrDefault(file, 0) != expected }
            .map { (file, expected) -> "$file: ${counts.getOrDefault(file, 0)} comparison(s), ledger says $expected" }

        withClue(
            "the primary root is identified by name ONLY in the boundary files the ledger above names, at the count " +
                "it records. A per-root fold must use `registry.primary` / `registry.extras` instead; a genuinely " +
                "new policy comparison must bump the ledger above with a reason.",
        ) {
            unledgered.shouldBeEmpty()
            drifted.shouldBeEmpty()
        }
    }
})
