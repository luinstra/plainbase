package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * PB-PATCH-1 (§A3) byte-pair golden suite — the FROZEN acceptance behavior, compared byte-for-byte.
 *
 * Fixtures live under `resources/golden/patcher/`: `<case>.in` paired with either `<case>.out`
 * (Patched — compared byte-identical), `<case>.alreadypresent` (AlreadyPresent — input returned
 * unchanged), or `<case>.refused` (Refused — the file's first line names the [RefusalReason]). All
 * fixtures are authored with a canonical UUID example id from the start — no ULID ever existed.
 *
 * **Asymmetric freeze (§A3):** every `.out` here is permanently frozen — an accepted input and its
 * insertion can never change. A `.refused` case may LATER become an acceptance via a documented
 * relaxation (never the reverse), so a future revision adding a `.out` for a today-refused case is
 * legal; flipping an `.out` is not.
 */
class FrontmatterPatcherGoldenTest : FunSpec({

    val patcher = FrontmatterPatcher()
    // The frozen example id all patcher goldens are authored with (§A3): 36-char canonical lowercase.
    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    val dir = File(FrontmatterPatcherGoldenTest::class.java.getResource("/golden/patcher")!!.toURI())

    fun fixturesWith(suffix: String): List<File> =
        dir.listFiles { f -> f.name.endsWith(suffix) }!!.sortedBy { it.name }

    fun inputOf(fixture: File): ByteArray = File(dir, fixture.name.substringBeforeLast('.') + ".in").readBytes()

    context("Patched byte-pairs (.in/.out) are byte-identical to the golden") {
        for (out in fixturesWith(".out")) {
            val case = out.name.removeSuffix(".out")
            test("$case: single-point insertion matches the golden bytes") {
                val result = patcher.patch(inputOf(out), id).shouldBeInstanceOf<FrontmatterPatcher.PatchResult.Patched>()
                result.bytes.toList() shouldBe out.readBytes().toList()
            }
        }
    }

    context("AlreadyPresent cases return input-identical bytes (idempotent)") {
        for (marker in fixturesWith(".alreadypresent")) {
            val case = marker.name.removeSuffix(".alreadypresent")
            test("$case: AlreadyPresent (no-op)") {
                val input = inputOf(marker)
                patcher.patch(input, id) shouldBe FrontmatterPatcher.PatchResult.AlreadyPresent
                // Idempotence: patching twice still inserts nothing (the contract's patch(patch(x))).
                patcher.patch(input, id) shouldBe FrontmatterPatcher.PatchResult.AlreadyPresent
            }
        }
    }

    context("Refused cases carry the frozen reason named in the golden") {
        for (refused in fixturesWith(".refused")) {
            val case = refused.name.removeSuffix(".refused")
            test("$case: Refused(${refused.readText().trim()})") {
                val expected = FrontmatterPatcher.RefusalReason.valueOf(refused.readText().trim())
                val result = patcher.patch(inputOf(refused), id).shouldBeInstanceOf<FrontmatterPatcher.PatchResult.Refused>()
                result.reason shouldBe expected
                // Every refusal carries a non-empty rule-naming message (§A3 actionable-output requirement).
                result.message.isNotBlank() shouldBe true
            }
        }
    }
})
