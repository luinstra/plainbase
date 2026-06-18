package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-file-isolation (W4 §6 #2, P0-1): the per-operation temp index means a commit captures EXACTLY the
 * captured HEAD tree plus the ONE Plainbase path — never the repo's shared/dirty live index. A stray
 * staged entry (an external `git add`) or crash-residue in `.git/index` must not leak into our commit.
 */
class GitCommitIsolationTest : FunSpec({

    val page = TreePath.require("docs/page.md")

    test("a stray staged entry in the live index does not leak") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(page, "v1\n".toByteArray()) // born HEAD

            // An external actor stages a DIFFERENT file into the shared live index (never committed).
            Files.writeString(root.resolve("stray.txt"), "stray\n")
            exec.run(listOf("add", "stray.txt")).ok shouldBe true

            // A save of OUR page must commit only docs/page.md — the stray staged entry stays out.
            provider.commit(page, "v2\n".toByteArray())

            openOracle(root).use { repo ->
                repo.treePaths(repo.headCommits().first()) shouldBe listOf("docs/page.md")
            }
        }
    }

    test("crash-residue staged entry does not leak") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(page, "v1\n".toByteArray())

            // Simulate crash residue: a half-staged unrelated file left in the live index by a prior
            // interrupted operation (the live index is shared mutable state we deliberately never touch).
            Files.writeString(root.resolve("residue.bin"), "leftover\n")
            exec.run(listOf("add", "residue.bin")).ok shouldBe true

            provider.commit(page, "v3\n".toByteArray())
            openOracle(root).use { repo ->
                repo.treePaths(repo.headCommits().first()) shouldBe listOf("docs/page.md")
            }
        }
    }

    // Acceptance #11 (ref-CAS): when HEAD advances OUT-OF-BAND between the provider's oldHead capture and
    // its `update-ref`, the `update-ref <ref> <new> <oldHead>` old-value CAS must REJECT the stale update
    // — surfacing it via orThrow (GitCommandException) rather than silently clobbering the concurrent
    // commit. We open the race window deterministically: a spy over the executor advances the branch
    // out-of-band (via a SEPARATE real executor, so it is not intercepted) on the provider's first
    // `commit-tree` call — exactly the step right before `update-ref`, so the provider's captured oldHead
    // is now stale. (No production change; the same out-of-band-advance idea the F7 test uses.)
    test("an out-of-band HEAD advance between capture and update-ref fails the CAS and never clobbers the concurrent commit") {
        withGitRepoHome { root, exec, home ->
            // A born repo with an initial commit so HEAD exists and the CAS has a real old-value.
            providerOver(exec, root, home).commit(page, "initial\n".toByteArray())

            // A separate, real executor for the out-of-band actor — never routed through the spy.
            val external = GitExecutor(workTree = root, home = home)
            val advanced = AtomicBoolean(false)
            lateinit var outOfBandSha: String

            val spy = spyk(GitExecutor(workTree = root, home = home))
            every { spy.run(any(), any(), any()) } answers {
                val args = firstArg<List<String>>()
                // The instant the provider runs commit-tree (step 6) — right before its update-ref (step 7)
                // — land an unrelated commit on the SAME branch so the provider's captured oldHead is stale.
                if (args.firstOrNull() == "commit-tree" && advanced.compareAndSet(false, true)) {
                    Files.writeString(root.resolve("external.md"), "out of band\n")
                    external.run(listOf("add", "external.md")).ok shouldBe true
                    external.run(
                        listOf("commit", "-m", "external"),
                        env = mapOf(
                            "GIT_AUTHOR_NAME" to "Ext",
                            "GIT_AUTHOR_EMAIL" to "ext@example.com",
                            "GIT_AUTHOR_DATE" to "@1780000000 +0000",
                            "GIT_COMMITTER_NAME" to "Ext",
                            "GIT_COMMITTER_EMAIL" to "ext@example.com",
                            "GIT_COMMITTER_DATE" to "@1780000000 +0000",
                        ),
                    ).ok shouldBe true
                    outOfBandSha = external.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                }
                callOriginal()
            }

            val provider = providerOver(spy, root, home)
            // The provider's update-ref CAS (old-value = the now-stale pre-advance HEAD) must reject.
            shouldThrow<GitCommandException> { provider.commit(page, "ours\n".toByteArray()) }

            openOracle(root).use { repo ->
                // The branch tip is STILL the out-of-band commit — never clobbered by our rejected update.
                repo.resolve("HEAD").name shouldBe outOfBandSha
                // And nothing torn: the external file is the only working-tree change committed at the tip.
                repo.treePaths(repo.headCommits().first()).contains("external.md") shouldBe true
            }
        }
    }

    // P2-1: the target branch ref is captured ATOMICALLY with oldHead (step 2), not re-read at update-ref.
    // If the user `git checkout`s a DIFFERENT branch (pointing at the same commit) mid-commit, we must
    // advance the branch the save STARTED on — never the switched-to branch. We open the window via the spy:
    // on the provider's commit-tree, point HEAD at `other` (same tip as `main`). The fix advances `main`
    // (the captured ref); the old re-read code would have advanced `other`.
    test("a branch switch mid-commit advances the branch the save started on, not the switched-to branch") {
        withGitRepoHome { root, exec, home ->
            providerOver(exec, root, home).commit(page, "initial\n".toByteArray()) // born `main`
            // A second branch pointing at the SAME commit as main.
            exec.run(listOf("branch", "other")).ok shouldBe true
            val startTip = exec.run(listOf("rev-parse", "main")).stdoutText.trim()

            val switched = AtomicBoolean(false)
            val spy = spyk(GitExecutor(workTree = root, home = home))
            every { spy.run(any(), any(), any()) } answers {
                val args = firstArg<List<String>>()
                // At commit-tree (right before update-ref), switch HEAD to `other` (same tip) out-of-band.
                if (args.firstOrNull() == "commit-tree" && switched.compareAndSet(false, true)) {
                    exec.run(listOf("symbolic-ref", "HEAD", "refs/heads/other")).ok shouldBe true
                }
                callOriginal()
            }

            val provider = providerOver(spy, root, home)
            val ours = provider.commit(page, "v2\n".toByteArray()) // started on `main`

            // `main` (the captured ref) advanced to our commit; `other` (the switched-to ref) is UNTOUCHED.
            exec.run(listOf("rev-parse", "main")).stdoutText.trim() shouldBe ours.sha
            exec.run(listOf("rev-parse", "other")).stdoutText.trim() shouldBe startTip
        }
    }

    // P2-3: the live-index sync is SURGICAL — it stages only the just-committed path, so a user's unrelated
    // staged work survives the commit (round-1's `read-tree HEAD` would have wiped it). The saved path stays
    // coherent (index entry == HEAD).
    test("an unrelated staged change survives a commit; the saved path is coherent in the live index") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(page, "v1\n".toByteArray()) // born HEAD

            // The user stages an unrelated NEW file into the live index (real `git add`).
            Files.writeString(root.resolve("unrelated.txt"), "user staged work\n")
            exec.run(listOf("add", "unrelated.txt")).ok shouldBe true

            // A Plainbase save commits docs/page.md — the unrelated staged entry must NOT be wiped.
            provider.commit(page, "v2\n".toByteArray())

            // The unrelated file is STILL staged (appears as an added entry in the live index, A status).
            val status = exec.run(listOf("status", "--porcelain")).stdoutText
            status.lines().any { it.trim() == "A  unrelated.txt" } shouldBe true
            // The saved path is coherent: its live-index entry matches HEAD (no phantom deletion).
            exec.run(listOf("diff-index", "--cached", "HEAD", "--", "docs/page.md")).stdoutText.trim() shouldBe ""
        }
    }

    // P2 (sync branch-guard): if the user `git checkout`s another branch after branchRef capture, the live
    // index now belongs to the switched-to branch — staging our blob onto it would leak the save onto the
    // wrong branch. syncLiveIndex re-reads HEAD and SKIPS when it no longer matches the captured branch. We
    // switch HEAD to `other` at commit-tree (out-of-band, before update-ref); `main` still advances (captured
    // ref), but the live index (now `other`'s) must NOT be touched with the new blob.
    test("a branch switch mid-commit skips the live-index sync (never stages onto the switched-to branch)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(page, "v1\n".toByteArray()) // born `main`; live index now has docs/page.md @ v1
            val v1IndexLine = exec.run(listOf("ls-files", "--stage", "docs/page.md")).stdoutText.trim()
            exec.run(listOf("branch", "other")).ok shouldBe true

            val switched = AtomicBoolean(false)
            val spy = spyk(GitExecutor(workTree = root, home = home))
            every { spy.run(any(), any(), any()) } answers {
                val args = firstArg<List<String>>()
                if (args.firstOrNull() == "commit-tree" && switched.compareAndSet(false, true)) {
                    exec.run(listOf("symbolic-ref", "HEAD", "refs/heads/other")).ok shouldBe true
                }
                callOriginal()
            }

            providerOver(spy, root, home).commit(page, "v2\n".toByteArray()) // captured branch = main

            // The live index (now `other`'s) was NOT restaged with the v2 blob — it still holds the v1 entry.
            exec.run(listOf("ls-files", "--stage", "docs/page.md")).stdoutText.trim() shouldBe v1IndexLine
        }
    }

    // P1 (round-5): the seed read-tree + idempotency compare are pinned to the CAPTURED oldHead, never live
    // HEAD. If the user `git checkout`s a branch with DIFFERENT files between capture and the seed, a
    // `read-tree HEAD` would import THAT branch's tree and commit it onto the captured branch (history
    // corruption). We switch HEAD to `other` (which has an unrelated file) at the seed call; the resulting
    // commit's tree must contain ONLY the captured branch's files + the saved path — never `other`'s file.
    test("a branch switch before the seed read-tree builds the tree from the captured base, not the switched-to branch") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(page, "main content\n".toByteArray()) // born `main`, tree = { docs/page.md }
            val mainTip = exec.run(listOf("rev-parse", "main")).stdoutText.trim()

            // Build `other` with a DIFFERENT file set (an unrelated file that must NOT leak into our commit).
            exec.run(listOf("checkout", "-b", "other")).ok shouldBe true
            provider.commit(TreePath.require("other-only.txt"), "only on other\n".toByteArray())
            exec.run(listOf("checkout", "main")).ok shouldBe true

            // Spy: switch HEAD to `other` the instant the provider seeds the temp index (read-tree), AFTER it
            // captured oldHead=main. With the fix the seed reads oldHead (main's tree); a live-HEAD read-tree
            // would pull in other-only.txt.
            val switched = AtomicBoolean(false)
            val spy = spyk(GitExecutor(workTree = root, home = home))
            every { spy.run(any(), any(), any()) } answers {
                val args = firstArg<List<String>>()
                if (args.firstOrNull() == "read-tree" && switched.compareAndSet(false, true)) {
                    exec.run(listOf("symbolic-ref", "HEAD", "refs/heads/other")).ok shouldBe true
                }
                callOriginal()
            }

            val ours = providerOver(spy, root, home).commit(page, "v2 content\n".toByteArray()) // captured base = mainTip

            // HEAD now points at `other`; our commit advanced `main`, so resolve it by its own SHA.
            openOracle(root).use { repo ->
                org.eclipse.jgit.revwalk.RevWalk(repo).use { walk ->
                    val committed = walk.parseCommit(repo.resolve(ours.sha))
                    // The tree is the captured base's files + the saved path — `other-only.txt` never leaked in.
                    repo.treePaths(committed).toSet() shouldBe setOf("docs/page.md")
                    // And the parent is the captured base (main's tip), not whatever HEAD became.
                    committed.getParent(0).name shouldBe mainTip
                }
            }
        }
    }

    // r6c: a save that STARTED detached must advance the detached HEAD itself even if an external checkout
    // re-attaches HEAD to a branch (same commit) before update-ref. Without --no-deref, `update-ref HEAD`
    // dereferences HEAD and would advance that branch. We attach HEAD to `main` at commit-tree via the spy;
    // the fix advances the detached HEAD (now the same as wherever HEAD points), but `main` must NOT move.
    test("a detached save that gets re-attached mid-commit advances HEAD via --no-deref, not the branch") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(page, "v1\n".toByteArray()) // born `main`
            val mainTip = exec.run(listOf("rev-parse", "main")).stdoutText.trim()
            exec.run(listOf("checkout", "--detach")).ok shouldBe true // the save starts DETACHED

            val attached = AtomicBoolean(false)
            val spy = spyk(GitExecutor(workTree = root, home = home))
            every { spy.run(any(), any(), any()) } answers {
                val args = firstArg<List<String>>()
                // Re-attach HEAD to `main` (same tip) right before update-ref — the re-attach race.
                if (args.firstOrNull() == "commit-tree" && attached.compareAndSet(false, true)) {
                    exec.run(listOf("symbolic-ref", "HEAD", "refs/heads/main")).ok shouldBe true
                }
                callOriginal()
            }

            val ours = providerOver(spy, root, home).commit(page, "v2\n".toByteArray()) // captured: detached

            // `main` did NOT move — the detached HEAD was advanced via --no-deref, not the re-attached branch.
            exec.run(listOf("rev-parse", "main")).stdoutText.trim() shouldBe mainTip
            exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe ours.sha
        }
    }

    // r6d: the live-index sync must verify HEAD still names the commit we made. If another actor advances the
    // SAME branch after our update-ref but before the sync, the branch-name guard still matches — so without
    // the HEAD-identity guard we'd stage our (now-older) blob against a NEWER HEAD, writing a stale revert.
    // We advance `main` out-of-band right after the provider's update-ref (intercepted via the spy); the sync
    // must then SKIP (HEAD != our commit), leaving the live index untouched by us.
    test("a same-branch advance after update-ref skips the live-index sync (no stale revert staged)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(page, "v1\n".toByteArray()) // born `main`; live index = docs/page.md @ v1
            val v1IndexLine = exec.run(listOf("ls-files", "--stage", "docs/page.md")).stdoutText.trim()

            val advanced = AtomicBoolean(false)
            val external = GitExecutor(workTree = root, home = home)
            val spy = spyk(GitExecutor(workTree = root, home = home))
            every { spy.run(any(), any(), any()) } answers {
                val args = firstArg<List<String>>()
                val result = callOriginal() as GitResult
                // Immediately AFTER our update-ref lands, an external actor advances `main` past our commit.
                if (args.firstOrNull() == "update-ref" && advanced.compareAndSet(false, true)) {
                    Files.writeString(root.resolve("ext.txt"), "external\n")
                    external.run(listOf("add", "ext.txt")).ok shouldBe true
                    external.run(
                        listOf("commit", "-m", "external advance"),
                        env = mapOf(
                            "GIT_AUTHOR_NAME" to "Ext",
                            "GIT_AUTHOR_EMAIL" to "ext@example.com",
                            "GIT_AUTHOR_DATE" to "@1780000000 +0000",
                            "GIT_COMMITTER_NAME" to "Ext",
                            "GIT_COMMITTER_EMAIL" to "ext@example.com",
                            "GIT_COMMITTER_DATE" to "@1780000000 +0000",
                        ),
                    ).ok shouldBe true
                }
                result
            }

            providerOver(spy, root, home).commit(page, "v2\n".toByteArray()) // our commit lands, then HEAD moves past it

            // The sync was skipped (HEAD no longer names our commit), so the live index still holds the v1
            // entry — we never staged our now-older v2 blob over a newer HEAD.
            exec.run(listOf("ls-files", "--stage", "docs/page.md")).stdoutText.trim() shouldBe v1IndexLine
        }
    }

    // P2-3: the idempotent no-op branch must honor the same HEAD-stability invariant as the commit path. The
    // tree compare is against the STALE captured oldHead; if an external actor advances HEAD after the capture
    // (committing a DIFFERENT version of this path), a bare "no-op success" would let W1 clear the dirty row
    // and silently DROP the user's save relative to the new tip. The provider must RAISE instead, so the page
    // stays dirty and reconcile retries against the fresh tip. We advance HEAD out-of-band at the no-op
    // branch's write-tree (after the oldHead capture) via the spy; the save's bytes equal oldHead's tree.
    test("the no-op branch raises (not silent no-op) when HEAD advanced out-of-band after capture (P2-3)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val sameBytes = "stable bytes\n".toByteArray()
            provider.commit(page, sameBytes) // born `main`; this is the captured base for the re-commit below

            // An external actor advances `main` with a DIFFERENT version of the SAME path the instant the
            // re-commit reaches write-tree (after it captured oldHead = the first commit).
            val advanced = AtomicBoolean(false)
            val external = GitExecutor(workTree = root, home = home)
            val spy = spyk(GitExecutor(workTree = root, home = home))
            every { spy.run(any(), any(), any()) } answers {
                val args = firstArg<List<String>>()
                val result = callOriginal() as GitResult
                if (args.firstOrNull() == "write-tree" && advanced.compareAndSet(false, true)) {
                    Files.createDirectories(root.resolve(page.value).parent)
                    Files.writeString(root.resolve(page.value), "EXTERNAL different version\n")
                    external.run(listOf("add", page.value)).ok shouldBe true
                    external.run(
                        listOf("commit", "-m", "external different version"),
                        env = mapOf(
                            "GIT_AUTHOR_NAME" to "Ext",
                            "GIT_AUTHOR_EMAIL" to "ext@example.com",
                            "GIT_AUTHOR_DATE" to "@1780000000 +0000",
                            "GIT_COMMITTER_NAME" to "Ext",
                            "GIT_COMMITTER_EMAIL" to "ext@example.com",
                            "GIT_COMMITTER_DATE" to "@1780000000 +0000",
                        ),
                    ).ok shouldBe true
                }
                result
            }
            val originalTip = exec.run(listOf("rev-parse", "main")).stdoutText.trim() // the captured base (first commit)

            // Re-committing the SAME bytes is a no-op vs the captured oldHead — but HEAD moved, so the provider
            // must NOT silently no-op; it raises (W1 leaves the page dirty for a fresh-capture reconcile).
            shouldThrow<GitCommandException> { providerOver(spy, root, home).commit(page, sameBytes) }

            // HEAD advanced to the external commit (a real second commit), and our no-op neither overwrote it
            // nor fabricated a clean success: exactly the external commit sits on top of the original base.
            val tipAfter = exec.run(listOf("rev-parse", "main")).stdoutText.trim()
            (tipAfter == originalTip) shouldBe false
            exec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim() shouldBe "2"
            exec.run(listOf("rev-parse", "HEAD~1")).stdoutText.trim() shouldBe originalTip
        }
    }
})
