package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The N-root runtime (ADR-0011 D5): what the server does when a root is THERE, when it GOES AWAY, and when it was
 * never there to begin with.
 *
 * The single invariant every row here serves: **a root that is not serving never reaches the wire as a 404, a
 * `page_deleted`, a `content_unreadable` or a false SUCCESS — and never destroys durable state on the way.** A 404
 * tells an agent the page is GONE and it should drop its citations; the truth is that a disk is unmounted and the
 * content is coming back. Those are not the same answer, and the difference is the whole chunk.
 */
class MultiRootRuntimeTest : FunSpec({

    /** Two real roots, `main` and an extra, under ONE temp parent - so a row that RENAMES a root aside still cleans up. */
    fun twoRoots(seedExtra: Boolean = true, block: (Path, Path) -> Unit) {
        val parent = Files.createTempDirectory("pb-mr")
        try {
            val main = Files.createDirectory(parent.resolve("main-root"))
            val extra = Files.createDirectory(parent.resolve("extra-root"))
            seedPage(main, "guides/deploy.md", "Deploy")
            if (seedExtra) seedPage(extra, "notes/rollback.md", "Rollback")
            block(main, extra)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    suspend fun io.ktor.server.testing.ApplicationTestBuilder.treeRoots() =
        Json.parseToJsonElement(client.get("/api/v1/tree").bodyAsText()).jsonObject.getValue("roots").jsonArray

    suspend fun io.ktor.server.testing.ApplicationTestBuilder.healthRoots() =
        Json.parseToJsonElement(client.get("/healthz").bodyAsText()).jsonObject.getValue("roots").jsonArray

    fun pageIdIn(harness: MultiRootRestHarness, root: String, path: String): PageId =
        harness.builder.current.byPath.getValue(
            com.plainbase.domain.root.RootedPath(RootName.require(root), TreePath.require(path)),
        ).id

    // ---- both roots served ------------------------------------------------------------------------

    test("both roots serve: pages, tree entries in D7 order, and cross-root search hits") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                client.get("/api/v1/pages/by-path/main/guides/deploy").status shouldBe HttpStatusCode.OK
                withClue("the extra root's page is served under its OWN root segment") {
                    client.get("/api/v1/pages/by-path/extra/notes/rollback").status shouldBe HttpStatusCode.OK
                }
                treeRoots().map { it.jsonObject.getValue("root").jsonPrimitive.content } shouldContainExactly listOf("main", "extra")
                treeRoots().forEach { it.jsonObject.getValue("available").jsonPrimitive.content shouldBe "true" }

                val hits = Json.parseToJsonElement(client.get("/api/v1/search?q=body").bodyAsText())
                    .jsonObject.getValue("hits").jsonArray
                hits.map { it.jsonObject.getValue("root").jsonPrimitive.content }.toSet() shouldBe setOf("main", "extra")
            }
        }
    }

    // ---- the mid-run vanish: skip, carry, mark - and DELETE NOTHING -------------------------------

    test("an extra root vanishing mid-run is marked, its section is CARRIED, and no durable row is deleted for it") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")
                val idMapBefore = harness.idMap.bindings().size
                val checkpointsBefore = harness.checkpoints.load().size
                val engineBefore = harness.searchProvider.indexedState().keys

                extra.toFile().deleteRecursively()
                harness.builder.rebuild()

                withClue("the probe marked it - detection is never silent") {
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()
                }
                withClue("the section is CARRIED, so the page is still IN the snapshot (which is what stops the delete)") {
                    harness.builder.current.byId.containsKey(extraPage).shouldBeTrue()
                }
                withClue("the publication listeners may only delete what the pass SCANNED") {
                    harness.idMap.bindings() shouldHaveSizeOf idMapBefore
                    harness.checkpoints.load() shouldHaveSizeOf checkpointsBefore
                    harness.searchProvider.indexedState().keys shouldBe engineBefore
                }
                withClue("the OTHER root is entirely unaffected") {
                    client.get("/api/v1/pages/by-path/main/guides/deploy").status shouldBe HttpStatusCode.OK
                }
            }
        }
    }

    test("a read of a vanished root answers 503 root_unavailable with Retry-After - NEVER a 404") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")
                extra.toFile().deleteRecursively()
                harness.builder.rebuild()

                val response = client.get("/api/v1/pages/${extraPage.value}")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.errorCode() shouldBe "root_unavailable"
                withClue("an agent needs a retry hint, and the message must say the page is NOT gone") {
                    response.headers[HttpHeaders.RetryAfter] shouldBe "300"
                    response.bodyAsText() shouldContain "The page still exists - do not discard it"
                }
            }
        }
    }

    // ---- the exit boundary is per PORT, not per surface: git is the OTHER backend a vanishing root takes down ----

    test("a root that vanishes DURING history() is 503 root_unavailable and gets MARKED - never a 500 off a raw git failure") {
        twoRoots { main, extra ->
            // The availability GATE is a status, and a status cannot answer for a root that dies after it is read.
            // Here the root is still marked AVAILABLE when the request starts (nothing has probed it yet), so the
            // gate passes and the git call is what discovers the loss - the exact window nine rounds of
            // ContentStore work closed on reads and left open on history.
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra)), histories = { FailingGitReads }) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")
                extra.toFile().deleteRecursively() // the disk goes; nothing has marked it

                val response = client.get("/api/v1/pages/${extraPage.value}/history")

                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.errorCode() shouldBe "root_unavailable"
                withClue("marking and answering are separate obligations - an unmarked root keeps serving carried bytes") {
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()
                }
            }
        }
    }

    test("a root that vanishes DURING diff() takes the same exit boundary as history()") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra)), histories = { FailingGitReads }) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")
                extra.toFile().deleteRecursively()

                val response = client.get("/api/v1/pages/${extraPage.value}/diff?from=aaaaaaa&to=bbbbbbb")

                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.errorCode() shouldBe "root_unavailable"
                harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()
            }
        }
    }

    test("a genuine git failure on a LIVE root still surfaces as itself - a real fault is never laundered into 'root down'") {
        twoRoots { main, extra ->
            // The other side of the two-sided rule, and the one that makes the rule safe to have: the disk is FINE,
            // so this is a corrupt repo / an unknown flag / a broken binary. Answering 503 root_unavailable here
            // would be the mirror-image lie, and it would leave a perfectly healthy root permanently Unavailable.
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra)), histories = { FailingGitReads }) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")

                val response = client.get("/api/v1/pages/${extraPage.value}/history")

                response.status shouldBe HttpStatusCode.InternalServerError
                withClue("a live root that merely failed a git call must NOT be marked") {
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeTrue()
                }
            }
        }
    }

    test("the tree flips its entry to available:false with an EMPTY subtree - never the stale carried listing") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                extra.toFile().deleteRecursively()
                harness.builder.rebuild()

                val entry = treeRoots().single { it.jsonObject.getValue("root").jsonPrimitive.content == "extra" }.jsonObject
                entry.getValue("available").jsonPrimitive.content shouldBe "false"
                withClue("serving the carried listing would be exactly the stale serve the whole rule exists to stop") {
                    entry.getValue("tree").jsonObject.getValue("children").jsonArray.size shouldBe 0
                }
            }
        }
    }

    test("health lists the root as unavailable with its CAUSE token, and the top-level status stays ok") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                extra.toFile().deleteRecursively()
                harness.builder.rebuild()

                val body = Json.parseToJsonElement(client.get("/healthz").bodyAsText()).jsonObject
                withClue("a vanished EXTRA must not flip a k8s liveness probe into a restart loop") {
                    body.getValue("status").jsonPrimitive.content shouldBe "ok"
                }
                val entry = healthRoots().single { it.jsonObject.getValue("root").jsonPrimitive.content == "extra" }.jsonObject
                entry.getValue("available").jsonPrimitive.content shouldBe "false"
                entry.getValue("reason").jsonPrimitive.content shouldBe "vanished"
            }
        }
    }

    // The IDLE-root bound, end to end - the row every other vanish test above quietly assumes away by calling
    // `rebuild()` itself.
    //
    // The root is RENAMED, and that is the whole point rather than a stylistic choice: an `rm -rf` deletes the pages
    // first, and those child ENTRY_DELETEs schedule a rebuild whose probe marks the root - so it would pass against
    // a watcher that cannot see root loss AT ALL. A rename (an unmount, a `mv`) touches no child: no event is
    // raised, no rebuild is scheduled, and on Linux the JDK does not even invalidate the key (every inode is still
    // exactly where it was - only the PATH is gone). Nothing is left to notice it except the watcher's own
    // root-liveness probe. Undetected, this root serves stale 200s and `available: true` FOREVER - not a lag, a
    // broken invariant. So the test drives nothing, and waits.
    test("an IDLE root that vanishes is marked, 503s and reports unavailable - with NO write, NO rescan and NO manual rebuild") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra)), liveWatchers = true) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")

                Files.move(extra, extra.resolveSibling("${extra.fileName}-unmounted"))

                awaitUntil("an idle root's loss was never detected: it would keep serving carried bytes as available") {
                    !harness.availability.current().isAvailable(RootName.require("extra"))
                }
                val response = client.get("/api/v1/pages/${extraPage.value}")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.errorCode() shouldBe "root_unavailable"

                val entry = healthRoots().single { it.jsonObject.getValue("root").jsonPrimitive.content == "extra" }.jsonObject
                entry.getValue("available").jsonPrimitive.content shouldBe "false"
                withClue("the watcher NOTICED the loss; the loss itself is a vanished disk, and the operator's remedy follows the cause") {
                    entry.getValue("reason").jsonPrimitive.content shouldBe "vanished"
                }
                withClue("the OTHER root keeps serving - one lost root is never a corpus outage") {
                    client.get("/api/v1/pages/by-path/main/guides/deploy").status shouldBe HttpStatusCode.OK
                }
            }
        }
    }

    test("search DROPS hits from a root that is not serving (a live search must never surface stale content)") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                extra.toFile().deleteRecursively()
                harness.builder.rebuild()

                val hits = Json.parseToJsonElement(client.get("/api/v1/search?q=body").bodyAsText())
                    .jsonObject.getValue("hits").jsonArray
                hits.shouldNotBeEmpty()
                hits.map { it.jsonObject.getValue("root").jsonPrimitive.content }.toSet() shouldBe setOf("main")
            }
        }
    }

    test("unavailability is STICKY: restoring the path does NOT bring the root back without a restart") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                extra.toFile().deleteRecursively()
                harness.builder.rebuild()

                Files.createDirectories(extra)
                seedPage(extra, "notes/rollback.md", "Rollback")
                harness.builder.rebuild()

                withClue("a vanished root's scan and identity state cannot be trusted afterwards - restart is the remedy") {
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()
                }
            }
        }
    }

    // ---- DEGRADED CONVERGENCE is not an OUTAGE ----------------------------------------------------

    test("a root whose watcher cannot see its whole tree is AVAILABLE and honest - coverage partial, pages still 200") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra)), liveWatchers = true) { harness ->
                // The fact a real watcher reports when a subtree will not register (the inotify watch limit; a
                // `chmod 000` directory), recorded through the SAME holder `serve()` wires the callback into. The
                // watcher's own end of that wire - PARTIAL on a tree it cannot cover, WHOLE again on a retry that
                // can, and never an `onFailure` - is proven against a real WatchService in `MultiWatcherNativeTest`;
                // a chmod'd subtree HERE would also fail the root's SCAN, which is a different (pre-existing)
                // condition and would prove the wrong thing about the wire.
                harness.convergence.record(RootName.require("extra"), whole = false)

                val entry = healthRoots().single { it.jsonObject.getValue("root").jsonPrimitive.content == "extra" }.jsonObject
                withClue("a kernel watch limit is not an outage: the root is there and serves every byte it holds") {
                    entry.getValue("available").jsonPrimitive.content shouldBe "true"
                    entry["reason"] shouldBe null
                    client.get("/api/v1/pages/by-path/extra/notes/rollback").status shouldBe HttpStatusCode.OK
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeTrue()
                }
                withClue("...but the wire must SAY its convergence is degraded, or the operator never learns of it") {
                    entry.getValue("coverage").jsonPrimitive.content shouldBe "partial"
                }
                withClue("a fully-watched root says nothing at all - silence is whole coverage") {
                    healthRoots().single { it.jsonObject.getValue("root").jsonPrimitive.content == "main" }
                        .jsonObject["coverage"] shouldBe null
                }

                // NOT sticky, which is the whole difference from availability: the retry that re-registers the tree
                // clears it, with no restart - and a restart is precisely what could not fix it.
                harness.convergence.record(RootName.require("extra"), whole = true)
                healthRoots().single { it.jsonObject.getValue("root").jsonPrimitive.content == "extra" }
                    .jsonObject["coverage"] shouldBe null
            }
        }
    }

    // ---- EMPTY is not GONE, and GONE can look EMPTY -----------------------------------------------
    //
    // These two are the same wire-level input - an available root with no files in it - reached from opposite
    // causes, and the whole delete pipeline hangs off telling them apart. What separates them is not the CONTENT
    // (both are empty) but the TREE: the operator's `rm` leaves the SAME directory behind, an unmount leaves a
    // DIFFERENT one (the mount point) at the same path.

    test("an EXISTING but emptied root scans normally and its pages DO delete - the probe tells gone from empty") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")
                harness.searchProvider.indexedState().keys.contains(extraPage).shouldBeTrue()

                // Empty the root but LEAVE IT THERE - the SAME directory, contents gone: a genuine full-corpus
                // delete, and the one thing carry-forward must never mask.
                Files.walk(extra.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                harness.builder.rebuild()

                withClue("an existing empty directory is available - carry-forward must not mask a REAL delete") {
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeTrue()
                    harness.builder.current.byId.containsKey(extraPage).shouldBeFalse()
                    harness.searchProvider.indexedState().keys.contains(extraPage).shouldBeFalse()
                }
            }
        }
    }

    test("an UNMOUNTED root looks empty and is NOT: it is marked, carried, and nothing is deleted for it") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")
                val checkpointsBefore = harness.checkpoints.load().size
                val engineBefore = harness.searchProvider.indexedState().keys

                // Unmounting a volume AT the root leaves the MOUNT-POINT DIRECTORY behind: present, empty,
                // readable, executable, and a DIFFERENT tree at the same path. Reproduced exactly - move the
                // volume away, leave a fresh empty directory where it was mounted. Read as merely empty, the pass
                // takes delete authority over a disk that is not there and purges the root on an unplug.
                Files.move(extra, extra.resolveSibling("unmounted-volume"))
                Files.createDirectory(extra)
                harness.builder.rebuild()

                withClue("a DIFFERENT tree at the root's path is a lost root, not an emptied one") {
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()
                    harness.builder.current.byId.containsKey(extraPage).shouldBeTrue() // carried, not dropped
                }
                withClue("an unplugged disk deletes NOTHING: the listeners may only delete what the pass SCANNED") {
                    harness.checkpoints.load() shouldHaveSizeOf checkpointsBefore
                    harness.searchProvider.indexedState().keys shouldBe engineBefore
                }
            }
        }
    }

    // ---- the BOOT arm: never scanned, so only the id_map can answer -------------------------------

    test("a root unavailable SINCE BOOT: no watcher, health names it, and its persisted binding answers 503 - not 404") {
        twoRoots(seedExtra = false) { main, extra ->
            val missing = extra.resolve("gone-forever")
            val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            MultiRootRestHarness(listOf(testRoot("main", main), testRoot("extra", missing))).use { harness ->
                // The binding survives the outage (D16 never touches an unscanned-but-configured root's rows), which
                // is the ONLY reason the server can tell this page apart from one that never existed.
                harness.idMapOnly("extra", "notes/rollback.md", pageId)
                harness.boot()

                withClue("nothing to watch") { harness.watched shouldContainExactly listOf(RootName.MAIN) }
                harness.availability.current().unavailable.getValue(RootName.require("extra")).cause shouldBe
                    UnavailableCause.MISSING_AT_BOOT

                io.ktor.server.testing.testApplication {
                    application { plainbaseModule(harness.services) }
                    withClue("the page is in NO section - a snapshot-only lookup would 404 and tell an agent it is gone") {
                        harness.builder.current.byId.containsKey(pageId).shouldBeFalse()
                    }
                    val byId = client.get("/api/v1/pages/${pageId.value}")
                    byId.status shouldBe HttpStatusCode.ServiceUnavailable
                    byId.errorCode() shouldBe "root_unavailable"

                    val permalink = client.get("/p/${pageId.value}")
                    permalink.status shouldBe HttpStatusCode.ServiceUnavailable

                    val health = Json.parseToJsonElement(client.get("/healthz").bodyAsText())
                        .jsonObject.getValue("roots").jsonArray
                        .single { it.jsonObject.getValue("root").jsonPrimitive.content == "extra" }.jsonObject
                    health.getValue("reason").jsonPrimitive.content shouldBe "missing_at_boot"
                }
            }
        }
    }

    test("the FIRST publish under a boot-unavailable root deletes NOTHING for it (search rows and checkpoints survive)") {
        twoRoots(seedExtra = false) { main, extra ->
            val missing = extra.resolve("gone-forever")
            val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b")
            MultiRootRestHarness(listOf(testRoot("main", main), testRoot("extra", missing))).use { harness ->
                harness.idMapOnly("extra", "notes/rollback.md", pageId)
                harness.checkpoints.replace(
                    harness.checkpoints.load() +
                        (pageId to com.plainbase.domain.repository.PreviousUrl(RootName.require("extra"), TreePath.require("rollback"))),
                )
                harness.boot() // the first rebuild - the one that would purge

                withClue("a boot-arm root was never scanned, so it is in NO section - a wholesale replace would purge it") {
                    harness.checkpoints.load().containsKey(pageId).shouldBeTrue()
                    harness.idMap.pathOf(pageId).shouldNotBeNullAnd { it.root shouldBe RootName.require("extra") }
                }
            }
        }
    }

    // ---- a DETACHED root: its name is gone from roots{}, its rows are not ------------------------

    test("a DETACHED root (rows in the DB, name gone from roots{}) serves 404 on its permalink and boots cleanly") {
        twoRoots { main, extra ->
            val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5c")
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                harness.detachedRoot("archive", "old/thing.md", pageId)

                withClue(
                    "a detached root has no availability status to consult - 404 is the honest answer, and the boot WARN is its visibility",
                ) {
                    client.get("/p/${pageId.value}").status shouldBe HttpStatusCode.NotFound
                }
                withClue("health reports the CONFIGURED topology; a detached root is not part of it") {
                    healthRoots().map { it.jsonObject.getValue("root").jsonPrimitive.content } shouldContainExactly listOf("main", "extra")
                }
            }
        }
    }

    test("the first publish after a root is DETACHED does not delete its page_checkpoint rows") {
        twoRoots { main, extra ->
            val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5d")
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                harness.detachedRoot("archive", "old/thing.md", pageId)
                val before = harness.checkpoints.load().size

                harness.builder.rebuild()

                withClue(
                    "checkpoints are DURABLE state, and pruning a detached root's rows is a deliberate, backup-first " +
                        "operator act - never something a routine rebuild does behind their back",
                ) {
                    harness.checkpoints.load() shouldHaveSizeOf before
                    harness.checkpoints.load().containsKey(pageId).shouldBeTrue()
                }
            }
        }
    }

    // ---- the WRITE path: a root-gone condition never lies about what happened ---------------------

    test("a CREATE into a vanished root is 503 root_unavailable - NOT content_unreadable, and the dir is not recreated") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                extra.toFile().deleteRecursively()
                // NO rebuild: the root is physically gone but NOT YET MARKED - the unmarked window, where a status
                // check cannot help and only a probe at the failure can answer.

                val response = client.post("/api/v1/pages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"root":"extra","folder":"notes","title":"New"}""")
                }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                withClue("`content_unreadable` implies a transient file fault and invites a blind retry - it is a lie here") {
                    response.errorCode() shouldBe "root_unavailable"
                }
                withClue("a write must never RESURRECT a root that is gone") { Files.exists(extra).shouldBeFalse() }
                withClue("the write's own probe MARKED it - detection is never silent, even without a rebuild") {
                    harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()
                }
                withClue(
                    "the write-ahead mark is laid down BEFORE the store call, and the store ANSWERS A LOST ROOT BY " +
                        "THROWING - so a create that never touched disk must not leave a WRITING row behind, promising " +
                        "reconcile bytes that do not exist",
                ) {
                    harness.dirtyPages.all().shouldBeEmptyList()
                }
            }
        }
    }

    test("an EDIT into a vanished root is 503 root_unavailable - NEVER 409 page_deleted - and the journal is untouched") {
        twoRoots { main, extra ->
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val page = harness.builder.current.byPath.getValue(
                    com.plainbase.domain.root.RootedPath(RootName.require("extra"), TreePath.require("notes/rollback.md")),
                )
                // A REAL prior recovery record (an earlier WrittenButUnindexed save): the one thing that could still
                // recover those bytes. The failing write below marks the page dirty with ITS OWN hash before calling
                // the store, so a nothing-written outcome that forgets to undo the mark DESTROYS this row.
                val prior = com.plainbase.domain.repository.DirtyPage(
                    pageId = page.id,
                    path = com.plainbase.domain.root.RootedPath(RootName.require("extra"), page.path),
                    expectedHash = "prior-attempt-hash",
                    stage = com.plainbase.domain.repository.Stage.WRITING,
                )
                harness.dirtyPages.mark(prior.pageId, prior.path, prior.expectedHash, prior.stage)

                extra.toFile().deleteRecursively() // the unmarked window again

                val response = client.put("/api/v1/pages/${page.id.value}") {
                    contentType(ContentType.parse("text/markdown"))
                    header(HttpHeaders.IfMatch, "\"${page.contentHash}\"")
                    setBody("---\ntitle: Rollback\n---\n\n# Rollback\n\nedited.\n")
                }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                withClue("`page_deleted` is the buffer-discarding signal: a client throws the user's edit away on it") {
                    response.errorCode() shouldBe "root_unavailable"
                }
                withClue("nothing was written, so the journal must read EXACTLY as it did before the request") {
                    harness.dirtyPages.all() shouldContainExactly listOf(prior)
                }
            }
        }
    }

    test("a dirty-page journal row under a root that is not serving is SKIPPED, never CLEARED") {
        twoRoots { main, extra ->
            MultiRootRestHarness(listOf(testRoot("main", main), testRoot("extra", extra))).use { harness ->
                harness.boot()
                val page = harness.builder.current.byPath.getValue(
                    com.plainbase.domain.root.RootedPath(RootName.require("extra"), TreePath.require("notes/rollback.md")),
                )
                harness.dirtyPages.mark(
                    page.id,
                    com.plainbase.domain.root.RootedPath(RootName.require("extra"), page.path),
                    expectedHash = page.contentHash,
                    stage = com.plainbase.domain.repository.Stage.WRITING,
                )
                extra.toFile().deleteRecursively()
                harness.builder.rebuild() // marks it unavailable

                harness.index.writePipeline().reconcileDirtyPages()

                withClue(
                    "the journal row is an interrupted save's ONLY recovery record - forgetting it because the whole " +
                        "disk is missing destroys the one thing that could still recover the write",
                ) {
                    harness.dirtyPages.all().map { it.pageId } shouldContainExactly listOf(page.id)
                }
            }
        }
    }

    // ---- history is per-root topology, and the CAPABILITY FLAG has to agree with the commits beside it ----

    test("`git_enabled` answers for the PAGE's root: an extra root with history ON is not hidden by a main with it off") {
        twoRoots { main, extra ->
            // main: history off (the no-op provider). extra: a real, git-backed history. Reading the flag off MAIN -
            // as the route used to - tells a client "no history here" for a page whose history the server is holding
            // in the very same response, and would tell it the opposite lie in the mirrored config.
            val perRoot: (RootName) -> com.plainbase.domain.history.HistoryProvider = { root ->
                if (root == RootName.MAIN) NoOpHistoryProvider else EnabledStubHistory
            }
            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra)), histories = perRoot) { harness ->
                val extraPage = pageIdIn(harness, "extra", "notes/rollback.md")
                val mainPage = pageIdIn(harness, "main", "guides/deploy.md")

                fun flagOf(body: String) =
                    Json.parseToJsonElement(body).jsonObject.getValue("git_enabled").jsonPrimitive.content

                flagOf(client.get("/api/v1/pages/${extraPage.value}/history").bodyAsText()) shouldBe "true"
                withClue("and main still answers for ITSELF - the flag is per-root, not a server-wide constant") {
                    flagOf(client.get("/api/v1/pages/${mainPage.value}/history").bodyAsText()) shouldBe "false"
                }
            }
        }
    }

    // ---- approve/rebase stay INSIDE the root the proposal was filed, reviewed and gated against ----

    test("an approve of an EDIT whose page id was RE-AWARDED to another root NEVER writes into that other root") {
        twoRoots(seedExtra = false) { main, extra ->
            // A proposal row's root is stamped once, at propose time, and never moves. A page id DOES move: the D17
            // cross-root duplicate-id contest re-awards it to the higher-ranked root the moment that root claims the
            // same frontmatter id — WITHOUT moving any file. The proposal's own page is still exactly where it was,
            // merely re-minted, so "follow the id" does not follow the page: it walks the write onto a STRANGER'S file
            // in a repository the reviewing admin never saw. And `base_hash` is no backstop, because the corpus that
            // makes duplicate ids routine in the first place (D2: two checkouts of ONE repo) is byte-identical, so the
            // CAS matches and the wrong write lands clean.
            //
            // Both roots here hold the SAME bytes, exactly so this row fails if the pin is ever removed.
            val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b60"
            val body = "---\nid: $pageId\ntitle: Rollback\n---\n\n# Rollback\n\nbody.\n"
            Files.createDirectories(extra.resolve("notes"))
            Files.writeString(extra.resolve("notes/rollback.md"), body)

            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val id = PageId.require(pageId)
                harness.builder.current.byId.getValue(id).root shouldBe RootName.require("extra")
                val hash = harness.builder.current.byId.getValue(id).contentHash
                val proposal = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"operation":"edit","page_id":"$pageId","base_hash":"$hash",""" +
                            """"proposed_content":"---\nid: $pageId\ntitle: Rollback\n---\n\n# Rollback\n\nrevised.\n",""" +
                            """"rationale":"tighten it"}""",
                    )
                }
                proposal.status shouldBe HttpStatusCode.Created
                val proposalId = Json.parseToJsonElement(proposal.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content

                // `main` gets the same repo checked out into it and OUTRANKS `extra` (D7 declaration order), so the
                // contest hands it the id; extra's copy is re-minted. The proposal row still says `extra` — correctly,
                // because extra's file is the one it was proposed against and it has not moved an inch.
                Files.createDirectories(main.resolve("notes"))
                Files.writeString(main.resolve("notes/rollback.md"), body)
                harness.builder.rebuild()
                harness.builder.current.byId.getValue(id).root shouldBe RootName.MAIN

                val approved = client.post("/api/v1/changes/$proposalId/approve")

                withClue("the id is gone from the root this edit was approved against, so the target is gone: CONFLICTED") {
                    approved.status shouldBe HttpStatusCode.Conflict
                }
                withClue("the whole point: the approved bytes did NOT land in `main`, whose file nobody reviewed") {
                    Files.readString(main.resolve("notes/rollback.md")) shouldContain "body."
                }
                Files.readString(extra.resolve("notes/rollback.md")) shouldContain "body."
            }
        }
    }

    test("an approve whose STORED root is not serving is a 503 - never silently retargeted at whoever holds the id now") {
        twoRoots(seedExtra = false) { main, extra ->
            val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b61"
            val body = "---\nid: $pageId\ntitle: Rollback\n---\n\n# Rollback\n\nbody.\n"
            Files.createDirectories(extra.resolve("notes"))
            Files.writeString(extra.resolve("notes/rollback.md"), body)

            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val id = PageId.require(pageId)
                val hash = harness.builder.current.byId.getValue(id).contentHash
                val proposal = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"operation":"edit","page_id":"$pageId","base_hash":"$hash",""" +
                            """"proposed_content":"---\nid: $pageId\ntitle: Rollback\n---\n\n# Rollback\n\nrevised.\n",""" +
                            """"rationale":"tighten it"}""",
                    )
                }
                val proposalId = Json.parseToJsonElement(proposal.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content

                // main claims the id, and THEN the root the row was filed against goes away entirely.
                Files.createDirectories(main.resolve("notes"))
                Files.writeString(main.resolve("notes/rollback.md"), body)
                harness.builder.rebuild()
                extra.toFile().deleteRecursively()
                harness.builder.rebuild()
                harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()

                val approved = client.post("/api/v1/changes/$proposalId/approve")

                withClue("`extra` is down, so its proposals are undecidable - the row stays PENDING for its return (D5)") {
                    approved.status shouldBe HttpStatusCode.ServiceUnavailable
                }
                withClue("a 503 is the honest answer; writing into `main` instead would be a silent retarget") {
                    Files.readString(main.resolve("notes/rollback.md")) shouldContain "body."
                }
            }
        }
    }

    test("an ALREADY-DECIDED proposal on a downed root answers 409, not 503: reporting a decided row reads nothing off its disk") {
        twoRoots(seedExtra = false) { main, extra ->
            val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b62"
            val body = "---\nid: $pageId\ntitle: Rollback\n---\n\n# Rollback\n\nbody.\n"
            Files.createDirectories(extra.resolve("notes"))
            Files.writeString(extra.resolve("notes/rollback.md"), body)

            multiRootTest(listOf(testRoot("main", main), testRoot("extra", extra))) { harness ->
                val hash = harness.builder.current.byId.getValue(PageId.require(pageId)).contentHash
                val proposal = client.post("/api/v1/changes") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"operation":"edit","page_id":"$pageId","base_hash":"$hash",""" +
                            """"proposed_content":"---\nid: $pageId\ntitle: Rollback\n---\n\n# Rollback\n\nrevised.\n",""" +
                            """"rationale":"tighten it"}""",
                    )
                }
                val proposalId = Json.parseToJsonElement(proposal.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                client.post("/api/v1/changes/$proposalId/reject") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }.status shouldBe HttpStatusCode.OK // terminal, forever

                extra.toFile().deleteRecursively()
                harness.builder.rebuild()
                harness.availability.current().isAvailable(RootName.require("extra")).shouldBeFalse()

                withClue("503 says 'retry when the disk is back'. No retry will ever apply a rejected row: that is a 409") {
                    client.post("/api/v1/changes/$proposalId/approve").status shouldBe HttpStatusCode.Conflict
                }
                withClue("same rule for rebase: a row that is not CONFLICTED has no rebase to do, up disk or down") {
                    client.post("/api/v1/changes/$proposalId/rebase").status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    test("a dirty row whose FILE is gone under a LIVE root still clears - the classification must not mask a real deletion") {
        twoRoots { main, extra ->
            MultiRootRestHarness(listOf(testRoot("main", main), testRoot("extra", extra))).use { harness ->
                harness.boot()
                val page = harness.builder.current.byPath.getValue(
                    com.plainbase.domain.root.RootedPath(RootName.require("extra"), TreePath.require("notes/rollback.md")),
                )
                harness.dirtyPages.mark(
                    page.id,
                    com.plainbase.domain.root.RootedPath(RootName.require("extra"), page.path),
                    expectedHash = page.contentHash,
                    stage = com.plainbase.domain.repository.Stage.WRITING,
                )
                Files.delete(extra.resolve("notes/rollback.md")) // the FILE, not the root
                harness.builder.rebuild()

                harness.index.writePipeline().reconcileDirtyPages()

                harness.dirtyPages.all().shouldBeEmptyList()
            }
        }
    }
})

/**
 * A history provider that is ENABLED but records/returns nothing — enough to answer the `git_enabled` capability
 * flag without standing up a real repository (the flag is `HistoryProvider.enabled`, never type-sniffing).
 */
private object EnabledStubHistory : com.plainbase.domain.history.HistoryProvider {
    override val enabled = true
    override fun commit(
        path: TreePath,
        bytes: ByteArray,
        author: com.plainbase.domain.history.CommitIdentity?,
        committer: com.plainbase.domain.history.CommitIdentity?,
    ) = null

    override fun lastCommits(paths: List<TreePath>) = emptyMap<TreePath, com.plainbase.domain.history.Commit>()
    override fun log(path: TreePath, limit: Int?) = emptyList<com.plainbase.domain.history.Commit>()
    override fun diff(from: String, to: String, path: TreePath) =
        com.plainbase.domain.history.FileDiff(from = from, to = to, path = path, unifiedDiff = "")

    override fun prepare() = Unit
    override fun gateCheck() = Unit
}

/**
 * A git-backed history whose READS always blow up (`log`/`diff`), the way a real `git -C <workTree>` does when the
 * work tree is gone — and equally the way it does when the repo is corrupt or the flag is unknown.
 *
 * That the SAME exception carries both is the point: nothing about the failure itself says which one happened, so
 * the exit boundary cannot classify it by type. Only the STORE PROBE can, which is why the three tests above feed
 * this one stub and differ solely in whether the disk is still there.
 *
 * `lastCommits` deliberately does NOT throw: it runs inside the harness's opening rebuild, while the root is
 * healthy, and a scan that fails there would be a different (already-covered) story.
 */
private object FailingGitReads : com.plainbase.domain.history.HistoryProvider {
    override val enabled = true
    override fun commit(
        path: TreePath,
        bytes: ByteArray,
        author: com.plainbase.domain.history.CommitIdentity?,
        committer: com.plainbase.domain.history.CommitIdentity?,
    ) = null

    override fun lastCommits(paths: List<TreePath>) = emptyMap<TreePath, com.plainbase.domain.history.Commit>()
    override fun log(path: TreePath, limit: Int?): List<com.plainbase.domain.history.Commit> =
        throw com.plainbase.frameworks.git.GitCommandException("log", 128, "fatal: cannot change to '/gone': No such file or directory")

    override fun diff(from: String, to: String, path: TreePath): com.plainbase.domain.history.FileDiff =
        throw com.plainbase.frameworks.git.GitCommandException("diff", 128, "fatal: cannot change to '/gone': No such file or directory")

    override fun prepare() = Unit
    override fun gateCheck() = Unit
}

// ---- small, honest assertion helpers ------------------------------------------------------------

private infix fun <T> Collection<T>.shouldHaveSizeOf(expected: Int) {
    size shouldBe expected
}

private infix fun <K, V> Map<K, V>.shouldHaveSizeOf(expected: Int) {
    size shouldBe expected
}

private fun <T> T?.shouldNotBeNullAnd(block: (T) -> Unit) {
    block(requireNotNull(this) { "expected a non-null value" })
}

private fun <T> Collection<T>.shouldBeEmptyList() {
    size shouldBe 0
}

private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
    Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

/**
 * Polls [condition] until it holds, or fails. The one row that waits on a real watcher thread: the detection bound
 * is the watcher's liveness interval, and the deadline is generous because what is being asserted is that detection
 * HAPPENS AT ALL without traffic - never how fast (the same rule the §B1 watcher tests follow).
 */
private fun awaitUntil(message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (!condition()) {
        check(System.nanoTime() < deadline) { message }
        Thread.sleep(50)
    }
}
