package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.livePathOf
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * **C4, end to end: the chunk that restores OFFLINE delete convergence.**
 *
 * C2 gave back the ONLINE delete (an unbroken epoch witnessed it go). C4 gives back the delete an operator commits
 * while the server is DOWN, with the one oracle that survives a shutdown - RECORDED HUMAN INTENT:
 *
 * > **A commit range that DELETED the path, on a HEAD that DESCENDS from the recorded checkpoint, confirmed by a
 * > complete scan.**
 *
 * These rows drive the DECISION logic against a FAKE [HistoryProvider] (a deterministic range, no git binary; the
 * real-git plumbing is `GitAbsenceOracleNativeTest`). Like the C2 rows they come in pairs: every row that reaps or
 * advances has a twin that refuses, differing in exactly one fact - a moved head, an unread path, a rewritten
 * history, a revoked view.
 *
 * Most roots here are NOT observed: a git offline delete converges with NO epoch at all, which is the whole point.
 * But production's boot is the OTHER way round - `serve()` installs the watcher BEFORE the first rebuild - so the
 * WATCHED rows are the ones that decide whether the feature does its job at all, and they come last.
 */
class GitAbsenceConvergenceTest : FunSpec({

    val extra = RootName.require("extra")
    val rollback = TreePath.require("notes/rollback.md")

    test("a git-committed offline delete converges on the next boot - the range proves it, no epoch required") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id
                world.retirements.gitHead(extra) shouldBe "A" // a baseline is recorded on first sight

                // The operator `git rm`s it while the server is DOWN. On the reboot the walk never sees the page and
                // no epoch ever witnessed it, so ONLY the committed range A..B can prove it gone.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                world.restart()
                git.head = "B"
                git.deleted = setOf(rollback)
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild()

                withClue("the binding is RETIRED, not hard-deleted: /p/{id} answers 410, never a 404") {
                    world.idMap.livePathOf(id).shouldBeNull()
                    world.idMap.retiredAt(extra, id).shouldNotBeNull().path shouldBe RootedPath(extra, rollback)
                }
                world.limbo.count(extra) shouldBe 0
                world.retirements.gitHead(extra) shouldBe "B" // the checkpoint advances with the deletion it proved
            }
        }
    }

    test("git mv does not split the permalink - the D-side proof is refuted by the witness that read the new path") {
        withAbsenceTrees { mainDir, extraDir ->
            val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val page = "---\nid: ${id.value}\ntitle: Rollback\n---\n\n# Rollback\n"
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/old.md", page)
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild()

                // `git mv old.md new.md`: the range deletes old.md (a `D old`, because --no-renames is deliberate),
                // and the SAME id is now at new.md, which this pass READS. The refutation drops the D-side cover, the
                // id travels with the file - AND the checkpoint still advances, symmetric with the empty-reap row.
                extraDir.resolve("notes/old.md").toFile().delete()
                writePage(extraDir, "notes/new.md", page)
                git.head = "B"
                git.deleted = setOf(TreePath.require("notes/old.md"))
                val healed = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild()

                withClue("same id at the new path - the permalink did not split") {
                    healed.byPath.getValue(RootedPath(extra, TreePath.require("notes/new.md"))).id shouldBe id
                }
                world.idMap.retiredBindings().shouldBeEmpty() // a refuted cover is resolution by PRESENCE - nothing died
                world.retirements.gitHead(extra) shouldBe "B" // ...and the range is consumed, so it never re-diffs
            }
        }
    }

    test("a non-descendant head mints nothing and advances nothing - a rewritten history idles the oracle") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            writePage(extraDir, "notes/keep.md", "# Keep\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id

                // A force-push / `pull --rebase` rewrote history: the new head is NOT a descendant of the checkpoint,
                // so the oracle cannot trust the range at all. It idles - no proof, no advance - until C5 re-baselines.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                git.head = "C"
                git.ancestry = false
                git.deleted = setOf(rollback)
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild()

                world.idMap.retiredAt(extra, id).shouldBeNull()
                world.limbo.holds(extra, id) shouldBe true
                world.retirements.gitHead(extra) shouldBe "A" // pinned until reconcile - stated residue
            }
        }
    }

    test("a range-deleted path in unread mints no cover and withholds the advance - an unknown is not a fact") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            writePage(extraDir, "notes/keep.md", "# Keep\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val store = UnreadableAfterArming(LocalContentStore(extraDir), rollback)
                val builder = world.builder(mainDir, store, world.indexer, extraHistory = git)
                val id = builder.rebuild().byPath.getValue(RootedPath(extra, rollback)).id

                // The file is on disk but this pass cannot READ it, AND the range deleted it: neither witnessed nor
                // proven gone. The cover is withheld (no reap) and the whole root's advance is withheld too.
                store.armed = true
                git.head = "B"
                git.deleted = setOf(rollback)
                builder.rebuild()

                world.idMap.retiredAt(extra, id).shouldBeNull()
                world.limbo.holds(extra, id) shouldBe true
                world.retirements.gitHead(extra) shouldBe "A" // an AbsenceUnknown may not advance a checkpoint
            }
        }
    }

    test("an empty effective reap set still advances the checkpoint - a restored file must not pin the range") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                builder.rebuild()

                // The range deleted the page, but it is PRESENT on disk (restored, or within the bracket's residual
                // window): the file this pass READ resolves the range by presence, so nothing is reaped - and yet the
                // checkpoint MUST advance, or the range re-diffs an ever-growing history forever.
                git.head = "B"
                git.deleted = setOf(rollback)
                builder.rebuild()

                world.idMap.retiredBindings().shouldBeEmpty()
                world.retirements.gitHead(extra) shouldBe "B"
            }
        }
    }

    test("a HEAD that moves during the pass yields no proof and no advance - the bracket") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            writePage(extraDir, "notes/keep.md", "# Keep\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                val id = builder.rebuild().byPath.getValue(RootedPath(extra, rollback)).id

                // A `git rm && commit` lands DURING the walk: the pre-scan head is A, but by the mint the head is Z.
                // The bracket sees the move and refuses the root this pass; the next pass re-derives from the unmoved A.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                git.deleted = setOf(rollback)
                git.moveMidPass("A", "Z")
                builder.rebuild()

                world.idMap.retiredAt(extra, id).shouldBeNull()
                world.limbo.holds(extra, id) shouldBe true
                world.retirements.gitHead(extra) shouldBe "A"
            }
        }
    }

    test("a revoke from OUTSIDE the pass drops the git proof AND its advance in the same transaction") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            writePage(extraDir, "notes/keep.md", "# Keep\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id
                world.retirements.gitHead(extra) shouldBe "A"

                // The tree is swapped out from under the pass AFTER the mint and BEFORE the apply - a watcher break
                // arriving on another thread, which is the only kind of revoke that means "what you just derived was
                // about a tree that is no longer there". The proof and its advance are BOTH discarded by the freshness
                // compare - one token, one transaction, no window.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                git.head = "B"
                git.deleted = setOf(rollback)
                val broken = BreakOnApply(world.retirements) { world.broke("extra", BreakCause.IDENTITY_REBIND) }
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git, retirements = broken).rebuild()

                world.idMap.retiredAt(extra, id).shouldBeNull() // the token moved under it, so it authorized nothing
                world.retirements.gitHead(extra) shouldBe "A" // and the advance rode the identical discarded token
            }
        }
    }

    test("a break landing between the SCAN and the MINT drops the git proof AND its advance - the stamp precedes the evidence") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            writePage(extraDir, "notes/keep.md", "# Keep\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id
                world.retirements.gitHead(extra) shouldBe "A"

                extraDir.resolve("notes/rollback.md").toFile().delete()
                git.head = "B"
                git.deleted = setOf(rollback)
                // THE WINDOW, one layer EARLIER than the row above: the break lands after the scan has fixed this pass's
                // evidence and BEFORE the mint takes its observation stamp. Read at MINT, that stamp is the POST-break
                // token, so `applyProofs` compares it against itself, MATCHES, and the stale proof reaps the binding
                // while its advance consumes the range for good - a break swallowed by the very source it must kill.
                // Captured BEFORE the evidence, the break moves the token past the stamp and both are discarded.
                val breaking = BreakAtScanEnd(LocalContentStore(extraDir)) { world.broke("extra", BreakCause.IDENTITY_REBIND) }
                world.builder(mainDir, breaking, world.indexer, extraHistory = git).rebuild()

                withClue("the binding survives: evidence gathered before a break authorizes nothing after it") {
                    world.idMap.retiredAt(extra, id).shouldBeNull()
                }
                withClue("and the advance rode the identical discarded token, so the range is still unconsumed") {
                    world.retirements.gitHead(extra) shouldBe "A"
                }
            }
        }
    }

    test("an observed root's FIRST pass still baselines - the epoch it opens IS this pass's own observation") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                // Production's own order: `serve()` installs the watcher BEFORE the first rebuild, so on EVERY watched
                // root the epoch opens - and revokes - on the very pass that must record the baseline. That revoke is
                // not a break; it is this pass's observation being born, and a proof derived by this same pass rides it
                // legitimately. Mint git under the PRE-open token instead and the boot silently baselines nothing.
                world.observe("extra")
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild()

                world.retirements.gitHead(extra) shouldBe "A"
            }
        }
    }

    test("a WATCHED root converges an offline git rm - the production boot, end to end") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                world.observe("extra")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id
                world.retirements.gitHead(extra) shouldBe "A"

                // Down it goes, the operator `git rm`s the page, and back it comes - watcher first, as always. The new
                // epoch opens over a tree the page is already missing from, so it witnessed the page NEVER and proves
                // nothing about it (the scoping rule). GIT is the only oracle left, and this is the headline case.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                world.restart()
                world.observe("extra")
                git.head = "B"
                git.deleted = setOf(rollback)
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild()

                world.idMap.retiredAt(extra, id).shouldNotBeNull().path shouldBe RootedPath(extra, rollback)
                world.limbo.count(extra) shouldBe 0
                world.retirements.gitHead(extra) shouldBe "B"
            }
        }
    }

    test("a baseline is recorded on first sight and proves nothing - pre-upgrade offline deletes stay in limbo") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                // Durable rows from a prior install, but the tree is empty NOW and there is no checkpoint yet: the
                // first sight has no range start to diff, so it records a baseline and reaps nothing (acceptance #23).
                val stranded = UuidV7IdProvider().next()
                world.idMap.bind(RootedPath(extra, TreePath.require("notes/gone.md")), stranded, materialized = false)
                world.retirements.gitHead(extra).shouldBeNull()

                val git = FakeHistory(head = "A")
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild()

                world.idMap.retiredAt(extra, stranded).shouldBeNull()
                world.idMap.livePathOf(stranded).shouldNotBeNull()
                world.limbo.holds(extra, stranded) shouldBe true
                world.retirements.gitHead(extra) shouldBe "A" // the baseline is recorded; the range starts here
            }
        }
    }

    test("a baseline is NOT recorded from an incomplete scan - G3 is uniform, the baseline included") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                // The walk comes back SHORT: a view with holes is not a corpus, so it grants no advance of any kind.
                world.builder(mainDir, IncompleteWalk(LocalContentStore(extraDir)), world.indexer, extraHistory = git).rebuild()
                world.retirements.gitHead(extra).shouldBeNull()
            }
        }
    }

    test("oldHead == postHead mints nothing and advances nothing - a checkpoint already at HEAD is idle") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                builder.rebuild() // baseline A
                builder.rebuild() // head still A, nothing new

                world.idMap.retiredBindings().shouldBeEmpty()
                world.retirements.gitHead(extra) shouldBe "A"
            }
        }
    }

    test("crash BEFORE the apply commits re-derives an equivalent proof from the unmoved checkpoint") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id // baseline A, page bound

                extraDir.resolve("notes/rollback.md").toFile().delete()
                git.head = "B"
                git.deleted = setOf(rollback)

                // The pre-commit crash: Recording OUTERMOST so it captures the args BEFORE CrashOnce throws. Nothing
                // reaches the transaction, so the checkpoint stays A and the binding is untouched.
                val crashed = Recording(CrashOnce(world.retirements))
                shouldThrowAny {
                    world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git, retirements = crashed).rebuild()
                }
                world.idMap.retiredAt(extra, id).shouldBeNull()
                world.retirements.gitHead(extra) shouldBe "A"
                val crashedProof = crashed.proofs.single { it.source == ProofSource.GIT && it.root == extra }

                // The reboot re-diffs the IDENTICAL range from the unmoved checkpoint and re-derives an equivalent
                // proof - same root, same source, same covers - which now applies and advances.
                world.restart()
                val recovery = Recording(world.retirements)
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git, retirements = recovery).rebuild()

                val reDerived = recovery.proofs.single { it.source == ProofSource.GIT && it.root == extra }
                reDerived.covers shouldBe crashedProof.covers
                world.idMap.retiredAt(extra, id).shouldNotBeNull()
                world.retirements.gitHead(extra) shouldBe "B"
            }
        }
    }

    test("crash AFTER the apply commits does not re-derive - the range is consumed") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val git = FakeHistory(head = "A")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id

                extraDir.resolve("notes/rollback.md").toFile().delete()
                git.head = "B"
                git.deleted = setOf(rollback)
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git).rebuild() // commit lands
                world.idMap.retiredAt(extra, id).shouldNotBeNull()
                world.retirements.gitHead(extra) shouldBe "B"

                // The reboot: the checkpoint moved with the deletions it proved, so the range oldHead..postHead is now
                // empty (both are B). No git proof and no advance are re-derived.
                world.restart()
                val recovery = Recording(world.retirements)
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git, retirements = recovery).rebuild()

                recovery.proofs.count { it.source == ProofSource.GIT && it.root == extra } shouldBe 0
                recovery.advances.count { it.root == extra } shouldBe 0
            }
        }
    }

    test("a history=off (NoOp) local root is never asked for currentHead/isAncestor/deletedIn - the enabled gate") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                // A spy that BLOWS UP on the three C4 members but reports `enabled = false`: the gate excludes it, so
                // a `history = off` root mints nothing and the members are never called (nothing throws).
                val offSpy = ThrowingSpyHistory(enabled = false)
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = offSpy).rebuild()
                world.retirements.gitHead(extra).shouldBeNull()
            }
        }
    }
})

