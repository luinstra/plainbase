package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * [GitCliHistoryProvider] behavior over a real `git` (W4 §6 + W5 reads): the author/committer split (#1),
 * N saves → N commits incl. the unborn first commit (#2, F2), the failing-pre-commit-hook skip (#7), the F7
 * SHA-from-commit-tree guarantee, the W5 `log`/`lastCommits`/`diff` reads (incl. the debate MUST-FIX
 * parser/merge cases), and idempotent recovery re-commits. Commits are read back through the JGit oracle.
 */
class GitCliHistoryProviderTest : FunSpec({

    val path = TreePath.require("docs/page.md")

    // The hermetic executor nulls global/system config, so raw porcelain `git commit`/`merge` have no
    // user identity — supply one via env (the provider sets its own for commit-tree; these are test setup).
    val identityEnv = mapOf(
        "GIT_AUTHOR_NAME" to "T",
        "GIT_AUTHOR_EMAIL" to "t@localhost",
        "GIT_AUTHOR_DATE" to "@100 +0000",
        "GIT_COMMITTER_NAME" to "T",
        "GIT_COMMITTER_EMAIL" to "t@localhost",
        "GIT_COMMITTER_DATE" to "@100 +0000",
    )

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

    // ---- W5 P1: prepare() readies the content-root repo BEFORE the first read ------------------

    // W5 P1: Git FORCED ON for a CONTENT_DIR with NO own `.git`. The startup rebuild reads (lastCommits)
    // before any save commits — and `git -C workTree log` on a plain dir is an operational fatal that now
    // THROWS (would abort serve). prepare() must create the content-root repo first, so the read returns an
    // honest empty (commit=null citations, no abort) and a subsequent save commits into the content-root repo.
    test("prepare() creates the content-root repo so the first read is empty (no serve abort), and a later save commits there") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            Files.exists(root.resolve(".git")) shouldBe false

            provider.prepare()

            Files.exists(root.resolve(".git")) shouldBe true
            // The first rebuild's read: empty history, NOT an operational throw → commit=null citations, no abort.
            provider.lastCommits(listOf(path)) shouldBe emptyMap()
            provider.log(path) shouldBe emptyList()

            // A subsequent save commits into the content-root repo.
            val commit = provider.commit(path, "first\n".toByteArray())
            openOracle(root).use { repo -> repo.headCommits().single().name shouldBe commit.sha }
        }
    }

    // W5 P1 (the wrong-repo hole): CONTENT_DIR nested INSIDE an ancestor checkout, Git forced ON, no own
    // `.git`. Without prepare()'s nested init, the startup `git -C content log` walks UP to the ancestor
    // `.git` and reads the ANCESTOR's commits — violating W4's exact-content-root guarantee. prepare() inits
    // a NESTED repo at CONTENT_DIR so reads/commits resolve to the content root, never the ancestor.
    test("prepare() inits a nested repo for a CONTENT_DIR inside an ancestor checkout; reads never see the ancestor") {
        val outer = Files.createTempDirectory("plainbase-p1-outer")
        val home = Files.createTempDirectory("plainbase-p1-home")
        try {
            // An ancestor checkout with a real commit touching a file the same name a content page would use.
            val outerExec = GitExecutor(workTree = outer, home = home)
            outerExec.run(listOf("init")).ok shouldBe true
            Files.createDirectories(outer.resolve("docs"))
            providerOver(outerExec, outer, home).commit(path, "ancestor version\n".toByteArray())
            val ancestorSha = outerExec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            // CONTENT_DIR is a subdir of the ancestor, no `.git` of its own.
            val content = outer.resolve("content")
            Files.createDirectories(content)
            val contentExec = GitExecutor(workTree = content, home = home)
            val provider = providerOver(contentExec, content, home)

            provider.prepare()

            // A nested repo now roots at CONTENT_DIR; reads resolve to it, never the ancestor.
            Files.exists(content.resolve(".git")) shouldBe true
            provider.lastCommits(listOf(path)) shouldBe emptyMap() // content repo is empty, not the ancestor's history
            provider.log(path) shouldBe emptyList()

            // A save commits into the NESTED content-root repo; the ancestor sha never appears.
            val nested = provider.commit(path, "content version\n".toByteArray())
            (nested.sha == ancestorSha) shouldBe false
            provider.lastCommits(listOf(path)).getValue(path).sha shouldBe nested.sha
            provider.log(path).none { it.sha == ancestorSha } shouldBe true
            // The ancestor repo is untouched.
            outerExec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe ancestorSha
        } finally {
            outer.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    // ---- W5 reads: log / lastCommits / diff -----------------------------------------------------

    test("log returns the file's commits newest-first, one record per commit (JGit oracle)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            repeat(3) { n -> provider.commit(path, "version $n\n".toByteArray()) }

            val log = provider.log(path)
            log.size shouldBe 3
            openOracle(root).use { repo ->
                log.map { it.sha } shouldBe repo.headCommits().map { it.name }
            }
        }
    }

    test("log respects limit") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            repeat(4) { n -> provider.commit(path, "v$n\n".toByteArray()) }
            provider.log(path, limit = 2).size shouldBe 2
        }
    }

    test("log of an unbuilt path is empty (no commit, no crash)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "seed\n".toByteArray())
            provider.log(TreePath.require("docs/never.md")) shouldBe emptyList()
        }
    }

    test("log of an unborn repo is empty") {
        withGitRepoHome { root, exec, home ->
            exec.run(listOf("init")).ok shouldBe true
            providerOver(exec, root, home).log(path) shouldBe emptyList()
        }
    }

    // log/lastCommits on an unborn HEAD (empty repo) is the ONE benign non-zero git exit — "no history
    // yet" maps to empty, NOT an operational error. (`log` is covered just above; this pins the symmetric
    // `lastCommits` behavior so the fail-loud classifier never mistakes an empty repo for a real failure.)
    test("lastCommits on an unborn HEAD (empty repo) returns empty, not an error") {
        withGitRepoHome { root, exec, home ->
            exec.run(listOf("init")).ok shouldBe true
            providerOver(exec, root, home).lastCommits(listOf(path)) shouldBe emptyMap()
        }
    }

    // FAIL-LOUD (W4 gate-check philosophy): an OPERATIONAL `git log` failure (timeout, corrupt/inaccessible
    // repo, unsupported flag) must SURFACE as a GitCommandException — never collapse to empty. Collapsing it
    // would persist a false `commit = null` for a page that genuinely has history (IndexBuilder.reindex does
    // `log(path, 1).firstOrNull()?.sha`) and report a false empty on `/history`. The unborn-HEAD stderr is
    // the ONLY non-zero exit that maps to empty; every other non-zero throws. A fake `git` (the file's
    // withFakeGit/counting-git idiom — a real subprocess, never a mock) emits an operational fatal whose
    // stderr is NOT the unborn signature, deterministically across git versions.
    test("log surfaces an operational git failure instead of returning empty") {
        withFatalLogGit("fatal: unable to read tree object") { root, exec, home ->
            shouldThrow<GitCommandException> { providerOver(exec, root, home).log(path) }
        }
    }

    test("lastCommits surfaces an operational git failure instead of returning empty") {
        withFatalLogGit("fatal: index file corrupt") { root, exec, home ->
            shouldThrow<GitCommandException> { providerOver(exec, root, home).lastCommits(listOf(path)) }
        }
    }

    test("log of a single-commit history parses cleanly (no trailing-empty-record crash) — MUST-FIX 1") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val commit = provider.commit(path, "only\n".toByteArray())
            val log = provider.log(path)
            log.map { it.sha } shouldBe listOf(commit.sha) // exactly one element, never an IndexOutOfBounds
        }
    }

    test("log parses a commit message containing newlines and an embedded separator byte — MUST-FIX 2") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "seed\n".toByteArray()) // born HEAD so amend has a base
            // Rewrite HEAD's message to one with a newline AND the RS/US control bytes git's older format
            // designs used as separators, proving the NUL-framed parse keeps an embedded byte (NUL excepted,
            // which git forbids) inside the message field instead of shifting/splitting records.
            val spicy = "line one\n\nbody with ${Char(0x1e)} and ${Char(0x1f)} embedded\n"
            exec.run(listOf("commit", "--amend", "-m", spicy), identityEnv).ok shouldBe true

            val message = provider.log(path).single().message
            message shouldContain "line one"
            message shouldContain "embedded"
            // Byte-fidelity: the control bytes (and the newline) survive the NUL-framed parse verbatim — not
            // just "no field shift" but the exact bytes round-trip inside the message field.
            message shouldBe spicy.removeSuffix("\n")
            message shouldContain "${Char(0x1e)} and ${Char(0x1f)}"
        }
    }

    test("lastCommits batches the newest touch per path and returns the right shas (JGit oracle)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val a = TreePath.require("docs/a.md")
            val b = TreePath.require("docs/b.md")
            provider.commit(a, "a1\n".toByteArray())
            val bCommit = provider.commit(b, "b1\n".toByteArray())
            val aCommit = provider.commit(a, "a2\n".toByteArray()) // a's newest touch

            val map = provider.lastCommits(listOf(a, b))
            map.getValue(a).sha shouldBe aCommit.sha
            map.getValue(b).sha shouldBe bCommit.sha
        }
    }

    test("lastCommits omits a path that has no commit (caller treats absent as null)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val a = TreePath.require("docs/a.md")
            val missing = TreePath.require("docs/missing.md")
            provider.commit(a, "a1\n".toByteArray())
            val map = provider.lastCommits(listOf(a, missing))
            map.keys shouldBe setOf(a)
        }
    }

    test("lastCommits maps ALL paths touched by one commit to that commit (don't stop at the first)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val a = TreePath.require("docs/a.md")
            val b = TreePath.require("docs/b.md")
            // One commit staging two paths via the live index (the provider commits one path at a time).
            exec.run(listOf("init")).ok shouldBe true
            Files.createDirectories(root.resolve("docs"))
            Files.writeString(root.resolve("docs/a.md"), "a\n")
            Files.writeString(root.resolve("docs/b.md"), "b\n")
            exec.run(listOf("add", "docs/a.md", "docs/b.md")).ok shouldBe true
            exec.run(listOf("commit", "-m", "both"), identityEnv).ok shouldBe true
            val head = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            val map = provider.lastCommits(listOf(a, b))
            map.getValue(a).sha shouldBe head
            map.getValue(b).sha shouldBe head
        }
    }

    test("lastCommits attributes a file last changed by a MERGE via first-parent — MUST-FIX 3") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val file = TreePath.require("docs/page.md")
            val onDisk = root.resolve("docs/page.md")
            exec.run(listOf("init")).ok shouldBe true
            Files.createDirectories(onDisk.parent)

            // Build the scenario over a real working tree (raw porcelain) so the merge is genuine: a base
            // commit on main, a side branch that resolves the file, then a no-fast-forward merge back into
            // main. The file's last change on main reaches it via the MERGE along first-parent history.
            fun commitWorkingTree(message: String) {
                exec.run(listOf("add", "docs/page.md")).ok shouldBe true
                exec.run(listOf("commit", "-m", message), identityEnv).ok shouldBe true
            }
            Files.writeString(onDisk, "base\n")
            commitWorkingTree("base")
            exec.run(listOf("checkout", "-b", "side")).ok shouldBe true
            Files.writeString(onDisk, "from side\n")
            commitWorkingTree("side edit")
            exec.run(listOf("checkout", "main")).ok shouldBe true
            exec.run(listOf("merge", "--no-ff", "-m", "merge side", "side"), identityEnv).ok shouldBe true
            val merge = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            // first-parent attribution: the merge is the last commit touching the file along main.
            provider.lastCommits(listOf(file)).getValue(file).sha shouldBe merge
            provider.log(file).first().sha shouldBe merge
        }
    }

    test("lastCommits issues ONE git log process for a normal batch of paths (not one-per-path)") {
        // A counting fake-git: every invocation appends its first arg to a tally file. The criterion is
        // independence from per-PAGE spawning, not a hard "exactly one": normal trees (here three short
        // paths) fit a single argv batch under MAX_PATHSPEC_BYTES_PER_BATCH, so lastCommits shells out
        // exactly once; only a huge tree splits into O(N/batch) walks (covered by the large-tree test below).
        val root = Files.createTempDirectory("plainbase-git-count")
        val home = Files.createTempDirectory("plainbase-git-count-home")
        val tally = Files.createTempFile("git-tally", ".log")
        val realGit = "git"
        // Tally one line per invocation whose subcommand is `log` (the first non-flag arg after the
        // pinned `-C <wt>` / `-c <cfg>` prefix), then exec real git so the read still works.
        val script = "#!/bin/sh\nsub=\"\"\nskip=0\nfor a in \"\$@\"; do\n" +
            "  if [ \$skip -eq 1 ]; then skip=0; continue; fi\n" +
            "  case \"\$a\" in -C|-c) skip=1;; -*) ;; *) sub=\"\$a\"; break;; esac\ndone\n" +
            "[ \"\$sub\" = log ] && echo log >> \"$tally\"\nexec $realGit \"\$@\"\n"
        val bin = Files.createTempFile("counting-git", ".sh")
        Files.writeString(bin, script)
        Files.setPosixFilePermissions(bin, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        try {
            val exec = GitExecutor(workTree = root, home = home, gitBinary = bin.toString())
            val provider = providerOver(exec, root, home)
            val a = TreePath.require("docs/a.md")
            val b = TreePath.require("docs/b.md")
            val c = TreePath.require("docs/c.md")
            provider.commit(a, "a\n".toByteArray())
            provider.commit(b, "b\n".toByteArray())
            provider.commit(c, "c\n".toByteArray())
            Files.writeString(tally, "") // reset the tally after setup

            provider.lastCommits(listOf(a, b, c))

            Files.readAllLines(tally).count { it == "log" } shouldBe 1
        } finally {
            Files.deleteIfExists(bin)
            Files.deleteIfExists(tally)
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    // P2 (argv overflow): IndexBuilder.rebuild passes the WHOLE corpus to lastCommits, so a single
    // `git log -- <every path>` would blow ARG_MAX on a large content tree (`Argument list too long`) and
    // kill Git-mode startup/rescan. lastCommits must instead BATCH the pathspecs under a byte budget. With
    // many long synthetic paths whose total bytes clearly exceed one batch, every path is still attributed
    // correctly (JGit oracle) AND the process count is O(N/batch) — a small handful of `git log` walks, not
    // one per page (which would be hundreds). The counting fake-git tallies the `log` invocations.
    test("lastCommits batches a large path set under the argv budget — correct attributions, O(N/batch) walks") {
        val root = Files.createTempDirectory("plainbase-git-batch")
        val home = Files.createTempDirectory("plainbase-git-batch-home")
        val tally = Files.createTempFile("git-batch-tally", ".log")
        val script = "#!/bin/sh\nsub=\"\"\nskip=0\nfor a in \"\$@\"; do\n" +
            "  if [ \$skip -eq 1 ]; then skip=0; continue; fi\n" +
            "  case \"\$a\" in -C|-c) skip=1;; -*) ;; *) sub=\"\$a\"; break;; esac\ndone\n" +
            "[ \"\$sub\" = log ] && echo log >> \"$tally\"\nexec git \"\$@\"\n"
        val bin = Files.createTempFile("batch-git", ".sh")
        Files.writeString(bin, script)
        Files.setPosixFilePermissions(bin, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        try {
            val exec = GitExecutor(workTree = root, home = home, gitBinary = bin.toString())
            val provider = providerOver(exec, root, home)
            exec.run(listOf("init")).ok shouldBe true

            // ~400 files, each path ~430 bytes (a deep, long-named dir) → ~170 KiB of pathspecs, comfortably
            // past the 128 KiB per-batch budget so batching MUST engage (≥2 walks), yet fast (one big commit).
            val longSeg = "a".repeat(200)
            val paths = (0 until 400).map { TreePath.require("docs/$longSeg/$longSeg/page-$it.md") }
            paths.forEach { p ->
                val onDisk = root.resolve(p.value)
                Files.createDirectories(onDisk.parent)
                Files.writeString(onDisk, "seed $p\n")
            }
            exec.run(listOf("add", "-A")).ok shouldBe true
            exec.run(listOf("commit", "-m", "seed all"), identityEnv).ok shouldBe true
            val seedAll = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            // Re-touch a handful individually so "newest touch per path" is non-trivial across batches.
            val retouched = listOf(paths.first(), paths[199], paths.last())
            val newest = retouched.associateWith { p ->
                Files.writeString(root.resolve(p.value), "newest $p\n")
                exec.run(listOf("add", p.value)).ok shouldBe true
                exec.run(listOf("commit", "-m", "touch $p"), identityEnv).ok shouldBe true
                exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            }
            Files.writeString(tally, "") // reset the tally after setup

            val map = provider.lastCommits(paths)

            // Every path attributed; the re-touched ones to their newest commit, the rest to the seed commit.
            map.keys shouldBe paths.toSet()
            retouched.forEach { p -> map.getValue(p).sha shouldBe newest.getValue(p) }
            paths.filterNot { it in retouched }.forEach { p -> map.getValue(p).sha shouldBe seedAll }

            // O(N/batch), NOT O(N): a handful of walks (here 2 — 170 KiB / 128 KiB), never ~400.
            val walks = Files.readAllLines(tally).count { it == "log" }
            (walks in 2..8) shouldBe true // batching engaged (>1) yet far below per-page (400)
        } finally {
            Files.deleteIfExists(bin)
            Files.deleteIfExists(tally)
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    // P3 (parser separator strip): git's `log -z --name-only` framing prefixes ONLY the FIRST filename of a
    // commit's name-list with the format→name-list separator `\n`; later filenames are raw. A page whose
    // repo-relative path legitimately STARTS WITH `\n` (control-char filenames are in-surface per the
    // freeze-surface policy) and is NOT the first file of a multi-file commit must keep its leading `\n` —
    // a blanket removePrefix on every token would strip it, fail to match byRepoPath, and drop the citation.
    // Here a single commit touches two files; the SECOND's repo path starts with a literal `\n`. Both must
    // be attributed to that commit (the newline-leading one matches, commit not null).
    test("lastCommits attributes a leading-newline filename in the SECOND name-list slot — P3") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            exec.run(listOf("init")).ok shouldBe true
            // The FIRST file must sort BEFORE the leading-newline (0x0A) file so the `\n`-leading one lands
            // in a non-first name-list slot: a leading TAB (0x09) sorts before 0x0A.
            val tabName = "\ttab.md"
            val nlName = "\nleadingnl.md"
            val tabPath = TreePath.require(tabName)
            val nlPath = TreePath.require(nlName)
            Files.writeString(root.resolve(tabName), "tab\n")
            Files.writeString(root.resolve(nlName), "nl\n")
            exec.run(listOf("add", "-A")).ok shouldBe true
            exec.run(listOf("commit", "-m", "two files, second leads with newline"), identityEnv).ok shouldBe true
            val head = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            val map = provider.lastCommits(listOf(tabPath, nlPath))
            map.getValue(tabPath).sha shouldBe head
            map.getValue(nlPath).sha shouldBe head // the leading-`\n` path matches; commit is NOT null
        }
    }

    // Re-review P2-2 (LITERAL PATHSPECS): a page filename containing pathspec metacharacters (`[`, `*`,
    // `?`, a leading `:`) is interpreted by `git log`/`diff` as a GLOB/magic pathspec after `--`, NOT a
    // literal name — so `weird[1].md` would match the sibling `weird1.md` (wrong commits/diff) or nothing
    // (dropped citation). `GIT_LITERAL_PATHSPECS=1` forces literal matching. The bracket file must resolve
    // to ITS OWN commits only, never the sibling's.
    test("read paths are literal, not globs — a filename with brackets never matches a sibling (GIT_LITERAL_PATHSPECS)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val bracket = TreePath.require("docs/weird[1].md") // a glob would expand `[1]` to the char class `1`
            val sibling = TreePath.require("docs/weird1.md") // which `weird[1].md` would falsely match
            val siblingCommit = provider.commit(sibling, "sibling content\n".toByteArray())
            val bracketV1 = provider.commit(bracket, "bracket one\n".toByteArray())
            val bracketV2 = provider.commit(bracket, "bracket two\n".toByteArray())

            // lastCommits: the bracket path resolves to its OWN newest commit, never the sibling's.
            provider.lastCommits(listOf(bracket)).getValue(bracket).sha shouldBe bracketV2.sha
            // log: exactly the bracket file's two commits, newest-first — the sibling's commit never leaks in.
            val log = provider.log(bracket)
            log.map { it.sha } shouldBe listOf(bracketV2.sha, bracketV1.sha)
            log.none { it.sha == siblingCommit.sha } shouldBe true
            // diff: the bracket file's own change between its two commits, not the sibling's content.
            val diff = provider.diff(bracketV1.sha, bracketV2.sha, bracket)
            diff.unifiedDiff shouldContain "-bracket one"
            diff.unifiedDiff shouldContain "+bracket two"
            diff.unifiedDiff.contains("sibling content") shouldBe false
        }
    }

    test("lastCommits/log/diff use the raw repoPath, not the NFC TreePath.value (r6b)") {
        withGitRepoHome { root, exec, home ->
            // NFD on disk, NFC TreePath: the provider must read on the raw on-disk repo path.
            val nfd = "café.md" // café decomposed
            val nfc = TreePath.require("café.md")
            val provider = providerOver(exec, root, home, repoPath = { nfd })
            provider.commit(nfc, "decomposed\n".toByteArray())

            provider.lastCommits(listOf(nfc)).keys shouldBe setOf(nfc)
            provider.log(nfc).size shouldBe 1
        }
    }

    test("diff between two shas yields the unified diff for the path") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "first line\n".toByteArray())
            provider.commit(path, "second line\n".toByteArray())
            val old = exec.run(listOf("rev-parse", "HEAD~1")).stdoutText.trim()
            val new = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            val diff = provider.diff(old, new, path)
            diff.from shouldBe old
            diff.to shouldBe new
            diff.unifiedDiff shouldContain "-first line"
            diff.unifiedDiff shouldContain "+second line"
        }
    }

    test("diff of an unknown sha surfaces an UnknownRevisionException (→ route 404), never a silent empty") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "content\n".toByteArray())
            // The route maps the narrow UnknownRevisionException to 404; it IS a GitCommandException so the
            // W1 commit-path catch stays correct. A bad ref ("bad object" / "unknown revision") classifies here.
            shouldThrow<UnknownRevisionException> { provider.diff("deadbeef", "HEAD", path) }
        }
    }

    // W5 P2: an OPERATIONAL diff failure (timeout, corrupt/inaccessible repo, unsupported flag) is NOT a bad
    // ref — it must propagate as a plain GitCommandException (route → 500), never collapse to the 404
    // UnknownRevisionException that would hide it as "no diff". A fake `git` whose `diff` exits 128 with an
    // operational fatal (NOT a bad-object/unknown-revision signature) proves the classification splits.
    test("diff surfaces an operational failure as a plain GitCommandException, not UnknownRevisionException — P2") {
        withFatalDiffGit("fatal: unable to read tree object") { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val ex = shouldThrow<GitCommandException> { provider.diff("HEAD~1", "HEAD", path) }
            (ex is UnknownRevisionException) shouldBe false // operational ⇒ 500, not the 404 ref error
        }
    }

    // BLOCKING 1 (RCE): `git diff` honors a repo-local `diff.external`/custom diff driver/`textconv` from
    // `.git/config` or `.gitattributes` — attacker-controlled config in the served repo would otherwise run
    // arbitrary commands as the server user when the diff read-path is hit. `--no-ext-diff --no-textconv`
    // disarm both. A sentinel file the helper would write proves it NEVER ran, and the real git diff is
    // returned unchanged.
    test("diff does not run a repo-local external diff helper (--no-ext-diff)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            provider.commit(path, "first line\n".toByteArray())
            provider.commit(path, "second line\n".toByteArray())
            val old = exec.run(listOf("rev-parse", "HEAD~1")).stdoutText.trim()
            val new = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            // A malicious external diff driver + a textconv driver, each writing a sentinel on invocation.
            val extSentinel = root.resolve("EXT_RAN")
            val tcSentinel = root.resolve("TEXTCONV_RAN")
            val extScript = root.resolve("evil-diff.sh")
            val tcScript = root.resolve("evil-textconv.sh")
            Files.writeString(extScript, "#!/bin/sh\ntouch '$extSentinel'\nexit 0\n")
            Files.writeString(tcScript, "#!/bin/sh\ntouch '$tcSentinel'\ncat \"\$1\"\n")
            Files.setPosixFilePermissions(extScript, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            Files.setPosixFilePermissions(tcScript, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            exec.run(listOf("config", "diff.external", extScript.toString())).ok shouldBe true
            exec.run(listOf("config", "diff.evil.textconv", tcScript.toString())).ok shouldBe true
            Files.writeString(root.resolve(".gitattributes"), "*.md diff=evil\n")

            val diff = provider.diff(old, new, path)

            Files.exists(extSentinel) shouldBe false // the external diff driver was NEVER invoked
            Files.exists(tcSentinel) shouldBe false // the textconv driver was NEVER invoked
            diff.unifiedDiff shouldContain "-first line" // the REAL git diff is returned, not helper output
            diff.unifiedDiff shouldContain "+second line"
        }
    }

    // BLOCKING 2 (parser shift): a commit with an EMPTY message emits an empty `%B` token under `-z`. Filtering
    // empties at the split would drop it and shift every later field, misattributing commits. The fixed
    // COMMIT_FIELD_COUNT stride must keep fields intact, so the empty-message commit is still attributed to
    // its own SHA for the path.
    test("lastCommits attributes correctly when a touching commit has an empty message") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val onDisk = root.resolve("docs/page.md")
            exec.run(listOf("init")).ok shouldBe true
            Files.createDirectories(onDisk.parent)

            Files.writeString(onDisk, "base\n")
            exec.run(listOf("add", "docs/page.md")).ok shouldBe true
            exec.run(listOf("commit", "-m", "base"), identityEnv).ok shouldBe true

            // The newest touch carries an EMPTY message — the empty `%B` token must not shift the parse.
            Files.writeString(onDisk, "newest\n")
            exec.run(listOf("add", "docs/page.md")).ok shouldBe true
            exec.run(listOf("commit", "--allow-empty-message", "-m", ""), identityEnv).ok shouldBe true
            val emptyMsgCommit = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            provider.lastCommits(listOf(path)).getValue(path).sha shouldBe emptyMsgCommit
            provider.log(path).first().sha shouldBe emptyMsgCommit // the `log` variant is empty-safe too
        }
    }

    // BLOCKING 3 (traversal): `--diff-merges=first-parent` only controls how merge diffs are SHOWN; without
    // `--first-parent` git still WALKS the side parent, so `log`/`lastCommits` would leak a side-branch-only
    // commit as the path's last touch. With a merge that does NOT itself change the path (first-parent content
    // wins), first-parent traversal must report the mainline commit, never the side commit S.
    test("lastCommits/log follow first-parent traversal (a side-branch-only commit is not reported)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val file = TreePath.require("docs/page.md")
            val onDisk = root.resolve("docs/page.md")
            val other = root.resolve("docs/other.md")
            exec.run(listOf("init")).ok shouldBe true
            Files.createDirectories(onDisk.parent)

            fun commitAll(message: String) {
                exec.run(listOf("add", "-A")).ok shouldBe true
                exec.run(listOf("commit", "-m", message), identityEnv).ok shouldBe true
            }
            // Mainline establishes the page; record the mainline commit that last touched it.
            Files.writeString(onDisk, "mainline content\n")
            commitAll("base on main")
            val mainlineTouch = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            // Side branch off base changes the PAGE (commit S), then mainline moves on touching an unrelated
            // file so the side branch is genuinely a side line, not fast-forwardable.
            exec.run(listOf("checkout", "-b", "side")).ok shouldBe true
            Files.writeString(onDisk, "side content\n")
            commitAll("S: side edit of page")
            val sideCommit = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            exec.run(listOf("checkout", "main")).ok shouldBe true
            Files.writeString(other, "unrelated\n")
            commitAll("mainline moves on (unrelated)")

            // Merge side into main with `-s ours`: the merge tree is IDENTICAL to the first parent (mainline),
            // so the page resolves to first-parent content and the merge does NOT change the page. first-parent
            // traversal is therefore TREESAME at the merge and never reaches S (the side-only page edit).
            exec.run(listOf("merge", "--no-ff", "-s", "ours", "-m", "merge side", "side"), identityEnv).ok shouldBe true

            // first-parent traversal excludes S; the page's last touch on main is the mainline commit.
            val reported = provider.lastCommits(listOf(file)).getValue(file).sha
            reported shouldBe mainlineTouch
            (reported == sideCommit) shouldBe false
            provider.log(file).none { it.sha == sideCommit } shouldBe true
        }
    }

    // SG-1 (RCE): a hostile `.git/config` setting `log.showSignature=true` + `gpg.program=<helper>` makes
    // `git log`/`git show -s` shell out to the helper to "verify" a (signed) commit — an RCE on the read
    // path (`/history`/`show`/`lastCommits`). PINNED_CONFIG's `-c log.showSignature=false` disarms the
    // trigger on every invocation, overriding the repo config. A sentinel the helper would write proves it
    // NEVER ran, and the reads still return the real commit.
    test("history reads never invoke a repo-configured gpg.program (-c log.showSignature=false)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val first = provider.commit(path, "first line\n".toByteArray())

            // A hostile gpg helper that writes a sentinel on any invocation, wired in via the repo's own
            // config exactly as an attacker-controlled checkout would carry it.
            val gpgSentinel = root.resolve("GPG_RAN")
            val gpgScript = root.resolve("evil-gpg.sh")
            Files.writeString(gpgScript, "#!/bin/sh\ntouch '$gpgSentinel'\nexit 0\n")
            Files.setPosixFilePermissions(gpgScript, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            exec.run(listOf("config", "log.showSignature", "true")).ok shouldBe true
            exec.run(listOf("config", "gpg.program", gpgScript.toString())).ok shouldBe true

            // Every read path that runs `git log`/`git show -s`.
            provider.log(path).first().sha shouldBe first.sha
            provider.lastCommits(listOf(path)).getValue(path).sha shouldBe first.sha
            // `git show -s` directly — the same pinned `log.showSignature=false` must hold here too.
            exec.run(listOf("show", "-s", first.sha)).ok shouldBe true

            Files.exists(gpgSentinel) shouldBe false // the gpg.program helper was NEVER invoked
        }
    }

    // SG-2 (RCE): the `ext::` transport lets a remote URL run an arbitrary command; a hostile config pairing
    // an `ext::` remote (via `insteadOf`) with `maintenance.prefetch=true` would run it when auto-maintenance
    // fires `git maintenance run --auto`/`gc --auto`. PINNED_CONFIG's `-c protocol.ext.allow=never` makes the
    // ext transport unusable on every invocation, so maintenance can never be steered into running it. The
    // sentinel the ext helper would write proves it NEVER ran; maintenance still completes and leaves history.
    test("auto-maintenance never runs an ext:: remote even with a hostile prefetch config (-c protocol.ext.allow=never)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home, maintenance = null)
            provider.commit(path, "content\n".toByteArray())

            // The ext:: helper writes a sentinel if the ext transport ever runs it.
            val extSentinel = root.resolve("EXT_REMOTE_RAN")
            val extScript = root.resolve("evil-ext.sh")
            Files.writeString(extScript, "#!/bin/sh\ntouch '$extSentinel'\nexit 1\n")
            Files.setPosixFilePermissions(extScript, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            // A hostile prefetch-able remote whose URL is an ext:: transport invoking the helper.
            exec.run(listOf("config", "maintenance.prefetch", "true")).ok shouldBe true
            exec.run(listOf("remote", "add", "evil", "ext::$extScript %S")).ok shouldBe true
            exec.run(listOf("config", "remote.evil.fetch", "+refs/heads/*:refs/remotes/evil/*")).ok shouldBe true

            runAutoMaintenance(exec) // the real default dispatcher — `maintenance run --auto` / `gc --auto`

            Files.exists(extSentinel) shouldBe false // the ext:: transport was NEVER allowed to run the helper
            exec.run(listOf("fsck")).ok shouldBe true // history stays consistent — GC itself still ran
        }
    }
})

