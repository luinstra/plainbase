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
 * A shared function does not stop someone adding a TENTH `exitProcess(1)` to `serve()` next quarter and never
 * telling the CLI. So the boot path's refusal sites are ledgered, one line each, with a written disposition:
 * either the shared gate PRODUCES it (and `plainbase root` gets it for free) or it is EXCLUDED for a reason the
 * code FORCES rather than a reason somebody chose. A tenth appears -> this goes RED -> somebody decides. That
 * decision is the whole point.
 *
 * **Assert a CEILING, not an equality.** The drift that matters is somebody ADDING an unledgered refusal. An
 * `==` would also go red when someone DELETES one, which is backwards: deleting special cases is the goal, and a
 * guard that punishes it teaches people to route around the guard. (`RootWiringArchitectureTest`'s Tier-2 ledger
 * has the `==` polarity and should become a ceiling too; C5 only bumps its counts, but the next person in that
 * file should fix it.)
 */
class BootRefusalLedgerTest : FunSpec({

    // Every `exitProcess(1)` on the boot path, with its CLI disposition. Counted IN THE FILE, not remembered - an
    // exact-count guard is only ever as good as its count.
    val ledger = mapOf(
        "loadForCommand" to "COVERED: the CLI runs the same loader (the same `build`) over the candidate it is about to write",
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
        "mainRootUrlCollisionRefusal" to
            "EXCLUDED: it needs a BUILT PageIndex (scan + render + DB), and it is main-only - and `root` has no code " +
            "path that can write main's name or path into any file, so no value it writes can change the verdict",
    )

    test("every exitProcess(1) on the boot path is ledgered with a CLI disposition") {
        val application = mainSourceRoot().resolve("Application.kt").readText()
        val sites = Regex("""exitProcess\(1\)""").findAll(stripComments(application)).count()
        withClue(
            "a NEW boot refusal appeared in Application.kt and nobody said whether `plainbase root` covers it. " +
                "Either the shared gate produces it (add a COVERED line) or it cannot (add an EXCLUDED line with the " +
                "reason the CODE forces). A silent exclusion is how the CLI writes a config that will not boot.",
        ) {
            sites shouldBeLessThanOrEqual ledger.size
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
        val staged = TOPOLOGY_REFUSAL_KINDS + BIND_REFUSAL_KINDS + VERDICT_REFUSAL_KINDS
        withClue("a new BootRefusal.Kind must be added to one of serve()'s consumption stages, or boot will never print it") {
            staged shouldBe BootRefusal.Kind.entries.toSet()
        }
        val overlap = TOPOLOGY_REFUSAL_KINDS.intersect(BIND_REFUSAL_KINDS) +
            TOPOLOGY_REFUSAL_KINDS.intersect(VERDICT_REFUSAL_KINDS) +
            BIND_REFUSAL_KINDS.intersect(VERDICT_REFUSAL_KINDS)
        withClue("a kind consumed by two stages would be printed twice, from the wrong place in the boot output") {
            overlap shouldBe emptySet()
        }
    }
})