/**
 * A deterministic [HistoryProvider] over a FAKE range: [head] is the current HEAD (read twice per pass by the
 * bracket), [ancestry] answers `isAncestor`, and [deleted] answers `deletedIn`. [moveMidPass] drives the G2 bracket
 * by returning a different head on the two reads of a single pass. The commit/read surface is inert.
 */
private class FakeHistory(var head: String? = "h1") : HistoryProvider {
    override val enabled = true
    var ancestry = true
    var deleted: Set<TreePath>? = emptySet()
    private val moving = ArrayDeque<String?>()

    /** The next pass's two head reads return [pre] then [post] - a head that moved mid-pass. */
    fun moveMidPass(pre: String?, post: String?) {
        moving.addAll(listOf(pre, post))
    }

    override fun currentHead(): String? = if (moving.isNotEmpty()) moving.removeFirst() else head
    override fun isAncestor(ancestor: String, descendant: String): Boolean = ancestry
    override fun deletedIn(from: String, to: String): Set<TreePath>? = deleted

    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit? = null
    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = emptyMap()
    override fun log(path: TreePath, limit: Int?): List<Commit> = emptyList()
    override fun diff(from: String, to: String, path: TreePath): FileDiff = FileDiff(from, to, path, "")
    override fun prepare() = Unit
    override fun gateCheck() = Unit
}

