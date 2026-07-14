package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * **A MOVE is not an ABSENCE.**
 *
 * An absence proof covers a `BindingRef(path, id)` whose path a complete scan did not see - and a RENAMED page's OLD
 * path is exactly that. So every proof source will happily mint a proof over a page that is sitting right there under
 * a different name, and the apply transaction will tombstone its id.
 *
 * The rule the design already states, in the one place it is enforced ([com.plainbase.domain.root.BindingLatch]):
 * *a witness is positive evidence of PRESENCE, and proving a page we are looking at to be absent is not a proof, it
 * is a contradiction.* The catch is that the enforcement is keyed by PATH, and a moved page's evidence arrives under
 * a different one. Identity is the only key that survives a rename.
 */
class OnlineMoveIdentityTest : FunSpec({

    val extra = RootName.require("extra")
    val from = TreePath.require("notes/rollback.md")
    val to = TreePath.require("notes/archive/rollback.md")

    fun identified(id: PageId) = "---\nid: ${id.value}\ntitle: Rollback\n---\n\n# Rollback\n\nbody\n"

    test("an ONLINE MOVE under an unbroken epoch PRESERVES the page's id - the id travels with its frontmatter") {
        withAbsenceTrees { mainDir, extraDir ->
            val pinned = requireNotNull(PageId.of("018f4c1e-8b7a-7c3d-9e2f-1a2b3c4d5e6f"))
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            // MATERIALIZED - the id is IN THE FILE, which is the only thing that can carry it across a rename.
            writePage(extraDir, "notes/rollback.md", identified(pinned))

            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("main", "extra")
                val builder = world.builder(mainDir, world.extraStore(extraDir), world.indexer)

                // The OPENING scan: the epoch WITNESSES the page at its original path.
                builder.rebuild().byPath.getValue(RootedPath(extra, from)).id shouldBe pinned

                // The operator renames it, under a running server that has been watching this tree the whole time.
                Files.createDirectories(extraDir.resolve("notes/archive"))
                Files.move(extraDir.resolve("notes/rollback.md"), extraDir.resolve("notes/archive/rollback.md"))

                // The CONFIRMATION scan. The old path is gone - and the file at the NEW path carries the id, in plain
                // sight, in its own frontmatter.
                val moved = builder.rebuild()

                withClue("the page keeps its permalink: we are LOOKING at it, and it says which page it is") {
                    moved.byPath.getValue(RootedPath(extra, to)).id shouldBe pinned
                }
                withClue("and it is NOT tombstoned - /p/{id} must not answer 410 because somebody renamed a file") {
                    world.idMap.retired(pinned).shouldBeNull()
                }
            }
        }
    }
})
