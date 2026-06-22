package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The A3 compile-time floor's RUNTIME witness (the synthesis's "no production mint outside PolicyService"
 * source-scan). The HARD guarantee against a FUTURE external module is the COMPILER: the grant constructors are
 * `internal`, so nothing outside the `:server` module can construct a grant. WITHIN `:server` the only legitimate
 * production mint site is `PolicyService`; the `grantForTests*` factories are PUBLIC test-only helpers (so both
 * `src/test` AND `src/nativeTest` can mint — `src/nativeTest` has no friend path to `main`/`src/test`).
 *
 * This scan asserts that NO production source file (`src/main`) outside `PolicyService.kt` constructs a grant OR
 * calls a `grantForTests*` factory — the in-`:server` half of the threat model. (`Grants.kt` DEFINES the
 * constructors + factories, so it is exempt alongside `PolicyService.kt`.)
 */
class GrantUnforgeabilityTest : FunSpec({

    val main = mainSourceRoot()
    val files = Files.walk(main).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    // The grant-mint patterns no production file outside PolicyService may reference.
    val mintPatterns = listOf(
        "EditGrant(",
        "CreateGrant(",
        "ManageGrant(",
        "grantForTests",
        "createGrantForTests",
        "manageGrantForTests",
    )

    // The ONLY production files allowed to reference the mint: PolicyService (the sole production mint site) and
    // Grants.kt (which DEFINES the constructors + the public test factories).
    val exempt = setOf("PolicyService.kt", "Grants.kt")

    test("the scan sees the production tree and the grant + policy files (anti-vacuous floor)") {
        val names = files.map { it.name }.toSet()
        names.containsAll(setOf("PolicyService.kt", "Grants.kt", "WritePipeline.kt", "GuardedMutatingFacade.kt")).shouldBeTrue()
    }

    test("no production file outside PolicyService mints a grant or calls grantForTests*") {
        val violations = files
            .filterNot { it.name in exempt }
            .flatMap { file ->
                val text = file.readText()
                mintPatterns.filter { pattern -> referencesToken(text, pattern) }
                    .map { "${main.relativize(file)}: forbidden grant-mint reference '$it'" }
            }
        violations.shouldBeEmpty()
    }
})