/** Reports git ENABLED per [enabled] but throws on every C4 member - proof the gate never reaches it. */
private class ThrowingSpyHistory(override val enabled: Boolean) : HistoryProvider {
    override fun currentHead(): String? = error("the gate must not ask an ineligible root for currentHead")
    override fun isAncestor(ancestor: String, descendant: String): Boolean = error("the gate must not ask isAncestor")
    override fun deletedIn(from: String, to: String): Set<TreePath>? = error("the gate must not ask deletedIn")

    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit? = null
    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = emptyMap()
    override fun log(path: TreePath, limit: Int?): List<Commit> = emptyList()
    override fun diff(from: String, to: String, path: TreePath): FileDiff = FileDiff(from, to, path, "")
    override fun prepare() = Unit
    override fun gateCheck() = Unit
}

/**
 * A [ContentStore] that fires [onScanEnd] the FIRST time `scan()` hands back - the instant a pass's witnessed/unread
 * evidence for this root is fixed. It models a watcher break arriving on another thread inside the (scan-end -> mint)
 * window: the evidence is already settled, and a stamp taken any later than the scan would fold the break's own revoke
 * into itself and match the reap it must forbid.
 */
private class BreakAtScanEnd(private val delegate: ContentStore, private val onScanEnd: () -> Unit) : ContentStore by delegate {
    private var fired = false

