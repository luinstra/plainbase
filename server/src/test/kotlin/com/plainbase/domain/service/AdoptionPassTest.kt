package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.queryLong
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AdoptionPass acceptance tests (the chunk 4b master criteria, component level): the zero-write
 * proof, dry-run exactness, materialization + idempotence with the single-insertion byte-diff, the
 * pre-write intent log and its mid-batch-abort reconstruction, the §5.2 duplicate policy
 * end-to-end, and binary-at-rest over the adoption-seeded id_map.
 *
 * Every test runs against a FRESH COPY of the committed fixture tree in a temp dir — the committed
 * fixtures are never written to — over an in-memory SQLite id_map. Accept/refuse expectations are
 * derived from the patcher itself (its behavior is owned by the §A3 goldens; these tests pin
 * AdoptionPass's ROUTING of each patcher outcome, so fixture frontmatter can evolve freely).
 */
class AdoptionPassTest : FunSpec({

    test("zero-write proof: default adoption assigns ids to all pages and the directory checksum is identical") {
        withHarness { h ->
            val before = checksum(h.root)
            val report = h.pass().run(AdoptionPass.Mode.RECORD)
            checksum(h.root) shouldBe before

            val pages = mdPages(h.root).map { Nfc.normalize(it) }
            report.pages shouldHaveSize pages.size
            h.idMap.bindings() shouldHaveSize pages.size
            h.idMap.bindings().map { it.path.value }.shouldContainExactlyInAnyOrder(pages)
            // Read-only first index: nothing is materialized, every id is map-only.
            h.idMap.bindings().none { it.materialized }.shouldBeTrue()
            // Binary at rest over the adoption-seeded table (direct SQL, below the adapter).
            h.driver.queryLong("SELECT count(*) FROM id_map WHERE length(id) != 16") shouldBe 0L
        }
    }

    test("dry-run lists exactly the unmaterialized pages and the would-refuse pages, and writes nothing") {
        withHarness { h ->
            addRefusalPage(h.root)
            val before = checksum(h.root)
            val expected = patcherOutcomes(h.root)

            val report = h.pass().run(AdoptionPass.Mode.PREVIEW)

            // Writes NOTHING: neither tree bytes nor db rows.
            checksum(h.root) shouldBe before
            h.idMap.bindings().shouldBeEmpty()
            h.idMap.issues().shouldBeEmpty()

            // EXACTLY the pages a MATERIALIZE run would patch / refuse.
            report.pages(AdoptionPass.Disposition.WOULD_MATERIALIZE).map { it.path.value }
                .shouldContainExactlyInAnyOrder(expected.accepted)
            report.pages(AdoptionPass.Disposition.REFUSED).map { it.path.value }
                .shouldContainExactlyInAnyOrder(expected.refused)

            // The would-refuse listing carries the rule-naming reason (§A3 measurement input).
            val refused = report.pages(AdoptionPass.Disposition.REFUSED).single { it.path.value == REFUSAL_PAGE }
            val reason = refused.issues.filterIsInstance<IdentityIssue.PatchRefused>().single()
            reason.message shouldContain "plain unquoted scalars"
        }
    }

    test("materialize: 100% of accepted pages patched (single-insertion byte-diff), second run = zero writes") {
        withHarness { h ->
            addRefusalPage(h.root)
            val originals = mdPages(h.root).associate { Nfc.normalize(it) to Files.readAllBytes(h.root.resolve(it)) }
            val expected = patcherOutcomes(h.root)

            // Run 1, with the intent log and the writes recorded into ONE event stream so the
            // intent-before-write pairing is asserted positionally.
            val events = mutableListOf<Pair<String, TreePath>>()
            val recording = RecordingStore(LocalContentStore(h.root)) { path -> events.add("write" to path) }
            val report = h.pass(recording).run(AdoptionPass.Mode.MATERIALIZE) { path, _ -> events.add("intent" to path) }

            // Every write is immediately preceded by its own intent entry.
            events.size shouldBe 2 * expected.accepted.size
            events.chunked(2).forEach { (intent, write) ->
                intent.first shouldBe "intent"
                write.first shouldBe "write"
                write.second shouldBe intent.second
            }

            // 100% of accepted pages materialized; each differs from its original by exactly the
            // patcher's single-point insertion carrying the assigned id.
            val materialized = report.pages(AdoptionPass.Disposition.MATERIALIZED)
            materialized.map { it.path.value }.shouldContainExactlyInAnyOrder(expected.accepted)
            materialized.forEach { page ->
                val original = originals.getValue(page.path.value)
                val patched = Files.readAllBytes(resolveByNfc(h.root, page.path.value))
                assertSingleIdInsertion(original, patched, page.id)
                h.idMap.find(page.path).shouldNotBeNull().materialized shouldBe true
            }

            // Refused pages are untouched, keep their map identity unmaterialized, and the crafted
            // page's issue is persisted with the rule-naming message.
            report.pages(AdoptionPass.Disposition.REFUSED).map { it.path.value }
                .shouldContainExactlyInAnyOrder(expected.refused)
            expected.refused.forEach { rel ->
                Files.readAllBytes(resolveByNfc(h.root, rel)) shouldBe originals.getValue(rel)
                h.idMap.find(TreePath.require(rel)).shouldNotBeNull().materialized shouldBe false
            }
            val refusal = h.idMap.issues().filterIsInstance<IdentityIssue.PatchRefused>()
                .single { it.path.value == REFUSAL_PAGE }
            refusal.message shouldContain "plain unquoted scalars"

            // Idempotence: a second MATERIALIZE run performs ZERO ContentStore writes and leaves
            // the tree byte-identical; every previously patched page now reads back as FRONTMATTER.
            val afterFirst = checksum(h.root)
            val counting = RecordingStore(LocalContentStore(h.root)) {}
            val second = h.pass(counting).run(AdoptionPass.Mode.MATERIALIZE)
            counting.writes shouldBe 0
            checksum(h.root) shouldBe afterFirst
            second.pages(AdoptionPass.Disposition.MATERIALIZED).shouldBeEmpty()
            second.pages(AdoptionPass.Disposition.ALREADY_MATERIALIZED) shouldHaveSize materialized.size
            second.pages(AdoptionPass.Disposition.ALREADY_MATERIALIZED)
                .all { it.source == PageIdentityService.Source.FRONTMATTER }.shouldBeTrue()
        }
    }

    test("duplicate-id fixture: the previously-bound path keeps the id, the copy is reassigned and the issue persisted") {
        withHarness { h ->
            h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            val keptPath = TreePath.require("notes/no-frontmatter.md")
            val keptId = h.idMap.find(keptPath).shouldNotBeNull().id

            // Copy the materialized page inside the temp tree — the §5.2 copied-file scenario.
            val copyPath = TreePath.require("notes/no-frontmatter-copy.md")
            Files.copy(h.root.resolve(keptPath.value), h.root.resolve(copyPath.value))

            val report = h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            val copy = report.pages.single { it.path == copyPath }
            copy.source shouldBe PageIdentityService.Source.MINTED
            copy.id shouldNotBe keptId
            // The copy's file still carries the OTHER page's id line -> AlreadyPresent -> never
            // overwritten, never marked materialized (§5.2: the fresh id is not materialized).
            copy.disposition shouldBe AdoptionPass.Disposition.MAPPED
            h.idMap.find(copyPath).shouldNotBeNull().materialized shouldBe false
            h.idMap.pathOf(keptId) shouldBe keptPath

            val issue = h.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>().single()
            issue shouldBe IdentityIssue.DuplicateId(id = keptId, keptPath = keptPath, reassignedPath = copyPath)

            // Rescan stability: the copy keeps its minted id on the next run (now from id_map), so
            // its /p/{id} permalink is stable while the conflict persists.
            val third = h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            val copyAgain = third.pages.single { it.path == copyPath }
            copyAgain.id shouldBe copy.id
            copyAgain.source shouldBe PageIdentityService.Source.ID_MAP
            // The deduped issue list still holds exactly one duplicate row.
            h.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>() shouldHaveSize 1
        }
    }

    test("intent log: a simulated mid-batch abort leaves a log from which completed/pending is reconstructable") {
        withHarness { h ->
            val real = LocalContentStore(h.root)
            val aborting = AbortingStore(real, failOnWrite = 4)
            val intents = mutableListOf<Pair<TreePath, PageId>>()

            shouldThrow<IOException> {
                h.pass(aborting).run(AdoptionPass.Mode.MATERIALIZE) { path, id -> intents.add(path to id) }
            }

            // 3 writes landed; the 4th was intent-logged and then aborted before the write — so the
            // completed/pending split falls out of the log plus the files' current bytes alone.
            intents shouldHaveSize 4
            val (completed, pending) = intents.partition { (path, id) ->
                String(Files.readAllBytes(resolveByNfc(h.root, path.value)), Charsets.UTF_8).contains("id: ${id.value}")
            }
            completed shouldHaveSize 3
            pending shouldHaveSize 1
            pending.single() shouldBe intents.last()

            // Idempotence makes re-running the reconciliation: the pending page materializes now.
            h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            val (pendingPath, _) = pending.single()
            h.idMap.find(pendingPath).shouldNotBeNull().materialized shouldBe true
        }
    }

    test("a page already carrying a valid frontmatter id is honored and bound as materialized") {
        withHarness { h ->
            val v4 = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
            Files.writeString(h.root.resolve("notes/already-identified.md"), "---\nid: $v4\ntitle: x\n---\nbody\n")

            val report = h.pass().run(AdoptionPass.Mode.RECORD)
            val page = report.pages.single { it.path.value == "notes/already-identified.md" }
            page.id shouldBe PageId.require(v4)
            page.source shouldBe PageIdentityService.Source.FRONTMATTER
            page.disposition shouldBe AdoptionPass.Disposition.ALREADY_MATERIALIZED
            h.idMap.find(page.path).shouldNotBeNull().materialized shouldBe true
        }
    }
})

/** The crafted would-refuse page (quoted key — the §A3 case-9 class) added to the temp tree. */
private const val REFUSAL_PAGE = "notes/refuse-me.md"

private fun addRefusalPage(root: Path) {
    Files.writeString(root.resolve(REFUSAL_PAGE), "---\n'quoted': key\n---\nbody\n")
}

/** A fresh fixture copy + in-memory id_map, torn down afterwards. */
private fun withHarness(block: (Harness) -> Unit) {
    val tmp = Files.createTempDirectory("pb-adopt")
    try {
        Files.walk(Fixtures.demoDocs).use { stream ->
            stream.forEach { src ->
                val dest = tmp.resolve(Fixtures.demoDocs.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dest) else Files.copy(src, dest)
            }
        }
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(Harness(tmp, driver))
        }
    } finally {
        Files.walk(tmp).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private class Harness(val root: Path, val driver: app.cash.sqldelight.db.SqlDriver) {
    val idMap = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))

    fun pass(store: ContentStore = LocalContentStore(root)): AdoptionPass =
        AdoptionPass(store, idMap, PageIdentityService(UuidV7IdProvider()), FrontmatterPatcher())
}

