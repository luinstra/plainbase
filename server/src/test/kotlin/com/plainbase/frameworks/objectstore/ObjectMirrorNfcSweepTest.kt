package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * B-C2: an NFC-equivalent-sibling SWEEP FAILURE must fail the whole mirror apply - never `recordConfirmed`
 * over a stale sibling that could still win a `LocalContentStore` scan/read (a lost-update / stale-bytes
 * path). A confirmed apply removes the stale sibling; a FAILED sweep leaves state unrecorded and retries
 * on the next poll (rolling back the just-added raw file so `replacingInPlace` cannot skip the retry sweep).
 *
 * Requires a normalization-PRESERVING filesystem (Linux CI) - on a folding FS (macOS/Windows) the NFC and
 * NFD raw names are the SAME file, so there is no separate sibling to sweep and the sweep is a no-op
 * replace-in-place. Each case skips there.
 */
class ObjectMirrorNfcSweepTest : FunSpec({

    val nfc = "caf\u00E9.md" // precomposed U+00E9 (NFC)
    val nfd = "cafe\u0301.md" // e + combining acute U+0301 - same TreePath, a DIFFERENT raw name (NFD)

    fun preservingFs(): Boolean {
        val dir = Files.createTempDirectory("pb-nfc-probe")
        return try {
            Files.write(dir.resolve(nfd), ByteArray(0))
            Files.notExists(dir.resolve(nfc)) // preserving iff the NFC name is a distinct (absent) file
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("a confirmed poll apply sweeps the stale NFC-equivalent sibling; no stale sibling wins a later read") {
        if (!preservingFs()) return@test
        HybridFixture().use { hybrid ->
            val path = TreePath.require(nfc)
            // A stale prior-generation raw file under the NFD name sits in the mirror; the bucket now holds
            // the NFC raw key with fresh bytes (an NFC-equivalent re-upload).
            Files.write(hybrid.mirrorRoot.resolve(nfd), "stale".toByteArray())
            hybrid.fake.seed(nfc, "fresh".toByteArray())

            hybrid.store.pollOnce()

            hybrid.state.etagOf(path).shouldNotBeNull() // confirmed apply
            Files.notExists(hybrid.mirrorRoot.resolve(nfd)) shouldBe true // stale sibling swept away
            hybrid.mirror.read(path)?.toString(Charsets.UTF_8) shouldBe "fresh" // no stale sibling wins
        }
    }

    test("a REPLACE-IN-PLACE apply still sweeps the stale sibling (O2: no `replacingInPlace` skip)") {
        if (!preservingFs()) return@test
        HybridFixture().use { hybrid ->
            val path = TreePath.require(nfc)
            // The exact NFC raw target ALREADY exists on disk (a replace-in-place write, not a fresh ADD), AND a
            // stale NFD sibling sits beside it. The old `replacingInPlace` skip would SKIP the sweep here and
            // recordConfirmed over the stale sibling; always-sweep must remove it even on a replace-in-place.
            Files.write(hybrid.mirrorRoot.resolve(nfc), "old".toByteArray())
            Files.write(hybrid.mirrorRoot.resolve(nfd), "stale".toByteArray())
            hybrid.fake.seed(nfc, "fresh".toByteArray())

            hybrid.store.pollOnce()

            hybrid.state.etagOf(path).shouldNotBeNull() // confirmed apply (replace in place)
            Files.notExists(hybrid.mirrorRoot.resolve(nfd)) shouldBe true // stale sibling swept even on replace-in-place
            hybrid.mirror.read(path)?.toString(Charsets.UTF_8) shouldBe "fresh" // no stale sibling wins
        }
    }

    test("a sweep FAILURE leaves state unrecorded and rolls back, then a later poll re-adds + re-sweeps") {
        if (!preservingFs()) return@test
        HybridFixture().use { hybrid ->
            val path = TreePath.require(nfc)
            // A broken symlink NAMED as the NFD sibling makes the sweep's Files.isSameFile throw - a portable
            // sweep-failure injection (the sweep uses raw Files ops, not the FailableFileAtomics seam).
            Files.createSymbolicLink(hybrid.mirrorRoot.resolve(nfd), hybrid.mirrorRoot.resolve("no-such-target"))
            hybrid.fake.seed(nfc, "fresh".toByteArray())

            hybrid.store.pollOnce()

            hybrid.state.etagOf(path).shouldBeNull() // sweep failed -> NEVER recordConfirmed
            Files.notExists(hybrid.mirrorRoot.resolve(nfc)) shouldBe true // the just-added raw target was rolled back

            // Clear the failure condition; a REAL (deletable) stale sibling now sits where the broken link was.
            Files.deleteIfExists(hybrid.mirrorRoot.resolve(nfd))
            Files.write(hybrid.mirrorRoot.resolve(nfd), "stale".toByteArray())

            hybrid.store.pollOnce() // re-adds AND re-sweeps (replacingInPlace stayed false thanks to the rollback)

            hybrid.state.etagOf(path).shouldNotBeNull() // retried -> confirmed
            Files.notExists(hybrid.mirrorRoot.resolve(nfd)) shouldBe true // swept on the retry
            hybrid.mirror.read(path)?.toString(Charsets.UTF_8) shouldBe "fresh" // no stale sibling wins
        }
    }
})
