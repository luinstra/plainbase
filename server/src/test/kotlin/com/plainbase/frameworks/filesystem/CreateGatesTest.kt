package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * The C3 read-only create-gate seam (theme 6): the gates themselves never mutate the filesystem, so
 * a create they REJECT provably leaves zero side effects — in particular, no freshly-minted parent
 * directory. [LocalContentStore] recomposes the resolve-only walk with its own directory creation,
 * which runs only AFTER every gate passed.
 */
class CreateGatesTest : FunSpec({

    val hasher = { bytes: ByteArray -> bytes.size.toString() }

    fun <T> withRoot(block: (Path) -> T): T {
        val root = Files.createTempDirectory("pb-create-gates")
        return try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a rejected create with missing parents leaves no directories behind (the gates never mutate)") {
        withRoot { root ->
            val store = LocalContentStore(root).also { it.scan() }
            // The LEAF is a dotfile (a scan-skipped name) while the parents are genuinely missing: a
            // mutating walk would mint `newdir/sub/` before the leaf gate fired; the read-only seam
            // rejects first, so nothing may exist afterwards.
            store.createExclusive(TreePath.require("newdir/sub/.secret.md"), "# nope\n".toByteArray(), hasher)
                .shouldBeInstanceOf<CreateResult.Rejected>()
            Files.exists(root.resolve("newdir")) shouldBe false
        }
    }

    test("resolveParent reports the existing prefix + missing tail without creating anything") {
        withRoot { root ->
            Files.createDirectory(root.resolve("a"))
            val resolution = CreateGates(root, IgnoreRules(), emptyList()).resolveParent(TreePath.require("a/b/c/page.md"))
            resolution.existingPrefix shouldBe root.resolve("a")
            resolution.missing shouldBe listOf("b", "c")
            resolution.occupiedByFile shouldBe false
            Files.exists(root.resolve("a/b")) shouldBe false // resolve-only: nothing minted
        }
    }

    test("a parent segment occupied by a FILE reports occupiedByFile; end-to-end the create refuses with nothing written") {
        withRoot { root ->
            Files.writeString(root.resolve("a"), "a file, not a dir")
            CreateGates(root, IgnoreRules(), emptyList()).resolveParent(TreePath.require("a/page.md"))
                .occupiedByFile shouldBe true
            // End-to-end the SAME-byte case is caught by the ancestor-is-a-file containment gate (a
            // permanent client error, deliberately not Exists); the occupiedByFile arm covers the
            // scan-invisible NFC-equivalent byte-form, which a normalization-insensitive filesystem
            // (macOS/APFS) cannot even mint — so the portable pin is: the gates ran READ-ONLY and the
            // occupying file is untouched.
            val store = LocalContentStore(root).also { it.scan() }
            store.createExclusive(TreePath.require("a/page.md"), "# body\n".toByteArray(), hasher)
                .shouldBeInstanceOf<CreateResult.Rejected>()
            Files.readString(root.resolve("a")) shouldBe "a file, not a dir" // untouched
        }
    }
})
