package com.plainbase

import com.plainbase.domain.root.BootRefusal
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.io.path.readText

/**
 * **The drift-proofing (C5 S1.7b), and the reason the shared boot gate is a STRUCTURAL fix rather than another
 * good intention.**
 *
 * A shared function does not stop someone adding an unledgered refusal to the owned runtime next quarter and never
 * telling the CLI. So the seven owned refusal semantics and the loader's separate semantic are ledgered, one line
 * each, with a written disposition: either the shared gate PRODUCES it (and `plainbase root` gets it for free) or
 * it is EXCLUDED for a reason the code FORCES rather than a reason somebody chose. A new site appears -> this goes
 * RED -> somebody decides. That decision is the whole point.
 *
 * **Assert a CEILING, not an equality** - but a ceiling that is TIGHT, or it is not a guard at all. The drift
 * that matters is somebody ADDING an unledgered refusal. An `==` would also go red when someone DELETES one,
 * which is backwards: deleting special cases is the goal, and a guard that punishes it teaches people to route
 * around the guard. (`RootWiringArchitectureTest`'s Tier-2 ledger has the `==` polarity and should become a
 * ceiling too; C5 only bumps its counts, but the next person in that file should fix it.)
 *
 * The tightness is the subtle half, and it went wrong once already: `ledger.size` is NOT the site count, because
 * the owned helper has six textual call sites for seven semantic entries: topology and bind share `refuseFirst`,
 * while the wrapper has one independent `exitProcess(1)` for the loader's status. The two derived ceilings are
 * therefore owned entries minus one shared site, and wrapper exits at most one.
 */
class BootRefusalLedgerTest : FunSpec({

    // Every boot refusal semantic, with its CLI disposition. Counted IN THE FILE, not remembered - a guard is only
    // ever as good as its count.
    val ledger = mapOf(
        "loadForCommand" to "COVERED: the CLI runs ConfigLoader and ConfigDecoder.decode over the candidate it is about to write",
        "gate: topology matrix" to "COVERED: evaluateBootGate, via bootGateFor",
        "gate: bind guard" to "COVERED: evaluateBootGate, via bootGateFor",
        "gate: per-root git gate" to "COVERED: evaluateBootGate, via bootGateFor",
        "DataDirLock contention" to
            "EXCLUDED: a CONCURRENCY refusal, not a config one - no candidate roots.conf can change who ELSE holds " +
            "the DATA_DIR, and `plainbase root` takes roots.lock rather than plainbase.lock (D-C5-9), precisely so " +
            "staging a topology change WHILE the server runs works",
        "detachedRootsRefusal" to
            "EXCLUDED: it reads id_map - the app DB - which may not be opened before the DATA_DIR lock (the first " +
            "open runs the MIGRATION), and `plainbase root` does not take that lock. MITIGATED, not shrugged at: " +
            "`root remove` PRINTS the consequence unconditionally, and RootCommandTest pins that it does.",
        "object-mode bundle restore + hydrate" to
            "EXCLUDED: object mode plus a roots {} block never LOADS, and the CLI's candidate always carries one - " +
            "so no config the CLI can write ever reaches it",
        "prepare()" to
            "EXCLUDED: it MUTATES (git init, the git-home mkdir). The gate is pure inspection - BootGatePurityTest " +
            "diffs the filesystem across a run - and nothing `root` writes changes prepare()'s outcome anyway.",
    )

    val ownedLedger = ledger - "loadForCommand"
    val loaderLedger = ledger.filterKeys { it == "loadForCommand" }
    // `refuseFirst` is ONE textual refuseServe call, called from the topology stage AND the bind stage - so those
    // two owned entries are backed by a single site. Nothing else in Application.kt shares one.
    val entriesSharingASite = 1

    test("owned refusal sites and the loader wrapper stay within their separate ledger ceilings") {
        val application = mainSourceRoot().resolve("Application.kt").readText()
        val source = stripComments(application)
        val allRefusalNames = Regex("""\brefuseServe\s*\(""").findAll(source).count()
        val declarationNames = Regex("""private\s+fun\s+refuseServe\s*\(""").findAll(source).count()
        val ownedSites = allRefusalNames - declarationNames
        val loaderExitSites = Regex("""exitProcess\(1\)""").findAll(source).count()
        withClue(
            "a NEW owned refusal appeared in Application.kt and nobody said whether `plainbase root` covers it. " +
                "Either the shared gate produces it (add a COVERED line) or it cannot (add an EXCLUDED line with the " +
                "reason the CODE forces). A silent exclusion is how the CLI writes a config that will not boot.",
        ) {
            ownedSites shouldBeLessThanOrEqual ownedLedger.size - entriesSharingASite
        }
        withClue("the loader must have one explicit status-1 process boundary, never a second owned exit") {
            loaderExitSites shouldBeLessThanOrEqual loaderLedger.size
        }
    }

    test("every ledger entry carries a disposition, and each is COVERED or EXCLUDED") {
        ledger.values.forEach { disposition ->
            withClue(disposition) {
                (disposition.startsWith("COVERED") || disposition.startsWith("EXCLUDED")) shouldBe true
            }
        }
    }

    // The other half of the same guarantee: a refusal KIND the gate produces but `serve()` consumes in NO stage
    // would be a refusal the CLI enforces and boot silently IGNORES - the worst of both worlds. Staged consumption
    // is what keeps a git refusal from swallowing the warnings that print before it today, and it is also what
    // would let a new kind fall through the cracks. So the stages must PARTITION the enum.
    test("serve()'s consumption stages partition BootRefusal.Kind - a new kind belongs to exactly one of them") {
        // DEGRADED is a disposition, not an exemption: `serve()` does not REFUSE on those kinds, it degrades the
        // root to 503 through the verdict loop. A kind that is in NO set is one boot silently drops, which is what
        // this partition exists to prevent - so the degrading kinds are stated, not left out.
        val stages = listOf(TOPOLOGY_REFUSAL_KINDS, BIND_REFUSAL_KINDS, VERDICT_REFUSAL_KINDS, DEGRADED_REFUSAL_KINDS)
        withClue("a new BootRefusal.Kind must be added to one of serve()'s consumption stages, or boot will never print it") {
            stages.flatten().toSet() shouldBe BootRefusal.Kind.entries.toSet()
        }
        withClue("a kind consumed by two stages would be printed twice, from the wrong place in the boot output") {
            stages.flatten().size shouldBe stages.flatten().toSet().size
        }
    }
})