/** Counts (and reports) delegated writes — the zero-writes-on-second-run and pairing probe. */
private class RecordingStore(
    private val delegate: ContentStore,
    private val onWrite: (TreePath) -> Unit,
) : ContentStore by delegate {
    var writes = 0
        private set

    override fun write(path: TreePath, bytes: ByteArray) {
        writes++
        onWrite(path)
        delegate.write(path, bytes)
    }
}

/** Fails the [failOnWrite]-th write — the simulated mid-batch abort. */
private class AbortingStore(
    private val delegate: ContentStore,
    private val failOnWrite: Int,
) : ContentStore by delegate {
    private var writes = 0

    override fun write(path: TreePath, bytes: ByteArray) {
        if (++writes == failOnWrite) throw IOException("simulated mid-batch abort")
        delegate.write(path, bytes)
    }
}

/** The tree's .md files as content-relative `/`-joined strings (raw on-disk form). */
private fun mdPages(root: Path): List<String> =
    Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
            .map { root.relativize(it).joinToString("/") }
            .toList()
    }

/** The patcher's own accept/refuse split over the tree's current bytes, keyed by NFC path. */
private class PatcherOutcomes(val accepted: List<String>, val refused: List<String>)

private fun patcherOutcomes(root: Path): PatcherOutcomes {
    val probe = FrontmatterPatcher()
    val probeId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val outcomes = mdPages(root).associate { rel ->
        Nfc.normalize(rel) to probe.patch(Files.readAllBytes(root.resolve(rel)), probeId)
    }
    return PatcherOutcomes(
        accepted = outcomes.filterValues { it is FrontmatterPatcher.PatchResult.Patched }.keys.toList(),
        refused = outcomes.filterValues { it is FrontmatterPatcher.PatchResult.Refused }.keys.toList(),
    )
}

