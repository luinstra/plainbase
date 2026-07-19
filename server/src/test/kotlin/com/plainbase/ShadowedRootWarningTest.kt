package com.plainbase

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.localRoot
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The C5 shadow WARN (D-C5-6): a REGISTERED extra root whose NAME is a top-level segment of main.
 *
 * `splitRootTail` treats a tail's first segment as a root iff it names a registered root - so the moment a root
 * named `guides` joins a main that already has a top-level `guides/`, every circulating link through that
 * segment stops resolving inside main and starts resolving inside the new root. **That is a live-link
 * correctness break, not a cosmetic one.**
 *
 * **BOOT WARNS AND NEVER REFUSES, and this is the decision most worth arguing with.** A refusal would mean an
 * author creating a top-level folder called `handbook` in main - a pure docs edit, through the product's own UI -
 * BRICKS THE NEXT RESTART of a server that has a root named `handbook`. That converts a link ambiguity into a
 * production outage. `plainbase root add` DOES refuse (with `--force`), and that is a CLI-owned policy decision -
 * deliberately stricter than boot, not a proxy for a boot check.
 */
class ShadowedRootWarningTest : FunSpec({

    fun world(seedMain: (Path) -> Unit, block: (PageIndexAndRegistry) -> Unit) {
        val base = Files.createTempDirectory("pb-shadow")
        try {
            val main = Files.createDirectory(base.resolve("main"))
            val guides = Files.createDirectory(base.resolve("guides-root"))
            Files.writeString(guides.resolve("page.md"), "---\ntitle: G\n---\n\n# G\n")
            seedMain(main)

            val registry = RootRegistry.of(listOf(localRoot("main", main), localRoot("guides", guides)))
            val sources = registry.roots.map { root ->
                IndexBuilder.Source(root, LocalContentStore(requireNotNull(root.localPath)), NoOpHistoryProvider)
            }
            IndexHarness(main, rootRegistry = registry, sources = sources).use { harness ->
                block(PageIndexAndRegistry(harness.builder.rebuild(), registry))
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    test("T-BOOT-1: a URL-space shadow WARNs, naming the root and the offending paths") {
        world({ main ->
            Files.createDirectories(main.resolve("guides"))
            Files.writeString(main.resolve("guides/deploy.md"), "---\ntitle: D\n---\n\n# D\n")
        }) { w ->
            val warning = shadowedRootWarning(w.snapshot, emptyMap(), w.registry)
            warning.shouldNotBeNull()
            warning shouldContain "guides"
            warning shouldContain "guides/deploy"
            warning shouldContain "resolve inside the ROOT, not inside main"
        }
    }

    test("T-BOOT-1: a CONTENT-PATH-space shadow WARNs too - /browse and /assets speak the raw tree, not the URL space") {
        world({ main ->
            // An ASSET under a top-level `guides/` directory: it never enters the URL space at all, but
            // `/assets/guides/...` and `/browse/guides/...` are still ambiguous the moment a root is named `guides`.
            Files.createDirectories(main.resolve("guides"))
            Files.write(main.resolve("guides/logo.png"), "png".toByteArray())
            Files.writeString(main.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
        }) { w ->
            val warning = shadowedRootWarning(w.snapshot, emptyMap(), w.registry)
            warning.shouldNotBeNull()
            warning shouldContain "guides/logo.png"
        }
    }

    test("T-BOOT-1: an ALIAS-ONLY shadow WARNs - the surface the CLI structurally CANNOT see") {
        // If this case is missing, the boot warn's whole reason to exist is untested. A `redirect_from` alias row
        // lives in the DB, outlives the frontmatter that minted it, and no filesystem scan can find one - so the
        // CLI's scan-derived check is blind to it by construction. This is the backstop.
        world({ main ->
            Files.writeString(main.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
        }) { w ->
            val aliases = mapOf(RootedPath(RootName.MAIN, TreePath.require("guides/old-deploy")) to RootedPageId(RootName.MAIN, id))
            val warning = shadowedRootWarning(w.snapshot, aliases, w.registry)
            warning.shouldNotBeNull()
            warning shouldContain "guides/old-deploy"
        }
    }

    test("an EXTRA root's alias rows do not raise a bogus shadow against main") {
        // The filter is `it.root == RootName.MAIN`, not "any alias anywhere" - an extra root's rows describe ITS
        // url space, which main's segment index has no business reading.
        world({ main -> Files.writeString(main.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n") }) { w ->
            val aliases = mapOf(RootedPath(RootName.require("guides"), TreePath.require("guides/x")) to RootedPageId(RootName.MAIN, id))
            shadowedRootWarning(w.snapshot, aliases, w.registry).shouldBeNull()
        }
    }

    test("no shadow -> null") {
        world({ main ->
            Files.createDirectories(main.resolve("handbook"))
            Files.writeString(main.resolve("handbook/p.md"), "---\ntitle: H\n---\n\n# H\n")
        }) { w ->
            shadowedRootWarning(w.snapshot, emptyMap(), w.registry).shouldBeNull()
        }
    }

    test("T-BOOT-2: a shadow is a WARNING, never a refusal - boot still SERVES") {
        // D-C5-6 is a DECISION, and a test that only checked the warning string would not catch someone later
        // turning it into a refusal. The reserved-`main` guard is the only shadow-shaped REFUSAL, and it stays one
        // because it is deterministic and unavoidable - not because refusal is the general policy.
        world({ main ->
            Files.createDirectories(main.resolve("guides"))
            Files.writeString(main.resolve("guides/deploy.md"), "---\ntitle: D\n---\n\n# D\n")
        }) { w ->
            shadowedRootWarning(w.snapshot, emptyMap(), w.registry).shouldNotBeNull()
            // The ONE boot refusal that reads the same index must stay silent: `guides` is not `main`.
            mainRootUrlCollisionRefusal(w.snapshot).shouldBeNull()
        }
    }
})

private class PageIndexAndRegistry(
    val snapshot: com.plainbase.domain.page.PageIndex,
    val registry: RootRegistry,
)