/**
 * A fresh temp repo wired to a fake `git` (the file's withFakeGit/counting-git idiom — a real subprocess,
 * never a mock) that makes EVERY `git log` exit 128 with [stderr] (an operational fatal whose text is NOT
 * the unborn-HEAD signature), while passing all other subcommands through to real git. This deterministically
 * induces the OPERATIONAL read failure the fail-loud classifier must surface — version-independent, no fragile
 * object-store surgery.
 */
private fun <T> withFatalLogGit(stderr: String, block: (root: java.nio.file.Path, exec: GitExecutor, home: java.nio.file.Path) -> T): T =
    withFatalSubcommandGit("log", stderr, block)

/**
 * As [withFatalLogGit], but the fake `git` makes every `git diff` exit 128 with [stderr] — an OPERATIONAL
 * fatal whose text is NOT a bad-object/unknown-revision signature (W5 P2). Proves the diff classifier lets
 * an operational failure propagate as a plain [GitCommandException] (route → 500), never the 404
 * [UnknownRevisionException].
 */
private fun <T> withFatalDiffGit(stderr: String, block: (root: java.nio.file.Path, exec: GitExecutor, home: java.nio.file.Path) -> T): T =
    withFatalSubcommandGit("diff", stderr, block)

private fun <T> withFatalSubcommandGit(
    subcommand: String,
    stderr: String,
    block: (root: java.nio.file.Path, exec: GitExecutor, home: java.nio.file.Path) -> T,
): T {
    val root = Files.createTempDirectory("plainbase-fatal-$subcommand")
    val home = Files.createTempDirectory("plainbase-fatal-$subcommand-home")
    // Detect the subcommand by skipping the pinned `-C <wt>` / `-c <cfg>` prefix and other flags, exactly as
    // the counting-git script does; on a match emit the operational stderr + exit 128, else exec real git.
    val script = "#!/bin/sh\nsub=\"\"\nskip=0\nfor a in \"\$@\"; do\n" +
        "  if [ \$skip -eq 1 ]; then skip=0; continue; fi\n" +
        "  case \"\$a\" in -C|-c) skip=1;; -*) ;; *) sub=\"\$a\"; break;; esac\ndone\n" +
        "if [ \"\$sub\" = $subcommand ]; then echo \"$stderr\" 1>&2; exit 128; fi\nexec git \"\$@\"\n"
    val bin = Files.createTempFile("fatal-$subcommand-git", ".sh")
    Files.writeString(bin, script)
    Files.setPosixFilePermissions(bin, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    return try {
        block(root, GitExecutor(workTree = root, home = home, gitBinary = bin.toString()), home)
    } finally {
        Files.deleteIfExists(bin)
        root.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }
}
