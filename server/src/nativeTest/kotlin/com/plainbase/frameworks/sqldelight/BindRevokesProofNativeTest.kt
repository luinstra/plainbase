package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R30, revoke-before-stamp (C5 Commit C) on the real file-backed xerial JDBC/JNI seam: **a bind advances the root's
 * binding_epoch, so an inferred AbsenceProof minted BEFORE that bind cannot reap the binding a restore just re-created.**
 *
 * The race this closes: a page's `dirty_page` recovery row is USER CONTENT (an interrupted save's ONLY record). If a
 * restore re-binds `(root, path, X)` after a pass minted an EPOCH proof over `(path, X)` but before that proof is
 * applied, the stale proof would tombstone the freshly-restored binding and DELETE its recovery row. The second,
 * orthogonal freshness stamp stops it: the re-bind advanced `binding_epoch`, the proof carries the OLD value, and the
 * two-token compare in `applyProofs` discards it - WITHOUT touching observation_id, so the epoch is not collapsed.
 *
 * Back-out proof: drop the `binding_epoch` half of the gate in `SqlDelightRetirementRepository.applyProofs` and all
 * three assertions below go RED - the observation compare alone still matches (the epoch never broke), so the stale
 * proof reaps the re-created binding and its recovery row. That is exactly the corpus bug this stamp exists to prevent.
 *
 * @Tag("native") + kotlin.test only - this source set compiles INTO the native test image.
 */
@Tag("native")
class BindRevokesProofNativeTest {

    @Test
    fun `a re-bind revokes a proof minted before it - the re-created binding and its dirty_page recovery row survive`() {
        val dir = Files.createTempDirectory("pb-native-revoke-before-stamp")
        try {
            val dbPath = dir.resolve("plainbase.db")
            DatabaseFactory.createDriver(dbPath).use { driver ->
                val db = DatabaseFactory.createDatabase(driver)
                val idMap = SqlDelightIdMapRepository(db)
                val retirements = SqlDelightRetirementRepository(db)

                val root = RootName.PRIMARY
                val path = RootedPath(root, TreePath.require("guides/deploy.md"))
                val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

                // A live binding, plus the interrupted-save recovery row that belongs to it.
                idMap.bind(path, id, materialized = true)
                db.dirtyPageQueries.upsert(id = id, root = root, path = path.path, expectedHash = "sha256:recovery", stage = "WRITING")

                // A pass mints an EPOCH proof over (path, X): both freshness stamps captured at MINT time, before the
                // re-bind below. observation() also mints the durable row the increment then advances.
                val proof = AbsenceProof(
                    root = root,
                    source = ProofSource.EPOCH,
                    observationId = retirements.observation(root),
                    bindingEpoch = retirements.bindingEpoch(root),
                    covers = setOf(BindingRef(path.path, id)),
                )

                // THE RESTORE: the same page is re-bound at the same (root, path, X) - no tombstone, an idempotent
                // re-create. It advances binding_epoch, which is precisely the revocation the stale proof must lose to.
                idMap.bind(path, id, materialized = true)

                // The observation NEVER broke (no restart, no watcher break), so the observation compare still matches -
                // only the binding-epoch half discards this proof. witnessed is empty (no scan this call).
                val retired = retirements.applyProofs(listOf(proof), witnessed = emptySet(), unavailableNow = { emptySet() })

                assertTrue(retired.isEmpty(), "the stale proof reaped $retired - the binding-epoch gate did not discard it")
                assertNotNull(idMap.bindingInRoot(root, id), "the re-created binding was tombstoned by a proof minted before it")
                assertNotNull(
                    db.dirtyPageQueries.selectByRootId(root = root, id = id).executeAsOneOrNull(),
                    "the USER-CONTENT dirty_page recovery row was destroyed by a stale reap",
                )
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
