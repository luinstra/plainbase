package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/** Git off (W4 §6 #5): every op is a no-op, no `.git` is created, no process is spawned. */
class NoOpHistoryProviderTest : FunSpec({

    val path = TreePath.require("page.md")

    test("no .git is created and every op is a no-op") {
        val root = Files.createTempDirectory("plainbase-noop-git")
        try {
            NoOpHistoryProvider.commit(path, "content\n".toByteArray()).shouldBeNull()
            NoOpHistoryProvider.lastCommits(listOf(path)).shouldBeEmpty()
            NoOpHistoryProvider.log(path).shouldBeEmpty()
            NoOpHistoryProvider.diff("a", "b", path).unifiedDiff shouldBe ""
            NoOpHistoryProvider.prepare() // no-op: creates no `.git`, spawns nothing (W5 P1)
            NoOpHistoryProvider.gateCheck() // never throws
            Files.exists(root.resolve(".git")) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