    override fun scan(): ScanResult {
        val result = delegate.scan()
        if (!fired) {
            fired = true
            onScanEnd()
        }
        return result
    }
}

/** A store whose walk comes back SHORT (`complete = false`) - a view with holes, which grants no delete authority. */
private class IncompleteWalk(private val delegate: ContentStore) : ContentStore by delegate {
    override fun scan(): ScanResult = delegate.scan().copy(complete = false)
}

/** The walk enumerates [vanished] but, once [armed], the READ of it produces no bytes - the UNREAD third answer. */
private class UnreadableAfterArming(private val delegate: ContentStore, private val vanished: TreePath) : ContentStore by delegate {
    var armed = false
    override fun readClassified(path: TreePath): StoreRead =
        if (armed && path == vanished) StoreRead.NoBytes else delegate.readClassified(path)
}

/** Throws on the FIRST [applyProofs] - exactly a pre-commit crash: nothing reaches the transaction. */
private class CrashOnce(private val delegate: RetirementRepository) : RetirementRepository by delegate {
    private var crashed = false
    override fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<RootedPageId>,
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> {
        if (!crashed) {
            crashed = true
            throw IllegalStateException("crash before the apply commits")
        }
        return delegate.applyProofs(proofs, witnessed, advances)
    }
}

/**
 * Fires [onApply] BETWEEN the mint and the apply - the window in which a watcher on another thread reports the break
 * that makes everything this pass derived worthless. The one revoke a same-pass proof must NOT survive.
 */
private class BreakOnApply(private val delegate: RetirementRepository, private val onApply: () -> Unit) :
    RetirementRepository by delegate {
    override fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<RootedPageId>,
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> {
        onApply()
        return delegate.applyProofs(proofs, witnessed, advances)
    }
}

/** Records the proofs/advances forwarded to [applyProofs] BEFORE delegating - so a crashed pass's mint is still captured. */
private class Recording(private val delegate: RetirementRepository) : RetirementRepository by delegate {
    val proofs = mutableListOf<AbsenceProof>()
    val advances = mutableListOf<GitCheckpointAdvance>()
    override fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<RootedPageId>,
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> {
        this.proofs += proofs
        this.advances += advances
        return delegate.applyProofs(proofs, witnessed, advances)
    }
}
