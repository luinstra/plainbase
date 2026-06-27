package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger

/**
 * F5 (W4 owner ruling): assets are NOT versioned in Phase 3. An asset upload goes through
 * `ContentStore.writeAssetExclusive`, a path that does NOT call [WriteHistoryHook] — so an upload fires
 * ZERO history commits. Asserted by wiring a counting hook into the W3b asset-upload harness and showing
 * a successful upload never invokes it. (Reuses the W3b `writeRestTest` harness.)
 */
class AssetUploadNoHistoryTest : FunSpec({

    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
    }
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5)

    test("an asset upload fires no history commit (assets are unversioned in Phase 3)") {
        val commits = AtomicInteger(0)
        val countingHook = WriteHistoryHook { _, _, _, _ ->
            commits.incrementAndGet()
            null
        }
        writeRestTest(Fixtures.demoDocs, seed, historyHook = countingHook) { _ ->
            val post = client.post("/api/v1/pages/$deployGuideId/assets?filename=diagram.png") { setBody(png) }
            post.status shouldBe HttpStatusCode.Created
            commits.get() shouldBe 0 // the asset path never routes through WriteHistoryHook
        }
    }
})
