package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.livePathOf
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/**
 * **C2, end to end: the chunk that makes an ONLINE DELETE CONVERGE AGAIN.**
 *
 * After C0, nothing reaps at all - that is the safety floor, and its honest cost is that a page an operator really
 * did delete sits in limbo forever. This is where that cost is bought back, and it is bought with EVIDENCE:
 *
 * > **The event never carries authority. The unbroken OBSERVATION does, and the scan confirms.**
 *
 * The rows come in pairs, because a proof source is only as good as the cases it REFUSES. Every row that reaps has
 * a twin that does not, differing in exactly one fact: whether the observation had a hole in it.
 */
class ObservationEpochConvergenceTest : FunSpec({

    val extra = RootName.require("extra")
    val rollback = TreePath.require("notes/rollback.md")

    test("an ONLINE DELETE inside an unbroken epoch REAPS - the binding retires, the checkpoint and the search row go with it") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val id = builder.rebuild().byPath.getValue(RootedPath(extra, rollback)).id // the OPENING scan: it witnesses the page

                // The operator deletes it, under a running server that has been watching this tree the whole time.
                // THAT is the difference from every C0 row: nothing here is inferred from an empty directory - the
                // epoch READ this page, it has had an unbroken view of the tree ever since, and now the page is gone.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                builder.rebuild() // the CONFIRMATION scan

                withClue("the binding is RETIRED, not hard-deleted: /p/{root}/{id} answers 410, never the 404 that kills a citation") {
                    world.idMap.livePathOf(id).shouldBeNull()
                    world.idMap.retiredAt(extra, id).shouldNotBeNull().path shouldBe RootedPath(extra, rollback)
                }
                withClue("and the sinks act on the proof the apply transaction cashed - they never re-derive it") {
                    world.checkpoints.load().keys.contains(RootedPageId(extra, id)) shouldBe false
                    world.engine.indexedState().keys.map { it.id }.contains(id) shouldBe false
                }
                withClue("limbo is EMPTY: the row is not unknown any more, it is settled") {
                    world.limbo.count(extra) shouldBe 0
                }
            }
        }
    }

    test("RESTART into a decoy tree: the OPENING scan mints NO proofs, and looking twice does not promote it") {
        withAbsenceTrees { mainDir, extraDir ->
            repeat(20) { i -> writePage(extraDir, "h/page-$i.md", "# Page $i\n\nbody\n") }
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val corpus = world.builder(mainDir, world.extraStore(extraDir), world.indexer).rebuild()
                    .section(extra).pages.map { it.id }

                // The volume goes and three files turn up at the mount point - and the server RESTARTS into that.
                // A restart is a revocation: the in-memory epoch is gone, so the first scan is an OPENING one, and an
                // opening scan proves nothing. Retroactive authority here would reap 20 rows on the strength of a
                // tree this process has never laid eyes on, which is precisely the decoy hole.
                extraDir.toFile().deleteRecursively()
                Files.createDirectories(extraDir)
                repeat(3) { i -> writePage(extraDir, "decoy-$i.md", "# Decoy $i\n\nbody\n") }
                world.restart()
                world.observe("docs", "extra")

                val cold = world.builder(mainDir, world.extraStore(extraDir), world.indexer)
                cold.rebuild()
                withClue("an epoch must WITNESS a page before it may say the page is gone - it has witnessed none of these") {
                    world.idMap.retiredBindings().shouldBeEmpty()
                    world.limbo.count(extra) shouldBe 20
                }

                // ...and it does not earn the authority by LOOKING AGAIN, either. The second scan IS a confirmation
                // scan - but of the DECOY, whose witness set holds three files and not one page of the corpus.
                cold.rebuild()
                world.idMap.retiredBindings().shouldBeEmpty()
                world.checkpoints.load().keys.map { it.id } shouldContainAll corpus
            }
        }
    }

    test("RUNNING SERVER, tree swapped under it: the store's OWN probe rebinds, the epoch dies, and 20 rows survive") {
        withAbsenceTrees { mainDir, extraDir ->
            repeat(20) { i -> writePage(extraDir, "h/page-$i.md", "# Page $i\n\nbody\n") }
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                // ONE store for the process's life, exactly as production holds it - which is what lets the liveness
                // probe compare the tree it BOUND to against the tree that is there now. Nothing is faked here: the
                // break comes out of `rootLivenessProbe`'s own rebind arm, through the store's `onIdentityRebind`.
                val store = world.extraStore(extraDir)
                val builder = world.builder(mainDir, store, world.indexer)
                builder.rebuild() // the epoch witnesses all 20

                // A RENAME-IN swap (`mv site.new site`), which is what an atomic content release actually does - and
                // the ONE swap the store's own probe can see, because the new tree brings its own inode.
                //
                // It deliberately is NOT `rm -rf site && mkdir site`: on ext4 that REUSES the directory's inode, so
                // `fileKey` is unchanged and NO rebind fires. CI caught exactly that (this test failed on Linux and
                // passed on macOS). The store's identity probe is a HINT, not a detector - the fourth time this
                // feature has trusted `fileKey` past what it can bear. The same-inode swap is caught by the WATCHER's
                // key cancellation instead, and that is pinned in `ObservationBreakNativeTest` on the real platform.
                val fresh = Files.createDirectories(extraDir.resolveSibling("extra.new"))
                repeat(3) { i -> writePage(fresh, "decoy-$i.md", "# Decoy $i\n\nbody\n") }
                extraDir.toFile().deleteRecursively()
                Files.move(fresh, extraDir)
                builder.rebuild()

                withClue("the REBIND is what saved it - not some other break that happened to fire on the way past") {
                    world.reportedBreaks shouldContain BreakCause.IDENTITY_REBIND
                }
                withClue("a POPULATED replacement tree is a healthy root and a NEW UNIVERSE: it serves, and it proves nothing") {
                    world.availability.current().isAvailable(extra) shouldBe true
                    world.idMap.retiredBindings().shouldBeEmpty()
                    world.limbo.count(extra) shouldBe 20
                }
            }
        }
    }

    test("a delete STORM that overflows the queue does NOT reap - past the overflow bound, `rm -rf` and an unmount are the same") {
        withAbsenceTrees { mainDir, extraDir ->
            repeat(20) { i -> writePage(extraDir, "h/page-$i.md", "# Page $i\n\nbody\n") }
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                builder.rebuild() // the epoch witnesses all 20

                // The storm. The watcher's event queue overflows, and the JDK drops an UNKNOWN set of events - so
                // this observation now has a hole in it of unknown size. `world.broke` is the exact call
                // `FileWatcher` makes on `StandardWatchEventKinds.OVERFLOW`; a real JDK queue overflow cannot be
                // forced deterministically (inotify's bound is large and macOS's poller has no overflow at all), so
                // the row drives the watcher's OWN reporting call rather than a flaky flood.
                world.broke("extra", BreakCause.OVERFLOW)
                extraDir.resolve("h").toFile().deleteRecursively()
                builder.rebuild()

                withClue("NOTHING is reaped, and that is CORRECT: an unmount produces this exact pair of scans") {
                    world.idMap.retiredBindings().shouldBeEmpty()
                    world.idMap.bindings().count { it.path.root == extra } shouldBe 20
                }
                withClue("the tail of the storm lands in LIMBO - carried, 503-able, and waiting for real evidence") {
                    world.limbo.count(extra) shouldBe 20
                }
            }
        }
    }

    test("a SMALL delete inside the epoch reaps even while its neighbours were never witnessed - authority is per-BINDING") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val id = builder.rebuild().byPath.getValue(RootedPath(extra, rollback)).id

                // A row this epoch has never seen - a page on a submount that was down when the epoch opened. It is
                // the SCOPING rule's subject, and it must survive a pass that legitimately reaps its neighbour.
                val stranded = UuidV7IdProvider().next()
                world.idMap.bind(RootedPath(extra, TreePath.require("notes/never-seen.md")), stranded, materialized = false)

                extraDir.resolve("notes/rollback.md").toFile().delete()
                builder.rebuild()

                world.idMap.retiredAt(extra, id).shouldNotBeNull()
                withClue("the unwitnessed row is UNTOUCHED - a healthy epoch grants no authority over what it never read") {
                    world.idMap.livePathOf(stranded).shouldNotBeNull()
                    world.limbo.holds(extra, stranded) shouldBe true
                }
            }
        }
    }

    test("an INCOMPLETE scan (a subtree the walk could not see) mints no proofs - a view with holes is not a corpus") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val real = LocalContentStore(extraDir)
                world.builder(mainDir, real, world.indexer).rebuild() // the epoch witnesses the page, honestly

                // The page goes AND the walk goes short - which is the pairing that matters, because a scan that
                // cannot see the whole tree cannot tell "the page was deleted" from "the page is in the part I could
                // not read". Both look like this one.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                world.builder(mainDir, ShortWalk(real), world.indexer).rebuild()

                world.idMap.retiredBindings().shouldBeEmpty()
                world.limbo.count(extra) shouldBe 1
            }
        }
    }

    test("a MATERIALIZED page the epoch reaped and the operator restores RECLAIMS its own id - the permalink round-trips") {
        withAbsenceTrees { mainDir, extraDir ->
            val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val page = "---\nid: ${id.value}\ntitle: Rollback\n---\n\n# Rollback\n"
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", page)
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val builder = world.builder(mainDir, world.extraStore(extraDir), world.indexer)
                builder.rebuild()

                extraDir.resolve("notes/rollback.md").toFile().delete()
                builder.rebuild()
                world.idMap.retiredAt(extra, id).shouldNotBeNull()

                // It comes home: same root, same path, same id IN THE FILE - the one return that carries its own
                // evidence (C0.4). A reap is a TOMBSTONE, never a hard delete, and that is exactly what makes an
                // epoch's mistake recoverable rather than final.
                writePage(extraDir, "notes/rollback.md", page)
                builder.rebuild().byPath.getValue(RootedPath(extra, rollback)).id shouldBe id
                world.idMap.retiredAt(extra, id).shouldBeNull()
            }
        }
    }

    test("an UNMATERIALIZED page the epoch reaped comes back as a NEW page - and its old permalink is a 410, never a 404") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n") // no `id:` - the id lives ONLY in id_map
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val builder = world.builder(mainDir, world.extraStore(extraDir), world.indexer)
                val id = builder.rebuild().byPath.getValue(RootedPath(extra, rollback)).id

                extraDir.resolve("notes/rollback.md").toFile().delete()
                builder.rebuild()

                // The honest cost of reaping an unmaterialized page, stated rather than hidden: the file carried no
                // id, so a file reappearing at that path is observationally identical to a BRAND NEW page there, and
                // we do not guess. It mints fresh. What the design does promise is that the old permalink ANNOUNCES
                // itself - the tombstone stands, `/p/{root}/{oldId}` is 410 Gone, and no agent is told its citation was
                // never real. (A hard delete would have made it a 404. That is the whole reason retirement is soft.)
                writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
                val healed = builder.rebuild()

                healed.byPath.getValue(RootedPath(extra, rollback)).id shouldNotBe id
                world.idMap.retiredAt(extra, id).shouldNotBeNull().path shouldBe RootedPath(extra, rollback)
                world.idMap.livePathOf(id).shouldBeNull()
            }
        }
    }
})

/**
 * A store whose walk comes back SHORT and says so - the `complete = false` contract, which `LocalContentStore` now
 * answers for real when a child can be NAMED but not STAT-ed (a `chmod r--` directory: the names still list, and
 * nothing under them opens). Faked here because the permission drop is a POSIX-only, root-sensitive setup that says
 * nothing extra about the DOMAIN rule under test - the real one is pinned in `LocalContentStoreScanNativeTest`.
 */
private class ShortWalk(private val delegate: ContentStore) : ContentStore by delegate {
    override fun scan(): ScanResult {
        val real = delegate.scan()
        return real.copy(files = real.files.filterNot { it.path.value.startsWith("notes/") }, complete = false)
    }
}
