package com.plainbase

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.readText

/**
 * **"The CLI may not enumerate the server's checks" (C5 D-C5-17), made MECHANICAL.**
 *
 * The rule is worthless as prose - six revisions of the design prove it. Three separate revisions were told, in
 * writing, to run the server's own gate; three separate revisions instead hand-wrote a LIST of the server's
 * checks, and every list was missing an item. Twice the missing items were inside the fix for the previously
 * missing items: rev 5's `native`-history guard covered only the REQUESTED root (so an `off` add could rewrite a
 * topology still containing an invalid `native` root and report success), and it omitted the git version floor
 * (so a git below 2.31 that `serve` REJECTS passed the CLI) - and the audit that confirmed those two found two
 * MORE nobody had caught, a missing-binary check and a repo access probe. **A four-item list, reviewed by three
 * model seats, was missing half of what it was a list OF.** That is not a competence problem. It is what lists do.
 *
 * So the rule stops being prose. `RootCommand.kt` calls ONE function - `bootGateFor` - and may not NAME an
 * individual boot check, nor the WIRING those checks need (a CLI that builds its own stores and history
 * providers has reproduced `serve()`'s graph by hand, which is the same drift one layer down).
 *
 * Matched over COMMENT-STRIPPED source, deliberately: the comments are FREE to name these, and they must. The
 * next reader learns why the CLI does not call them from the comment that says so.
 */
class CliBootGateArchitectureTest : FunSpec({

    val rootCommand = mainSourceRoot().resolve("frameworks/cli/RootCommand.kt")
    val code = stripComments(rootCommand.readText())

    // The individual checks (each one an item somebody's list forgot), and then the wiring they need.
    val banned = listOf(
        "nativeRootGuardFailure",
        "gitVersionFloorFailure",
        "validateExplicitRoots",
        "explicitRootRefusals",
        "bootRefusals",
        "requireContentDir",
        "bindGuardRefusal",
        "gateCheck",
        "versionProbe",
        "RootStores(",
        "HistoryProviders(",
        "koinApplication",
    )

    test("RootCommand names no individual boot check and no boot wiring - it calls ONE function") {
        val violations = banned.filter { code.contains(it) }.map {
            "RootCommand.kt names '$it'. It must not: that is a check (or the wiring a check needs) the CLI would " +
                "then be keeping its own list of, and a list of somebody else's checks always drifts. Call " +
                "bootGateFor and let the server's own gate produce the refusal."
        }
        withClue("the CLI validates the ARTIFACT through the server's own loader and the server's own gate") {
            violations.shouldBeEmpty()
        }
    }

    test("RootCommand DOES call bootGateFor - the positive leg, or this only proves the CLI is quiet") {
        code shouldContain "bootGateFor"
    }
})
