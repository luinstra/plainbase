package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.grantForTests
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.providerOver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files

/**
 * W5 §5 test 7 / debate MUST-FIX 4 — inter-field coherence. After a save (CAS → history commit → targeted
 * reindex), the FINAL published [IndexedPage] pairs the NEW `contentHash` with the NEW `commit` (the
 * just-made SHA), never the prior commit and never null. No locking change is added or tested (D-1): the
 * guarantee is that the save's OWN reindex republishes a coherent snapshot — which it does because reindex
 * reads the last commit AFTER the history hook commits. Real git over a temp repo, never mocked.
 */
class IndexBuilderHistoryCoherenceTest : FunSpec({

    val sha = Regex("[0-9a-f]{40}([0-9a-f]{24})?$")
    val pagePath = "notes/page.md"

    test("after a save the published page snapshot's commit matches its content_hash epoch") {
        val root = Files.createTempDirectory("plainbase-coherence")
        val home = Files.createTempDirectory("plainbase-coherence-home")
        try {
            val file = root.resolve(pagePath)
            Files.createDirectories(file.parent)
            val v1 = "---\ntitle: Page\n---\n\n# Page\n\nversion one.\n"
            Files.writeString(file, v1)

            val exec = GitExecutor(workTree = root, home = home)
            val provider = providerOver(exec, root, home)
            val tree = TreePath.require(pagePath)
            provider.commit(tree, v1.toByteArray()) // the initial committed version
            val firstSha = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            IndexHarness(root, history = provider).use { h ->
                h.builder.rebuild()
                val before = h.builder.current.byId.values.single { it.path == tree }
                before.commit shouldBe firstSha // baseline: the page carries its first commit

                // Drive a real save: the pipeline's history hook is the SAME provider, so the save makes a
                // NEW commit, then its targeted reindex republishes the snapshot with the new commit.
                val pipeline = h.writePipeline(historyHook = { p, bytes, author, committer ->
                    provider.commit(p, bytes, author, committer)?.sha
                })
                val v2 = "---\ntitle: Page\n---\n\n# Page\n\nversion two.\n"
                val baseHash = h.builder.current.byId.getValue(before.id).contentHash
                val outcome = pipeline.write(grantForTests(), WriteIntent(before.id, tree, baseHash, v2.toByteArray()))
                outcome.shouldBeWritten()

                val newSha = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                val after = h.builder.current.byId.getValue(before.id)
                after.markdown shouldBe v2
                after.commit shouldMatch sha
                after.commit shouldBe newSha // the just-made commit, NOT firstSha and NOT null
                (after.commit == firstSha) shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }
})

private fun WriteOutcome.shouldBeWritten() {
    check(this is WriteOutcome.Written) { "expected a Written outcome, got $this" }
}
