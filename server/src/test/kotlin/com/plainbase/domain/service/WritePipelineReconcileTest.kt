package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.repository.Stage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * PB-WRITE-1 named tests 10 (end-to-end half) + 11 (fix H, debate MUST-FIX 5): the write-ahead
 * dirty journal and startup reconciliation.
 *
 * (a) a post-write step that throws (after the CAS succeeds) yields [WriteOutcome.WrittenButUnindexed],
 *     the bytes ARE on disk, and the page is left dirty WITH the new bytes' hash + stage (proving the
 *     mark was written BEFORE the disk write, not after).
 * (b) a crash between mark and write (bytes never landed) leaves an on-disk hash ≠ expectedHash, so
 *     reconcile DRIFT-SKIPS, leaving the mark.
 * (c) a fresh pipeline over the same content+DB with a non-throwing hook reconciles the matching-hash
 *     page (reindexed + cleared) and leaves the drifted page marked.
 */
class WritePipelineReconcileTest : FunSpec({

    val citations = CitationFactory()

    fun seedOne(root: Path) {
        writePage(root, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n\noriginal.\n")
    }

    // 11(a) + 10 end-to-end: a throwing post-write step ⇒ WrittenButUnindexed, bytes on disk, left dirty.
    test("a post-write failure yields WrittenButUnindexed, with the bytes on disk and a write-ahead mark") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = harness.builder.current.pages.single()
                val saveBytes = "---\ntitle: Doc\n---\n\n# Doc\n\nsaved but unindexed.\n".toByteArray()
                // A history hook that throws AFTER the CAS write succeeds (a post-write step failure).
                val pipeline = harness.writePipeline(historyHook = { _, _ -> error("commit blew up") })

                val outcome = pipeline.write(WriteIntent(page.id, page.path, page.contentHash, saveBytes))

                val unindexed = outcome.shouldBeInstanceOf<WriteOutcome.WrittenButUnindexed>()
                unindexed.newHash shouldBe citations.contentHash(saveBytes)
                Files.readAllBytes(root.resolve("doc.md")) shouldBe saveBytes // bytes ARE durably on disk
                val dirty = harness.dirtyPages.all().single()
                dirty.pageId shouldBe page.id
                dirty.expectedHash shouldBe citations.contentHash(saveBytes) // the NEW hash — written before the disk write
                dirty.stage shouldBe Stage.WRITING
            }
        }
    }

    // FIX 2 (dirty-row clobber): a no-write retry after a prior WrittenButUnindexed must RESTORE the
    // prior recovery record, never poison it with this attempt's hash nor clear it. Otherwise the
    // earlier on-disk-but-unindexed bytes are drift-skipped/lost by reconcile forever.
    test("a no-write conflict after a prior WrittenButUnindexed restores the prior dirty row intact") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = harness.builder.current.pages.single()

                // Attempt 1: bytes B land on disk, but a post-write step throws ⇒ WrittenButUnindexed,
                // leaving a dirty row whose expectedHash = hash(B).
                val bytesB = "---\ntitle: Doc\n---\n\n# Doc\n\nbytes B on disk, unindexed.\n".toByteArray()
                harness.writePipeline(historyHook = { _, _ -> error("commit blew up") })
                    .write(WriteIntent(page.id, page.path, page.contentHash, bytesB))
                    .shouldBeInstanceOf<WriteOutcome.WrittenButUnindexed>()
                val hashB = citations.contentHash(bytesB)
                harness.dirtyPages.all().single().expectedHash shouldBe hashB

                // Attempt 2: a retry with a STALE baseHash ⇒ CAS Mismatch (NOTHING written). The prior
                // row must survive: same expectedHash = hash(B), not this attempt's hash, not cleared.
                val bytesC = "---\ntitle: Doc\n---\n\n# Doc\n\nbytes C never written.\n".toByteArray()
                harness.writePipeline()
                    .write(WriteIntent(page.id, page.path, "sha256:stale-base", bytesC))
                    .shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe "content_changed"
                val afterConflict = harness.dirtyPages.all().single()
                afterConflict.expectedHash shouldBe hashB // not poisoned to hash(C), not cleared
                afterConflict.pageId shouldBe page.id

                // reconcile still recovers B: on-disk hash matches the recorded hash(B) ⇒ reindexed + cleared.
                harness.writePipeline().reconcileDirtyPages()
                harness.dirtyPages.all().isEmpty() shouldBe true
                harness.builder.current.byId.getValue(page.id).contentHash shouldBe hashB
            }
        }
    }

    // 11(b): a mark with no write ⇒ on-disk hash ≠ expectedHash ⇒ reconcile drift-skips, leaving the mark.
    test("reconcile drift-skips a marked page whose on-disk bytes never matched the recorded hash") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = harness.builder.current.pages.single()
                // Simulate a crash between mark and write: mark with a hash the on-disk bytes do NOT have.
                harness.dirtyPages.mark(page.id, page.path, expectedHash = "sha256:neverwritten", stage = Stage.WRITING)

                harness.writePipeline().reconcileDirtyPages()

                // Drift-skip: the mark is LEFT (the on-disk old bytes do not match the recorded hash).
                harness.dirtyPages.all().single().pageId shouldBe page.id
            }
        }
    }

    // 11(c): a matching-hash mark reconciles (reindex + clear); a drifted mark is left.
    test("reconcile reindexes a matching-hash page and clears it, leaving a drifted page marked") {
        withTempTree({ root ->
            writePage(root, "match.md", "---\ntitle: Match\n---\n\n# Match\n\nbody.\n")
            writePage(root, "drift.md", "---\ntitle: Drift\n---\n\n# Drift\n\nbody.\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val match = harness.builder.current.byPath.getValue(TreePath.require("match.md"))
                val drift = harness.builder.current.byPath.getValue(TreePath.require("drift.md"))

                // match.md: the on-disk bytes match the recorded hash (a write that completed but did not clear).
                val matchOnDisk = Files.readAllBytes(root.resolve("match.md"))
                harness.dirtyPages.mark(match.id, match.path, expectedHash = citations.contentHash(matchOnDisk), stage = Stage.WRITING)
                // drift.md: the recorded hash is for bytes that never landed.
                harness.dirtyPages.mark(drift.id, drift.path, expectedHash = "sha256:neverwritten", stage = Stage.WRITING)

                harness.writePipeline().reconcileDirtyPages()

                val remaining = harness.dirtyPages.all()
                remaining.map { it.pageId }.toSet() shouldBe setOf(drift.id) // match cleared, drift left
                harness.builder.current.byId.getValue(match.id).contentHash shouldBe citations.contentHash(matchOnDisk)
            }
        }
    }
})
