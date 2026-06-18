package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * [GitCliHistoryProvider] behavior over a real `git` (W4 §6): the author/committer split (#1), N saves →
 * N commits incl. the unborn first commit (#2, F2), the failing-pre-commit-hook skip (#7), the F7
 * SHA-from-commit-tree guarantee, the F6 not-silently-empty read ops, and idempotent recovery re-commits.
 * Commits are read back through the JGit oracle.
 */
class GitCliHistoryProviderTest : FunSpec({

    val path = TreePath.require("docs/page.md")

    test("commit records configured author and committer (JGit oracle)") {
        withGitRepoHome { root, exec, home ->
            val author = CommitIdentity("Alice Author", "alice@example.com")
            val committer = CommitIdentity("Bob Committer", "bob@example.com")
            val provider = providerOver(exec, root, home, author = author, committer = committer)

            val commit = provider.commit(path, "hello\n".toByteArray())

            openOracle(root).use { repo ->
                val head = repo.headCommits().single()
                head.name shouldBe commit.sha
                head.authorIdent.name shouldBe "Alice Author"
                head.authorIdent.emailAddress shouldBe "alice@example.com"
                head.committerIdent.name shouldBe "Bob Committer"
                head.committerIdent.emailAddress shouldBe "bob@example.com"
            }
        }
    }

    test("first commit in a fresh unborn repo commits exactly one file (no 'index file smaller than expected')") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "first\n".toByteArray())

            openOracle(root).use { repo ->
                val head = repo.headCommits().single()
                repo.treePaths(head) shouldBe listOf("docs/page.md")
                head.parentCount shouldBe 0 // root commit — no -p
            }
        }
    }

    test("N consecutive saves produce N commits") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            repeat(5) { n -> provider.commit(path, "version $n\n".toByteArray()) }

            openOracle(root).use { repo ->
                repo.headCommits().size shouldBe 5
                repo.treePaths(repo.headCommits().first()) shouldBe listOf("docs/page.md")
            }
        }
    }

    test("a re-commit of unchanged bytes is a no-op (idempotent recovery)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val bytes = "same\n".toByteArray()
            val first = provider.commit(path, bytes)
            val second = provider.commit(path, bytes) // recovery re-commit of identical bytes

            second.sha shouldBe first.sha
            openOracle(root).use { repo -> repo.headCommits().size shouldBe 1 }
        }
    }

    test("commit succeeds despite a failing pre-commit hook") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "seed\n".toByteArray()) // init the repo + .git/hooks

            // A pre-commit hook that always fails — the plumbing recipe runs no hooks, and core.hooksPath
            // is pinned to /dev/null besides, so the commit still lands.
            val hook = root.resolve(".git/hooks/pre-commit")
            Files.createDirectories(hook.parent)
            Files.writeString(hook, "#!/bin/sh\nexit 1\n")
            hook.toFile().setExecutable(true)

            provider.commit(path, "after hook\n".toByteArray())
            openOracle(root).use { repo -> repo.headCommits().size shouldBe 2 }
        }
    }

    test("the returned Commit.sha is commit-tree's own SHA, never a post-update-ref rev-parse HEAD (F7)") {
        withGitRepoHome { root, exec, home ->
            // A maintenance dispatcher that, AFTER update-ref, advances HEAD out-of-band with an unrelated
            // commit — modelling a concurrent external committer. The returned SHA must still be OUR commit,
            // not the racing HEAD a post-update-ref rev-parse would observe. A UNIQUE file per invocation
            // guarantees a real delta to commit (the post-commit live-index sync now leaves index==HEAD).
            val externalSeq = java.util.concurrent.atomic.AtomicInteger(0)
            val provider = GitCliHistoryProvider(
                exec = exec,
                workTree = root,
                gitHome = home,
                defaultAuthor = testIdentity(),
                defaultCommitter = testIdentity(),
                clock = fixedClock(),
                maintenance = {
                    // Stage + commit an UNRELATED change directly via the live index (the "external" actor).
                    val name = "external-${externalSeq.incrementAndGet()}.txt"
                    Files.writeString(root.resolve(name), "out of band\n")
                    exec.run(listOf("add", name)).ok shouldBe true
                    exec.run(
                        listOf("commit", "-m", "external"),
                        env = mapOf(
                            "GIT_AUTHOR_NAME" to "Ext",
                            "GIT_AUTHOR_EMAIL" to "ext@x",
                            "GIT_AUTHOR_DATE" to "@1780000000 +0000",
                            "GIT_COMMITTER_NAME" to "Ext",
                            "GIT_COMMITTER_EMAIL" to "ext@x",
                            "GIT_COMMITTER_DATE" to "@1780000000 +0000",
                        ),
                    ).ok shouldBe true
                },
            )
            provider.commit(path, "seed\n".toByteArray()) // born HEAD
            val ours = provider.commit(path, "ours\n".toByteArray())

            openOracle(root).use { repo ->
                // HEAD is now the external commit; our returned SHA is the one we created, a parent of HEAD.
                val headSha = repo.resolve("HEAD").name
                headSha shouldContain "" // sanity: resolvable
                (headSha == ours.sha) shouldBe false
                repo.headCommits().any { it.name == ours.sha } shouldBe true
            }
        }
    }

    // P1-A: the staging runs under a per-op temp index, so the LIVE .git/index must be synced to HEAD
    // after the commit — otherwise an external `git status` shows phantom staged deletions and a bare
    // `git commit` could commit an empty/destructive tree. The working tree must also write the bytes so
    // status is fully clean (this test mirrors the ContentStore write the W1 pipeline does).
    test("after a commit on a fresh repo the live index == HEAD and git status is clean (no phantom deletions)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val bytes = "live-index synced to head\n".toByteArray()
            // Mirror the W1 ContentStore write so the WORKING TREE matches the committed blob too.
            Files.createDirectories(root.resolve("docs"))
            Files.write(root.resolve("docs/page.md"), bytes)

            provider.commit(path, bytes)

            // No phantom staged deletions / modifications — the live index and working tree both match HEAD.
            exec.run(listOf("status", "--porcelain")).stdoutText.trim() shouldBe ""
            // The live index tree (write-tree on the live index) equals HEAD's tree.
            val liveIndexTree = GitExecutor.parseSha(exec.run(listOf("write-tree")).stdout)
            val headTree = GitExecutor.parseSha(exec.run(listOf("rev-parse", "HEAD^{tree}")).stdout)
            liveIndexTree shouldBe headTree
        }
    }

    // r6b: the provider must stage the path the injected resolver returns (the RAW on-disk repo-relative
    // path — raw-name-preserving), NOT the NFC `path.value`. On a normalization-preserving filesystem the
    // on-disk name is the NFD byte-form while the TreePath is NFC, so staging path.value would commit a
    // phantom path that does not match the real file (history diverges; W5's path-keyed citations miss it).
    // The resolver here returns a DISTINCT raw string (not a normalization variant — git's macOS
    // core.precomposeUnicode would silently NFC-fold a bare NFD arg, masking the staged value); we assert
    // the committed tree entry, `git log`, the live index, and the message ALL use the resolver's string,
    // proving the provider never falls back to path.value.
    test("commit stages the raw on-disk path from the resolver, not the NFC TreePath.value (r6b)") {
        withGitRepoHome { root, exec, home ->
            val nfcPath = TreePath.require("notes/page.md") // what the W1 hook hands the provider (NFC)
            val rawRepoPath = "notes/raw-on-disk-name.md" // what the resolver returns (the real on-disk path)
            val bytes = "page content\n".toByteArray()
            // A resolver that maps THIS TreePath to the raw path (LocalContentStore::resolveRepoRelativePath
            // stand-in). Write the file under the raw name so `git log -- <raw>` has a real match.
            Files.createDirectories(root.resolve("notes"))
            Files.write(root.resolve(rawRepoPath), bytes)
            val provider = providerOver(exec, root, home, repoPath = { if (it == nfcPath) rawRepoPath else it.value })

            val committed = provider.commit(nfcPath, bytes)

            // The committed tree entry is the RESOLVER's raw path — never the NFC `path.value` phantom.
            openOracle(root).use { repo ->
                val paths = repo.treePaths(repo.headCommits().single())
                paths shouldBe listOf(rawRepoPath)
                (nfcPath.value in paths) shouldBe false // "notes/page.md" (path.value) was NOT staged
            }
            // `git log -- <rawpath>` finds the commit (history is keyed on the real on-disk path).
            exec.run(listOf("log", "--format=%H", "--", rawRepoPath)).stdoutText.trim() shouldBe committed.sha
            // The live index reflects the raw path too (the sync staged the same string).
            exec.run(listOf("ls-files")).stdoutText.lines().any { it == rawRepoPath } shouldBe true
            // The commit message uses the raw path (consistency).
            committed.message shouldBe "Update $rawRepoPath"
        }
    }

    // P2 (no-op recovery): the idempotent path (newTree == HEAD^{tree}) must ALSO repair the live index.
    // Crash-then-reconcile re-commits already-committed bytes: HEAD has them, so it's a no-op — but the live
    // index would still show the page deleted/modified. Stale the live index (so the page reads as a phantom
    // deletion), then re-commit the SAME bytes: the no-op branch repairs the index even though no new commit
    // is made.
    test("the idempotent no-op recovery path repairs the live index (git status clean, no new commit)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val bytes = "recovered bytes\n".toByteArray()
            Files.createDirectories(root.resolve("docs"))
            Files.write(root.resolve("docs/page.md"), bytes)
            provider.commit(path, bytes)

            // Stale the live index so the saved path now shows as a phantom deletion (the crash-residue state).
            exec.run(listOf("read-tree", "--empty")).ok shouldBe true
            exec.run(listOf("status", "--porcelain")).stdoutText.lines().any { it.trim() == "D  docs/page.md" } shouldBe true

            // Reconcile re-commits the SAME bytes → no-op branch (tree unchanged). It must still repair the index.
            val before = exec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim()
            provider.commit(path, bytes)
            exec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim() shouldBe before // no new commit
            exec.run(listOf("status", "--porcelain")).stdoutText.trim() shouldBe "" // live index repaired
        }
    }

    // P1-B: when CONTENT_DIR is a SUBDIRECTORY of an outer git checkout (and git.enabled forces Git on),
    // `rev-parse --is-inside-work-tree` reports "true" against the ANCESTOR repo — so a rev-parse-based
    // init guard would skip init and silently advance the surrounding checkout. ensureRepo() must instead
    // `git init` a NESTED repo at CONTENT_DIR: the commit lands in CONTENT_DIR/.git and the outer repo is
    // untouched.
    test("CONTENT_DIR nested in an outer repo gets its own nested repo; the outer repo is never mutated") {
        val outer = Files.createTempDirectory("plainbase-outer-repo")
        val home = Files.createTempDirectory("plainbase-nested-home")
        try {
            // An outer checkout with a commit so it has a real HEAD/log to compare against.
            val outerExec = GitExecutor(workTree = outer, home = home)
            outerExec.run(listOf("init")).ok shouldBe true
            Files.write(outer.resolve("outer.md"), "outer\n".toByteArray())
            providerOver(outerExec, outer, home).commit(TreePath.require("outer.md"), "outer\n".toByteArray())
            val outerHeadBefore = outerExec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val outerLogBefore = outerExec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim()

            // CONTENT_DIR is a subdir of the outer checkout, with NO .git of its own yet.
            val content = outer.resolve("content")
            Files.createDirectories(content)
            (Files.exists(content.resolve(".git"))) shouldBe false

            val contentExec = GitExecutor(workTree = content, home = home)
            val provider = providerOver(contentExec, content, home)
            provider.commit(TreePath.require("page.md"), "nested\n".toByteArray())

            // (a) A nested repo was created at CONTENT_DIR.
            Files.exists(content.resolve(".git")) shouldBe true
            // (b) The commit landed in CONTENT_DIR/.git, rooted at CONTENT_DIR (path is relative to it).
            openOracle(content).use { repo ->
                repo.treePaths(repo.headCommits().single()) shouldBe listOf("page.md")
            }
            // (c) The OUTER repo's HEAD/log is UNCHANGED — never advanced by the nested commit.
            outerExec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe outerHeadBefore
            outerExec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim() shouldBe outerLogBefore
        } finally {
            outer.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    // r6a: a commit on a DETACHED HEAD advances HEAD itself (no branch ref). The atomic capture reports the
    // detached HEAD (symbolic-full-name = literal "HEAD" → null branch), and update-ref --no-deref moves HEAD.
    test("a commit on a detached HEAD advances the detached HEAD itself, not a branch") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "on main\n".toByteArray()) // born `main`
            val mainTipBefore = exec.run(listOf("rev-parse", "main")).stdoutText.trim()
            exec.run(listOf("checkout", "--detach")).ok shouldBe true
            exec.run(listOf("symbolic-ref", "-q", "HEAD")).ok shouldBe false // sanity: HEAD is detached

            val ours = provider.commit(path, "while detached\n".toByteArray())

            // HEAD now names our commit and stays detached; `main` did NOT move (--no-deref on HEAD, r6c).
            exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe ours.sha
            exec.run(listOf("symbolic-ref", "-q", "HEAD")).ok shouldBe false
            exec.run(listOf("rev-parse", "main")).stdoutText.trim() shouldBe mainTipBefore
        }
    }

    // r6e: a SHA-256 repo (64-hex object ids) round-trips a commit. parseSha must accept 64-hex, the seed/
    // commit-tree/update-ref plumbing must carry it, and the unborn create-CAS must use the 64-zero OID. The
    // repo is pre-inited with --object-format=sha256, so ensureRepo (Files.exists(.git)) skips its own init.
    test("a sha256 repo round-trips a commit (first commit + a second), 64-hex ids throughout (r6e)") {
        withGitRepoHome { root, exec, home ->
            // Pre-init as sha256 (skips ensureRepo's default init); object format pinned at init time.
            exec.run(listOf("init", "--object-format=sha256", "-b", "main", ".")).ok shouldBe true
            exec.run(listOf("rev-parse", "--show-object-format")).stdoutText.trim() shouldBe "sha256"

            val provider = providerOver(exec, root, home)
            val first = provider.commit(path, "sha256 first\n".toByteArray()) // unborn → create-CAS with 64 zeros
            first.sha.length shouldBe 64
            val second = provider.commit(path, "sha256 second\n".toByteArray())
            second.sha.length shouldBe 64

            exec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim() shouldBe "2"
            exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe second.sha
            // Byte-fidelity holds on sha256 too.
            exec.run(listOf("show", "HEAD:docs/page.md")).stdoutText shouldBe "sha256 second\n"
        }
    }

    test("lastCommits/log/diff throw NotImplementedError (W5), never a silently-empty result (F6)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "content\n".toByteArray())
            shouldThrow<NotImplementedError> { provider.lastCommits(listOf(path)) }
            shouldThrow<NotImplementedError> { provider.log(path) }
            shouldThrow<NotImplementedError> { provider.diff("HEAD~1", "HEAD", path) }
        }
    }
})
