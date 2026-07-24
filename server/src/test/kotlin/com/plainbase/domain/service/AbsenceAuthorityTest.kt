package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.livePathOf
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/**
 * THE SAFETY FLOOR (C0). One rule, driven at every place the tree used to break it:
 *
 * > **A scan proves the pages it READ. It does not prove the pages it did not read are DELETED.**
 *
 * These rows are the data-loss class from the review ledger, reproduced as tests. Each one used to destroy
 * durable state; each one now destroys nothing, because in C0 there are ZERO proof sources and an
 * [AbsenceProof] is the only thing that can authorize a deletion. That is not a policy the code checks - it is
 * a property of the TYPE, and the way to see it is that the ONLY tests here that reap anything are the ones
 * that construct a proof BY HAND.
 *
 * The honest cost is pinned too (`a legitimate delete does NOT converge`): C0 buys safety by refusing to guess,
 * and C2/C4 buy convergence back with evidence.
 */
class AbsenceAuthorityTest : FunSpec({

    val extra = RootName.require("extra")

    // ---- A1: the handbook decoy, and its blast radius --------------------------------------------------

    test("the handbook decoy: a 3-page scan of a DIFFERENT tree reaps NOTHING from the 497 rows it cannot see") {
        withAbsenceTrees { mainDir, handbookDir ->
            // The real corpus: 500 pages, indexed, durable rows written for every one of them.
            repeat(500) { i -> writePage(handbookDir, "h/page-$i.md", "# Page $i\n\nbody\n") }
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            AbsenceWorld(mainDir, handbookDir).use { world ->
                val warm = world.builder(mainDir, LocalContentStore(handbookDir), world.indexer).rebuild()
                val corpus = warm.section(extra).pages.map { it.id }
                corpus.size shouldBe 500

                // THE DECOY. The volume is gone; a container runtime (or a restore that has not finished, or an
                // operator) has left THREE files at the mount point. Every path predicate passes, the scan is
                // COMPLETE, the tree is readable - and it is not the corpus. Under `drafts.isNotEmpty()` those
                // three files bought full delete authority over the other 497 rows.
                handbookDir.toFile().deleteRecursively()
                Files.createDirectories(handbookDir)
                repeat(3) { i -> writePage(handbookDir, "decoy-$i.md", "# Decoy $i\n\nbody\n") }

                val cold = world.builder(mainDir, LocalContentStore(handbookDir), world.indexer)
                val snapshot = cold.rebuild()

                withClue("the three files it CAN read are served - a broken view still serves what it holds") {
                    snapshot.section(extra).pages.map { it.path.value }.size shouldBe 3
                }
                withClue("the id_map bindings ARE the permalinks: 497 pages that still exist must keep theirs") {
                    world.idMap.bindings().count { it.path.root == extra } shouldBe 503 // 500 + the 3 decoys
                    world.idMap.retiredBindings().shouldBeEmpty()
                }
                withClue("the checkpoint rows - the down-time-move alias fact - survive a view they were not in") {
                    world.checkpoints.load().keys.map { it.id } shouldContainAll corpus
                }
                withClue("and the search rows, which the sync listener deletes off the very same authority") {
                    world.engine.indexedState().keys.map { it.id } shouldContainAll corpus
                }
                withClue("every unaccounted row is in LIMBO: carried, not destroyed, and self-healing on return") {
                    world.limbo.count(extra) shouldBe 500
                }
            }
        }
    }

    test("partial restore: 40 pages of 1000 come back, and the other 960 rows survive it") {
        withAbsenceTrees { mainDir, extraDir ->
            repeat(1000) { i -> writePage(extraDir, "p/page-$i.md", "# Page $i\n\nbody\n") }
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer).rebuild()

                // The restore is still running. 40 files are back. A pass that reads "960 pages deleted" here
                // deletes them for real, and the restore then finishes into an index that has forgotten them.
                extraDir.toFile().deleteRecursively()
                Files.createDirectories(extraDir)
                repeat(40) { i -> writePage(extraDir, "p/page-$i.md", "# Page $i\n\nbody\n") }

                world.builder(mainDir, LocalContentStore(extraDir), world.indexer).rebuild()

                world.idMap.bindings().count { it.path.root == extra } shouldBe 1000
                world.idMap.retiredBindings().shouldBeEmpty()
                world.checkpoints.load().size shouldBe 1001 // the 1000 + main's page
                world.limbo.count(extra) shouldBe 960
            }
        }
    }

    test("limbo SELF-HEALS on reappearance: the restore finishes, and no code runs") {
        withAbsenceTrees { mainDir, extraDir ->
            repeat(10) { i -> writePage(extraDir, "p/page-$i.md", "# Page $i\n\nbody\n") }
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                // ONE builder: a RUNNING server watching the tree go and come back. (A restart into the outage is
                // the other case, and it is the decoy row above - there the root is marked and its section carried.)
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                builder.rebuild()
                val ids = world.idMap.bindings().filter { it.path.root == extra }.map { it.id }

                // The CONTENTS go, the mount point stays (its inode unchanged) - so this is a root that is
                // demonstrably still THERE and simply cannot show us its pages. Exactly the shape a half-finished
                // restore, a failed submount or an emptied tree arrives in, and the pass cannot tell them apart.
                extraDir.resolve("p").toFile().deleteRecursively()
                builder.rebuild()
                world.limbo.count(extra) shouldBe 10

                // The mount comes back, byte-for-byte. No operator ceremony, no reconcile, no restart.
                repeat(10) { i -> writePage(extraDir, "p/page-$i.md", "# Page $i\n\nbody\n") }
                val healed = builder.rebuild()

                world.limbo.count(extra) shouldBe 0
                withClue("the pages come back with the ids they left with - path-keyed identity, unbroken") {
                    healed.section(extra).pages.map { it.id } shouldContainAll ids
                }
            }
        }
    }

    // ---- A2: inode reuse, and the zero-page scan ------------------------------------------------------

    test("inode reuse + a ZERO-page scan reaps nothing - and 'this process saw the corpus once' is not evidence") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                // The pass sees the corpus with its own eyes. Under the old rule this ADMITTED the root to
                // `corpusSeen` forever - a snapshot from T, cashed at T+n - so a later zero-page scan of a
                // REPLACED tree (ext4 reuses a directory inode on delete+recreate) inherited full authority.
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val rollback = builder.rebuild().byPath.getValue(RootedPath(extra, TreePath.require("notes/rollback.md"))).id

                // Same process, same builder, same inode - and now an empty tree.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                extraDir.resolve("notes").toFile().delete()
                builder.rebuild()

                withClue("having once seen a corpus is not a licence to believe it is gone now") {
                    world.idMap.livePathOf(rollback) shouldBe RootedPath(extra, TreePath.require("notes/rollback.md"))
                    world.checkpoints.load().keys.map { it.id } shouldContainAll listOf(rollback)
                    world.engine.indexedState().keys.map { it.id } shouldContainAll listOf(rollback)
                }
            }
        }
    }

    // ---- THE HONEST COST, said out loud ----------------------------------------------------------------

    test("a legitimate delete on a root NOBODY IS WATCHING does not converge - it lands in limbo, and only evidence buys it back") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                // No `world.observe("extra")`, and that is the SUBJECT: with no watcher there is no observation, so
                // two scans with an `rm` between them are just two scans - the same pair a failed submount produces.
                // C2 does not weaken this row, it EXPLAINS it: the epoch is what an operator's delete converges
                // through (see `ObservationEpochConvergenceTest`), and an unwatched root has none to converge through.
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val rollback = builder.rebuild().byPath.getValue(RootedPath(extra, TreePath.require("notes/rollback.md"))).id

                extraDir.resolve("notes/rollback.md").toFile().delete()
                val snapshot = builder.rebuild()

                withClue("the page leaves the SNAPSHOT immediately - what does not happen is the durable reap") {
                    snapshot.section(extra).pages.shouldBeEmpty()
                }
                world.idMap.livePathOf(rollback).shouldNotBeNull()
                world.limbo.holds(extra, rollback) shouldBe true
            }
        }
    }

    // ---- A3 / C0.1: the unbindStale gate ---------------------------------------------------------------

    test("a copied id in ANOTHER root does NOT steal an UNWITNESSED, UNMATERIALIZED incumbent - root-scoping protects it") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "placeholder.md", "# P\n")
            // The incumbent carries NO id in its file, so its id_map row is the SOLE record it ever had one.
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val rollbackPath = RootedPath(extra, TreePath.require("notes/rollback.md"))
                val stolen = world.builder(mainDir, LocalContentStore(extraDir)).rebuild().byPath.getValue(rollbackPath).id
                world.idMap.find(rollbackPath)?.materialized shouldBe false

                // extra's disk is a broken view (an empty mount point). Someone pastes a copy of the page - with
                // the id in its frontmatter - into MAIN. Pre-flip the winner's key-complete bind HARD-DELETED extra's
                // row (a cross-root steal). Post-flip [bind] is root-scoped and cannot touch another root's binding, so
                // extra's sole record survives untouched - and main simply holds its own copy of the id.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                writePage(mainDir, "pasted.md", "---\nid: ${stolen.value}\ntitle: Pasted\n---\n\n# Pasted\n")

                val snapshot = world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                withClue("the unobservable incumbent KEEPS its binding - a bind in another root never removes it") {
                    world.idMap.bindingInRoot(extra, stolen)?.path shouldBe rollbackPath
                }
                withClue("...and main's copy simply holds the id in its OWN root - a legal per-root duplicate, no steal") {
                    snapshot.pageAt(RootedPageId(RootName.MAIN, stolen)).shouldNotBeNull().path.value shouldBe "pasted.md"
                    world.idMap.rootsHoldingId(stolen) shouldContainExactlyInAnyOrder listOf(RootName.MAIN, extra)
                }
                withClue("no cross-root contest is recorded, because per-root identity has none to record") {
                    world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().shouldBeEmpty()
                }
            }
        }
    }

    // The OTHER side of the gate, RE-DERIVED for per-root identity (C5). A MATERIALIZED incumbent's id lives in its
    // FILE, so a file elsewhere carrying that id may well BE this page, moved. Pre-flip a copy in a HIGHER-ranked root
    // could take the id from a down root - the "bounded residue" this row used to pin. Post-flip [bind] is ROOT-SCOPED:
    // it never removes another root's binding, so a copy cannot steal at all. The id simply becomes a legal per-root
    // duplicate - main holds its copy, extra keeps its own binding - and when the root returns BOTH pages are present,
    // BOTH witnessed, and there is no contest to adjudicate because nothing was ever stolen.
    test("a materialized copy in another root does NOT steal the id - the bind is root-scoped, so both roots keep it") {
        withAbsenceTrees { mainDir, extraDir ->
            val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val page = "---\nid: ${id.value}\ntitle: Rollback\n---\n\n# Rollback\n"
            writePage(mainDir, "placeholder.md", "# P\n")
            writePage(extraDir, "notes/rollback.md", page) // MATERIALIZED: the id is in the file
            AbsenceWorld(mainDir, extraDir).use { world ->
                val home = RootedPath(extra, TreePath.require("notes/rollback.md"))
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild().byPath.getValue(home).id shouldBe id

                // extra's file goes; a copy carrying the id lands in main. Pre-flip the winner's key-complete bind
                // hard-deleted extra's row - a cross-root steal. Post-flip the bind is root-scoped and never touches
                // another root's binding: main gets the id in its OWN root, extra's binding stands untouched.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                writePage(mainDir, "pasted.md", page)
                val snapshot = world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                snapshot.pageAt(RootedPageId(RootName.MAIN, id)).shouldNotBeNull().path.value shouldBe "pasted.md" // main's OWN id
                world.idMap.bindingInRoot(extra, id)?.path shouldBe home // extra's binding was never stolen
                // Both roots hold the id in the map - a legal per-root duplicate, no steal, no cross-root issue.
                world.idMap.rootsHoldingId(id) shouldContainExactlyInAnyOrder listOf(RootName.MAIN, extra)
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().shouldBeEmpty()
            }
        }
    }

    // ---- C0.4: tombstones ------------------------------------------------------------------------------

    test("an external id: change TOMBSTONES the displaced id - /p/{oldId} is 410-able, not a 404") {
        withAbsenceTrees { mainDir, extraDir ->
            val old = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val new = PageId.require("0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01")
            writePage(mainDir, "guides/deploy.md", "---\nid: ${old.value}\ntitle: Deploy\n---\n\n# Deploy\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val builder = world.builder(mainDir, LocalContentStore(extraDir))
                builder.rebuild().pageAt(RootedPageId(RootName.MAIN, old)).shouldNotBeNull().path.value shouldBe "guides/deploy.md"

                // The user edits the frontmatter. This is a DISPLACEMENT, not an absence: we are looking at the
                // file, and it no longer carries the old id. No proof is needed - but the old id must not simply
                // VANISH, or every citation to it degrades to "that page never existed".
                writePage(mainDir, "guides/deploy.md", "---\nid: ${new.value}\ntitle: Deploy\n---\n\n# Deploy\n")
                builder.rebuild().pageAt(RootedPageId(RootName.MAIN, new)).shouldNotBeNull().path.value shouldBe "guides/deploy.md"

                world.idMap.livePathOf(old).shouldBeNull()
                val tombstone = world.idMap.retiredAt(RootName.MAIN, old)
                tombstone.shouldNotBeNull()
                tombstone.path shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/deploy.md"))
            }
        }
    }

    test("path reuse preserves the old permalink: a DIFFERENT page at the same path takes a fresh id, the tombstone stands") {
        withAbsenceTrees { mainDir, extraDir ->
            val old = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            writePage(mainDir, "guides/deploy.md", "---\nid: ${old.value}\ntitle: Deploy\n---\n\n# Deploy\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val builder = world.builder(mainDir, LocalContentStore(extraDir))
                builder.rebuild()

                // A page is deleted and a DIFFERENT one is later created at the same path. Under a `retired_at`
                // FLAG on id_map this collided with its own tombstone and destroyed it - so /p/{oldId} answered
                // 404, which is exactly the harm soft-retirement exists to prevent. Tombstones live OUTSIDE the
                // live key space, keyed by ID, so path reuse is a non-event.
                world.retirements.applyProofs(
                    listOf(
                        AbsenceProof(
                            root = RootName.MAIN,
                            source = ProofSource.OPERATOR,
                            observationId = world.retirements.observation(RootName.MAIN),
                            bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                            covers = setOf(BindingRef(TreePath.require("guides/deploy.md"), old)),
                        ),
                    ),
                    // A retirement manufactured for SETUP: no scan ran, so this observation saw nothing. (And OPERATOR
                    // is not an INFERENCE from not-seeing, so no witness could refute it anyway - see ProofSource.)
                    witnessed = emptySet(), unavailableNow = { emptySet() },
                ) shouldBe setOf(RootedPageId(RootName.MAIN, old))

                writePage(mainDir, "guides/deploy.md", "# A totally different page\n\nbody\n")
                val snapshot = builder.rebuild()

                snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("guides/deploy.md"))).id shouldNotBe old
                withClue("/p/{oldId} must still answer 410 GONE, naming where the page used to live") {
                    world.idMap.retiredAt(RootName.MAIN, old)?.path shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/deploy.md"))
                }
            }
        }
    }

    test("a copied file carrying a RETIRED id does NOT steal it - a tombstoned id is reserved forever") {
        withAbsenceTrees { mainDir, extraDir ->
            val retiredId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            writePage(mainDir, "guides/deploy.md", "---\nid: ${retiredId.value}\ntitle: Deploy\n---\n\n# Deploy\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val builder = world.builder(mainDir, LocalContentStore(extraDir))
                builder.rebuild()
                world.retirements.applyProofs(
                    listOf(
                        AbsenceProof(
                            root = RootName.MAIN,
                            source = ProofSource.OPERATOR,
                            observationId = world.retirements.observation(RootName.MAIN),
                            bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                            covers = setOf(BindingRef(TreePath.require("guides/deploy.md"), retiredId)),
                        ),
                    ),
                    witnessed = emptySet(), unavailableNow = { emptySet() }, // setup: no scan ran, and OPERATOR is not refutable regardless
                )
                mainDir.resolve("guides/deploy.md").toFile().delete()

                // A restore from an old backup, or a paste. It carries the retired id in its frontmatter. If it
                // TOOK the id, /p/{id} would silently redirect to the WRONG DOCUMENT - strictly worse than the
                // 404 the tombstone prevents, because a dead link announces itself and a live wrong one does not.
                writePage(mainDir, "restored/copy.md", "---\nid: ${retiredId.value}\ntitle: Copy\n---\n\n# Copy\n")
                val snapshot = builder.rebuild()

                snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("restored/copy.md"))).id shouldNotBe retiredId
                world.idMap.livePathOf(retiredId).shouldBeNull()
                world.idMap.retiredAt(RootName.MAIN, retiredId).shouldNotBeNull()
            }
        }
    }

    test("a page returning to its OWN (root, path) RECLAIMS its retired id - the one return that carries its own evidence") {
        withAbsenceTrees { mainDir, extraDir ->
            val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val page = "---\nid: ${id.value}\ntitle: Deploy\n---\n\n# Deploy\n"
            writePage(mainDir, "guides/deploy.md", page)
            AbsenceWorld(mainDir, extraDir).use { world ->
                val builder = world.builder(mainDir, LocalContentStore(extraDir))
                builder.rebuild()
                world.retirements.applyProofs(
                    listOf(
                        AbsenceProof(
                            root = RootName.MAIN,
                            source = ProofSource.OPERATOR,
                            observationId = world.retirements.observation(RootName.MAIN),
                            bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                            covers = setOf(BindingRef(TreePath.require("guides/deploy.md"), id)),
                        ),
                    ),
                    witnessed = emptySet(), unavailableNow = { emptySet() }, // setup: no scan ran, and OPERATOR is not refutable regardless
                )
                world.idMap.retiredAt(RootName.MAIN, id).shouldNotBeNull()

                // It came home. Same root, same path, same id in the file. That is the ONE case a return can be
                // told from a paste, so it is the only case we un-retire on.
                writePage(mainDir, "guides/deploy.md", page)
                builder.rebuild().pageAt(RootedPageId(RootName.MAIN, id)).shouldNotBeNull().path.value shouldBe "guides/deploy.md"

                world.idMap.retiredAt(RootName.MAIN, id).shouldBeNull()
                world.idMap.livePathOf(id) shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/deploy.md"))
            }
        }
    }

    test("an UNMATERIALIZED binding survives a wrong reap - the case where a hard delete would have been forever") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "placeholder.md", "# P\n")
            writePage(extraDir, "notes/loose.md", "# Loose\n\nno id in the file at all\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                val loose = RootedPath(extra, TreePath.require("notes/loose.md"))
                val id = world.builder(mainDir, LocalContentStore(extraDir)).rebuild().byPath.getValue(loose).id
                world.idMap.find(loose)?.materialized shouldBe false

                extraDir.toFile().deleteRecursively()
                Files.createDirectories(extraDir)
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                withClue("this row is the ONLY record the path ever had this id: hard-delete it and the permalink dies with it") {
                    world.idMap.livePathOf(id) shouldBe loose
                }
            }
        }
    }

    // ---- Freshness, and the ONE transaction boundary ---------------------------------------------------

    test("a revocation committed BEFORE the reap transaction yields ZERO deletes") {
        withAbsenceTrees { mainDir, extraDir ->
            val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            writePage(mainDir, "guides/deploy.md", "---\nid: ${id.value}\ntitle: Deploy\n---\n\n# Deploy\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                // A proof is minted from an observation... and then the observation is REVOKED (a restart, a
                // watcher break, a rebind - all the same fact: we are no longer looking at what we were looking
                // at). Revocation is a write to the SAME app DB the reap re-reads inside its own transaction, so
                // the compare fails and the licence is worth nothing.
                val proof = AbsenceProof(
                    root = RootName.MAIN,
                    source = ProofSource.EPOCH,
                    observationId = world.retirements.observation(RootName.MAIN),
                    bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                    covers = setOf(BindingRef(TreePath.require("guides/deploy.md"), id)),
                )
                world.retirements.revoke(RootName.MAIN)

                world.retirements.applyProofs(listOf(proof), witnessed = emptySet(), unavailableNow = { emptySet() }).shouldBeEmpty()
                world.idMap.livePathOf(id) shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/deploy.md"))
                world.idMap.retiredAt(RootName.MAIN, id).shouldBeNull()
            }
        }
    }

    test("a proof minted for root A cannot retire anything in root B - no cross-root replay") {
        withAbsenceTrees { mainDir, extraDir ->
            // The SAME relative path and the SAME id CAN now live in both roots (UNIQUE(id, root)), so the replay this
            // guards against is a proof for ONE root whose (path, id) pair names a binding that lives in ANOTHER.
            // BindingRef carries no root; `proof.root` is the only thing standing between it and root B's row.
            val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            writePage(mainDir, "placeholder.md", "# P\n")
            writePage(extraDir, "notes/rollback.md", "---\nid: ${id.value}\ntitle: Rollback\n---\n\n# Rollback\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                val forRootA = AbsenceProof(
                    root = RootName.MAIN, // minted for MAIN...
                    source = ProofSource.EPOCH,
                    observationId = world.retirements.observation(RootName.MAIN),
                    bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                    covers = setOf(BindingRef(TreePath.require("notes/rollback.md"), id)), // ...but naming extra's binding
                )

                world.retirements.applyProofs(listOf(forRootA), witnessed = emptySet(), unavailableNow = { emptySet() }).shouldBeEmpty()
                world.idMap.livePathOf(id) shouldBe RootedPath(extra, TreePath.require("notes/rollback.md"))
            }
        }
    }

    // R11 (the §5.2 WEDGE-FIX): a live X in root B must NOT refute root A's legitimate retirement of X. Pre-flip the
    // witness was GLOBAL by bare id, so B holding X live wedged `(A, X)` into perpetual 503 limbo with the git range
    // consumed. Fixed at the SIGNATURE: `AbsenceProof.survives(Set<RootedPageId>)`, so only `(A, X)` in the witness
    // refutes `(A, X)`'s retirement, and `(B, X)` never does.
    test("a live X in root B does NOT refute root A's retirement of X - the per-root witness (R11 wedge-fix)") {
        withAbsenceTrees { mainDir, extraDir ->
            val x = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val page = "---\nid: ${x.value}\ntitle: X\n---\n\n# X\n"
            writePage(mainDir, "guides/a.md", page) // root A (main) holds X live
            writePage(extraDir, "notes/b.md", page) // root B (extra) holds the SAME X live - legal per-root
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                // A legitimate INFERRED absence for A's copy, applied with the witness carrying B's live observation of
                // X - `(extra, X)`, NOT `(main, X)`. Under the global witness B's live X would refute this and nothing
                // would reap; under the per-root witness `(extra, X)` does not match `(main, X)`, so A's retire lands.
                val proof = AbsenceProof(
                    root = RootName.MAIN,
                    source = ProofSource.EPOCH,
                    observationId = world.retirements.observation(RootName.MAIN),
                    bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                    covers = setOf(BindingRef(TreePath.require("guides/a.md"), x)),
                )

                world.retirements.applyProofs(
                    listOf(proof),
                    witnessed = setOf(RootedPageId(extra, x)),
                    unavailableNow = { emptySet() },
                ) shouldBe setOf(RootedPageId(RootName.MAIN, x))

                world.idMap.retiredAt(RootName.MAIN, x).shouldNotBeNull() // A tombstoned...
                world.idMap.bindingInRoot(extra, x)?.path shouldBe RootedPath(extra, TreePath.require("notes/b.md")) // ...B still LIVE
            }
        }
    }

    test("SEEING the page refutes an INFERRED absence, and refutes a CAUSED one NOT AT ALL - the fault line is the source") {
        withAbsenceTrees { mainDir, extraDir ->
            val id = PageId.require("0197c4d5-1a2b-7c3d-8e4f-5a6b7c8d9e01")
            writePage(mainDir, "guides/deploy.md", "---\nid: ${id.value}\ntitle: Deploy\n---\n\n# Deploy\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()
                val binding = BindingRef(TreePath.require("guides/deploy.md"), id)
                fun proofFrom(source: ProofSource) = AbsenceProof(
                    root = RootName.MAIN,
                    source = source,
                    observationId = world.retirements.observation(RootName.MAIN),
                    bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                    covers = setOf(binding),
                )

                // The witness says we READ this id. For an EPOCH proof that is a CONTRADICTION: the absence was
                // INFERRED from a gap in what we observed, and the page turns out to be one of the things we observed.
                withClue("an INFERRED absence is a conclusion from NOT SEEING, so seeing refutes it") {
                    world.retirements.applyProofs(
                        listOf(proofFrom(ProofSource.EPOCH)),
                        witnessed = setOf(RootedPageId(RootName.MAIN, id)),
                        unavailableNow = { emptySet() },
                    ).shouldBeEmpty()
                    world.idMap.retiredAt(RootName.MAIN, id).shouldBeNull()
                }

                // ...and for an OPERATOR proof it is NOT a contradiction, because that proof never claimed to have
                // inferred anything. A human read the exact reap set and signed it. If a witness could veto this, then
                // a stale COPY of a page an operator deliberately deleted would block the delete FOREVER - and
                // `reconcile` would be refused by the very copy it was run to resolve. The same holds for API_DELETE:
                // "we CAUSED this" is not an observation, so no observation can refute it.
                withClue("a CAUSED or ACCEPTED absence is not an inference, so no amount of looking can refute it") {
                    world.retirements.applyProofs(
                        listOf(proofFrom(ProofSource.OPERATOR)),
                        witnessed = setOf(RootedPageId(RootName.MAIN, id)),
                        unavailableNow = { emptySet() },
                    ) shouldBe
                        setOf(RootedPageId(RootName.MAIN, id))
                    world.idMap.retiredAt(RootName.MAIN, id).shouldNotBeNull()
                }
            }
        }
    }

    test("a proof whose binding no longer carries that id retires NOTHING - the page at that path is someone else's now") {
        withAbsenceTrees { mainDir, extraDir ->
            val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val other = PageId.require("0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01")
            writePage(mainDir, "guides/deploy.md", "---\nid: ${id.value}\ntitle: Deploy\n---\n\n# Deploy\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()
                val proof = AbsenceProof(
                    root = RootName.MAIN,
                    source = ProofSource.EPOCH,
                    observationId = world.retirements.observation(RootName.MAIN),
                    bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                    covers = setOf(BindingRef(TreePath.require("guides/deploy.md"), other)), // a stale (path, id) pair
                )

                world.retirements.applyProofs(listOf(proof), witnessed = emptySet(), unavailableNow = { emptySet() }).shouldBeEmpty()
                world.idMap.livePathOf(id) shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/deploy.md"))
            }
        }
    }

    // ---- The bind gate, at the repository boundary ------------------------------------------------------

    test("bind REFUSES a retired id for another page of the SAME root - the tombstone reservation is per-root (C5)") {
        withAbsenceTrees { mainDir, extraDir ->
            AbsenceWorld(mainDir, extraDir).use { world ->
                val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
                val home = RootedPath(RootName.MAIN, TreePath.require("guides/deploy.md"))
                world.idMap.bind(home, id, materialized = true) shouldBe BindOutcome.Bound
                world.retirements.applyProofs(
                    listOf(
                        AbsenceProof(
                            root = RootName.MAIN,
                            source = ProofSource.OPERATOR,
                            observationId = world.retirements.observation(RootName.MAIN),
                            bindingEpoch = world.retirements.bindingEpoch(RootName.MAIN),
                            covers = setOf(BindingRef(home.path, id)),
                        ),
                    ),
                    witnessed = emptySet(), unavailableNow = { emptySet() }, // setup: no scan ran, and OPERATOR is not refutable regardless
                )

                // A thief in the SAME root (main) is refused: main's tombstone reserves the id for the page that earned it.
                val thief = RootedPath(RootName.MAIN, TreePath.require("copy.md"))
                world.idMap.bind(thief, id, materialized = true) shouldBe BindOutcome.Refused(id, heldBy = home, retired = true)
                withClue("a REFUSED bind writes NOTHING - the claimant is left to mint fresh, and nothing is destroyed") {
                    world.idMap.find(thief).shouldBeNull()
                    world.idMap.retiredAt(RootName.MAIN, id).shouldNotBeNull()
                }
                // A page in ANOTHER root is NOT refused: the tombstone is root-scoped and reserves the id for MAIN only
                // (post-flip UNIQUE(id, root) legalizes the same id in a different root). extra's own claim stands.
                val foreign = RootedPath(extra, TreePath.require("copy.md"))
                world.idMap.bind(foreign, id, materialized = true) shouldBe BindOutcome.Bound
                world.idMap.bindingInRoot(extra, id)?.path shouldBe foreign

                world.idMap.bind(home, id, materialized = true) shouldBe BindOutcome.Bound // ...and MAIN's page may come home
                world.idMap.retiredAt(RootName.MAIN, id).shouldBeNull()
            }
        }
    }

    // ---- C4: the git-checkpoint advance rides the SAME transaction, behind the SAME compare -------------

    test("an advances-only call (no proofs) still runs the transaction and lands the checkpoint - the widened early return") {
        withAbsenceTrees { mainDir, extraDir ->
            AbsenceWorld(mainDir, extraDir).use { world ->
                val token = world.retirements.observation(RootName.MAIN)
                val epoch = world.retirements.bindingEpoch(RootName.MAIN)
                world.retirements.gitHead(RootName.MAIN).shouldBeNull()

                // A baseline advance carries NO proofs by construction. The old `if (proofs.isEmpty()) return` skipped
                // the transaction and silently dropped it; the widened guard (`proofs.isEmpty() && advances.isEmpty()`)
                // lets it through.
                world.retirements.applyProofs(
                    proofs = emptyList(),
                    witnessed = emptySet(),
                    unavailableNow = { emptySet() },
                    advances = listOf(GitCheckpointAdvance(RootName.MAIN, token, epoch, "deadbeef")),
                ).shouldBeEmpty()

                world.retirements.gitHead(RootName.MAIN) shouldBe "deadbeef"
            }
        }
    }

    test("an advance whose token matches lands; one whose token was revoked does not - the same compare a proof rides") {
        withAbsenceTrees { mainDir, extraDir ->
            AbsenceWorld(mainDir, extraDir).use { world ->
                val minted = world.retirements.observation(RootName.MAIN)
                world.retirements.applyProofs(
                    emptyList(),
                    emptySet(),
                    unavailableNow = { emptySet() },
                    advances = listOf(GitCheckpointAdvance(RootName.MAIN, minted, world.retirements.bindingEpoch(RootName.MAIN), "aaaa")),
                )
                world.retirements.gitHead(RootName.MAIN) shouldBe "aaaa"

                // Revoke, then present an advance minted under the STALE token: discarded, the checkpoint holds.
                world.retirements.revoke(RootName.MAIN)
                world.retirements.applyProofs(
                    emptyList(),
                    emptySet(),
                    unavailableNow = { emptySet() },
                    advances = listOf(GitCheckpointAdvance(RootName.MAIN, minted, world.retirements.bindingEpoch(RootName.MAIN), "bbbb")),
                )
                world.retirements.gitHead(RootName.MAIN) shouldBe "aaaa"

                // ...and a FRESH-token advance lands.
                val fresh = world.retirements.observation(RootName.MAIN)
                world.retirements.applyProofs(
                    emptyList(),
                    emptySet(),
                    unavailableNow = { emptySet() },
                    advances = listOf(GitCheckpointAdvance(RootName.MAIN, fresh, world.retirements.bindingEpoch(RootName.MAIN), "cccc")),
                )
                world.retirements.gitHead(RootName.MAIN) shouldBe "cccc"
            }
        }
    }

    test("gitHead round-trips through upsert - a later advance overwrites the recorded head") {
        withAbsenceTrees { mainDir, extraDir ->
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.retirements.gitHead(RootName.MAIN).shouldBeNull()
                val token = world.retirements.observation(RootName.MAIN)
                world.retirements.applyProofs(
                    emptyList(),
                    emptySet(),
                    unavailableNow = { emptySet() },
                    advances = listOf(GitCheckpointAdvance(RootName.MAIN, token, world.retirements.bindingEpoch(RootName.MAIN), "1111")),
                )
                world.retirements.gitHead(RootName.MAIN) shouldBe "1111"
                world.retirements.applyProofs(
                    emptyList(),
                    emptySet(),
                    unavailableNow = { emptySet() },
                    advances = listOf(GitCheckpointAdvance(RootName.MAIN, token, world.retirements.bindingEpoch(RootName.MAIN), "2222")),
                )
                world.retirements.gitHead(RootName.MAIN) shouldBe "2222"
            }
        }
    }
})