/** Resolves an NFC content-relative path against the raw (possibly NFD) on-disk tree. */
private fun resolveByNfc(root: Path, nfcValue: String): Path {
    val match = mdPages(root).singleOrNull { Nfc.normalize(it) == nfcValue }
    return root.resolve(match ?: nfcValue)
}

/** NFC relative-path -> sha256 over every regular file: the directory-checksum primitive. */
private fun checksum(dir: Path): Map<String, String> =
    Files.walk(dir).use { stream ->
        stream.filter(Files::isRegularFile).toList().associate { file ->
            Nfc.normalize(dir.relativize(file).joinToString("/")) to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
        }
    }

/**
 * The byte-diff acceptance assertion: [patched] is [original] plus exactly ONE single-point
 * insertion — the `id:` line inside an existing block, or the complete minimal block when the
 * original had none (§A3 cases 1–4). Proven structurally (longest common prefix + suffix cover
 * every original byte) and textually (the inserted bytes are exactly the id line / block).
 */
private fun assertSingleIdInsertion(original: ByteArray, patched: ByteArray, id: PageId) {
    val inserted = patched.size - original.size
    inserted shouldBeGreaterThan 0

    var prefix = 0
    while (prefix < original.size && original[prefix] == patched[prefix]) prefix++
    var suffix = 0
    while (suffix < original.size - prefix && original[original.size - 1 - suffix] == patched[patched.size - 1 - suffix]) suffix++
    // Single-point insertion: every original byte survives, in order, as prefix + suffix.
    (prefix + suffix >= original.size).shouldBeTrue()

    val at = original.size - suffix
    val insertion = String(patched.copyOfRange(at, at + inserted), Charsets.UTF_8)
    val pattern = when (FrontmatterBlock.detect(original)) {
        is FrontmatterBlock.Detection.Present -> Regex("id: ${id.value}(\r\n|\r|\n)")
        is FrontmatterBlock.Detection.Absent -> Regex("---(\r\n|\r|\n)id: ${id.value}\\1---\\1")
    }
    pattern.matches(insertion).shouldBeTrue()
}
