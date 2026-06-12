package com.plainbase.domain.service

import com.plainbase.domain.page.PageIndex
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The §B4 publication-listener seam: listeners run synchronously inside the serialized rebuild,
 * AFTER the snapshot publishes (so a listener observing `current` sees exactly the snapshot it
 * was handed) — and the exception policy holds: a THROWING listener is contained and logged,
 * `rebuild()` returns normally, the published snapshot stands, and the listeners after it still
 * run. This is the seam S2 registers the search sync on and the Phase-3 save path rides.
 */
class IndexBuilderListenerTest : FunSpec({

    fun seed(root: java.nio.file.Path) {
        writePage(root, "a.md", "# Alpha\n\nbody\n")
        writePage(root, "b.md", "# Beta\n\nbody\n")
    }

    test("listeners receive the snapshot that was JUST published, after publication") {
        withTempTree(::seed) { root ->
            val seen = mutableListOf<PageIndex>()
            lateinit var currentAtCallback: PageIndex
            lateinit var harness: IndexHarness
            harness = IndexHarness(
                root,
                listeners = listOf(
                    IndexBuilder.PublicationListener { snapshot ->
                        seen += snapshot
                        currentAtCallback = harness.builder.current
                    },
                ),
            )
            harness.use {
                val snapshot = harness.builder.rebuild()
                seen shouldBe listOf(snapshot)
                currentAtCallback shouldBe snapshot // publish happens BEFORE notification
                harness.builder.rebuild()
                seen.size shouldBe 2
                seen[1] shouldNotBe seen[0]
            }
        }
    }

    test("exception policy: a throwing listener is contained — rebuild returns, the snapshot stands, later listeners run") {
        withTempTree(::seed) { root ->
            var laterListenerSaw: PageIndex? = null
            val harness = IndexHarness(
                root,
                listeners = listOf(
                    IndexBuilder.PublicationListener { error("listener blew up (deliberately)") },
                    IndexBuilder.PublicationListener { laterListenerSaw = it },
                ),
            )
            harness.use {
                val snapshot = harness.builder.rebuild() // must NOT throw
                harness.builder.current shouldBe snapshot // the publish stands
                snapshot.pages.size shouldBe 2
                laterListenerSaw shouldBe snapshot // the remaining listener still ran
                (snapshot !== PageIndex.EMPTY).shouldBeTrue()
            }
        }
    }
})
