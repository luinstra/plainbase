package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.IdResolution
import com.plainbase.domain.service.PageRootResolver
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE headline flip proof (§9): a real dual-root duplicate over the xerial-backed `id_map` must SURVIVE, not be
 * silently wiped. `selectRetiredAt`/`selectBindingByRootId`/the `:88` guard throw LOUDLY on the first mis-scoped
 * duplicate, while `unbindStale`/`unretire`/`survives` SUCCEED and DESTROY silently - so a `resolve() == One`/"no throw"
 * RED green-passes a half-flip. This RED constructs the duplicate and asserts SURVIVAL, and its two back-outs (global
 * `unbindStale`, global `unretire`) are RUN one at a time.
 *
 * Every [bind] uses the DEFAULT `supersession = Supersession.NONE`: NONE refuses every displacement, so if any of these
 * binds succeeded via `mayDisplace` the test would be proving the wrong thing (STOP-4 arm 1).
 *
 * @Tag("native") + kotlin.test - the JDBC/JNI seam, run under the native image.
 */
@Tag("native")
class DualRootIdentitySurvivalTest {

    @Test
    fun `a cross-root duplicate id survives displacement and reclaim in-image`() {
        val dir = Files.createTempDirectory("pb-native-survival")
        try {
            val main = RootName.PRIMARY
            val extra = RootName.require("extra")
            val pA = TreePath.require("guides/a.md")
            val pB = TreePath.require("notes/b.md")
            val x = PageId.require("01010101-0101-0101-0101-010101010101")
            val y1 = PageId.require("02020202-0202-0202-0202-020202020202")
            val y2 = PageId.require("03030303-0303-0303-0303-030303030303")

            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { driver ->
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))

                // Step 1 - the duplicate exists and BOTH rows survive. Under UNIQUE(id) the second bind is unreachable
                // (the global incumbent check returns main's row and Supersession.NONE refuses it).
                assertEquals(BindOutcome.Bound, repo.bind(RootedPath(main, pA), x, materialized = false))
                val secondBind = repo.bind(RootedPath(extra, pB), x, materialized = false)
                // STOP-4 arm 1: a foreign-root incumbent reaching mayDisplace under NONE would make this Refused naming a
                // foreign-root path. It is Bound, so mayDisplace never saw a foreign incumbent.
                assertEquals(BindOutcome.Bound, secondBind)
                assertTrue(repo.rootsHoldingId(x).containsAll(listOf(main, extra)))
                assertEquals(RootedPath(main, pA), repo.bindingInRoot(main, x)?.path)
                assertEquals(RootedPath(extra, pB), repo.bindingInRoot(extra, x)?.path)

                // Step 2 - tombstone X in BOTH roots by displacing it at each (root, path) with a different id.
                assertEquals(BindOutcome.Bound, repo.bind(RootedPath(main, pA), y1, materialized = false))
                assertEquals(BindOutcome.Bound, repo.bind(RootedPath(extra, pB), y2, materialized = false))
                assertNotNull(repo.retiredAt(main, x))
                assertNotNull(repo.retiredAt(extra, x)) // TWO tombstones for one id - unreachable under UNIQUE(id)

                // Step 3 - restore X in ONE root; the OTHER root's tombstone must SURVIVE.
                assertEquals(BindOutcome.Bound, repo.bind(RootedPath(main, pA), x, materialized = false))
                assertNull(repo.retiredAt(main, x)) // main reclaimed its own id
                val extraTombstone = assertNotNull(repo.retiredAt(extra, x)) // THE HEADLINE SURVIVAL ASSERTION
                assertEquals(RootedPath(extra, pB), extraTombstone.path)
                assertEquals(RootedPath(main, pA), repo.bindingInRoot(main, x)?.path)

                // Step 4 (R22n) - the fail-closed resolve at the JDBC seam. Step 3 left main LIVE on X and extra holding
                // X's surviving tombstone: the mixed live-plus-foreign-tombstone case, which is Ambiguous, NOT One(main).
                val mainDir = Files.createDirectories(dir.resolve("main"))
                val extraDir = Files.createDirectories(dir.resolve("extra"))
                val registry = RootRegistry.of(
                    listOf(
                        Root(main, RootBackend.Local(mainDir), editable = true, history = HistoryMode.OFF),
                        Root(extra, RootBackend.Local(extraDir), editable = true, history = HistoryMode.OFF),
                    ),
                )
                val resolution = PageRootResolver(repo, registry).resolve(x)
                assertTrue(resolution is IdResolution.Ambiguous, "expected Ambiguous, was $resolution")
                assertTrue(resolution.hasRetiredCandidate, "the extra-root tombstone must set hasRetiredCandidate")
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
