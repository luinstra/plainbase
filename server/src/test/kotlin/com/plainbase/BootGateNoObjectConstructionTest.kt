package com.plainbase

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.S3ObjectClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * The counter-proof that `bootGateFor`'s isolated graph constructs NO object machinery - asserted the way this
 * repo already asserts this class (`LocalBootNoObjectConstructionTest`): a BEFORE/AFTER DELTA on the process-global
 * construction counters, never reasoned from `single {}` laziness.
 *
 * The gate has TWO independent seals against ever touching object mode, and this pins the second:
 *  1. `repositoryModule` is deliberately ABSENT from the graph, so the app DB is UNREACHABLE rather than merely
 *     un-opened - an accidental object-mode resolution fails LOUD with a missing definition rather than quietly
 *     opening and MIGRATING a database from a command that holds no DATA_DIR lock;
 *  2. a `check(backend == LOCAL)` at the top.
 *
 * The underlying reachability argument holds anyway - a candidate config always carries a `roots {}` block, and
 * object mode plus a roots block is refused at LOAD - which is exactly why `plainbase root` loads the candidate
 * FIRST and gates it SECOND. These are the belts.
 *
 * (The filesystem half of the purity guarantee - no git-home, no `git init`, no byte anywhere - is
 * `BootGatePurityTest`, in the native suite, because it is NIO plus real process execution.)
 */
class BootGateNoObjectConstructionTest : FunSpec({

    test("bootGateFor constructs ZERO ObjectContentStores and ZERO S3ObjectClients over a LOCAL config") {
        val base = Files.createTempDirectory("pb-gate-noobj")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            Files.writeString(content.resolve("page.md"), "---\ntitle: P\n---\n\n# P\n")

            val objectBefore = ObjectContentStore.constructions.get()
            val s3Before = S3ObjectClient.constructions.get()

            val config = PlainbaseConfig.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString()),
            )
            bootGateFor(config)

            ObjectContentStore.constructions.get() shouldBe objectBefore
            S3ObjectClient.constructions.get() shouldBe s3Before
        } finally {
            base.toFile().deleteRecursively()
        }
    }
})
