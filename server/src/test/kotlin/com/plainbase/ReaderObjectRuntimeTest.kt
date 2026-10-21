package com.plainbase

import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.lifecycle.ServerRunControl
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ReaderObjectRuntimeTest : FunSpec({
    listOf(
        ReaderOverlapCase(
            "overlapping readers of the same release return identical answers",
            "releases/r1", ReaderReleases.r1, "alpha", sameRelease = true,
        ),
        ReaderOverlapCase(
            "overlapping readers of different releases return different answers",
            "releases/r2", ReaderReleases.r2, "beta", sameRelease = false,
        ),
    ).forEach { case ->
        test(case.description) {
            withReader { first ->
                first.useFakeObjectStore = true
                first.seedRelease("releases/r1", ReaderReleases.r1)
                if (!case.sameRelease) first.seedRelease("releases/r2", ReaderReleases.r2)
                val second = first.fresh(mapOf("PLAINBASE_S3_PREFIX" to case.secondPrefix))
                first.data shouldNotBe second.data
                (first.bucket === second.bucket) shouldBe true
                val requests = listOf(
                    ReaderRequest("/docs/index", markdown = true),
                    ReaderRequest("/docs/guides/alpha", markdown = true),
                    ReaderRequest("/docs/guides/gone", markdown = true),
                    ReaderRequest("/api/v1/pages/by-path/docs/guides/alpha"),
                    ReaderRequest("/p/docs/${ReaderReleases.guideId.value}"),
                    ReaderRequest("/api/v1/tree"),
                )
                lateinit var firstContext: RouteContext
                lateinit var secondContext: RouteContext
                var firstAnswers = emptyMap<ReaderRequest, ReaderAnswer>()
                var secondAnswers = emptyMap<ReaderRequest, ReaderAnswer>()
                var firstStarts = 0
                var secondStarts = 0

                val firstStatus = runServer(
                    first.config(), first.output.output, first.openers,
                    ServerRunControl(
                        onRuntimeContext = { firstContext = it },
                        startServer = {
                            firstStarts++
                            val secondStatus = runServer(
                                second.config(), second.output.output, second.openers,
                                ServerRunControl(
                                    onRuntimeContext = { secondContext = it },
                                    startServer = {
                                        secondStarts++
                                        dataDirLockHeldForOwnership(first.data) shouldBe true
                                        dataDirLockHeldForOwnership(second.data) shouldBe true
                                        secondAnswers = second.answers(secondContext, requests)
                                        assertReaderReleaseAnswers(secondAnswers, case.secondFiles, case.secondGuide)
                                        dataDirLockHeldForOwnership(first.data) shouldBe true
                                        dataDirLockHeldForOwnership(second.data) shouldBe true
                                        runtimeLogger.info {
                                            "both reader data directory locks were held during answer collection"
                                        }
                                    },
                                ),
                            )
                            withClue("second reader status=$secondStatus stderr=${second.errors()}") {
                                secondStatus shouldBe 0
                                second.errors() shouldBe emptyList()
                            }
                            firstAnswers = first.answers(firstContext, requests)
                            assertReaderReleaseAnswers(firstAnswers, ReaderReleases.r1, "alpha")
                        },
                    ),
                )

                firstStatus shouldBe 0
                first.errors() shouldBe emptyList()
                firstStarts shouldBe 1
                secondStarts shouldBe 1
                if (case.sameRelease) {
                    firstAnswers shouldBe secondAnswers
                } else {
                    firstAnswers shouldNotBe secondAnswers
                }
                first.bucket.putCount shouldBe 0
                first.bucket.deleteCount shouldBe 0
                first.bucket.listCount shouldBe 2
                runtimeLogger.info {
                    "overlapping reader comparison: sameRelease=${case.sameRelease}, requests=${requests.size}, " +
                        "equal=${firstAnswers == secondAnswers}, lists=${first.bucket.listCount}, " +
                        "puts=${first.bucket.putCount}, deletes=${first.bucket.deleteCount}"
                }
            }
        }
    }

    listOf(
        ReaderOtherFileFault("a listed asset deleted before its first index", "assets/logo.svg", restoreAfterRebuild = false),
        ReaderOtherFileFault("a listed asset moved aside during its first index", "assets/logo.svg"),
        ReaderOtherFileFault("a listed folder sidecar moved aside during its first index", "guides/_folder.yaml"),
        ReaderOtherFileFault("a metadata-only folder moved aside during its first index", "meta-only", "meta-only/_folder.yaml"),
    ).forEach { fault ->
        test("reader refuses ${fault.description}") {
            withReader { world ->
                world.useFakeObjectStore = true
                world.seedRelease("releases/r1", ReaderReleases.r1)
                var starts = 0
                val status = runServer(
                    world.config(), world.output.output, world.openers,
                    ServerRunControl(
                        initialRebuild = { builder ->
                            val mirror = world.data.resolve("mirror")
                            val source = mirror.resolve(fault.path)
                            if (fault.restoreAfterRebuild) {
                                val aside = Files.createDirectories(world.data.parent.resolve("aside")).resolve(source.fileName)
                                aside.startsWith(mirror) shouldBe false
                                Files.move(source, aside)
                                try {
                                    builder.rebuild()
                                } finally {
                                    Files.move(aside, source)
                                }
                                val restored = mirror.resolve(fault.listedPath)
                                Files.isRegularFile(restored) shouldBe true
                                Files.readString(restored) shouldBe ReaderReleases.r1.getValue(fault.listedPath).decodeToString()
                                if (fault.path == "assets/logo.svg") {
                                    world.stores.single().read(TreePath.require(fault.path)) shouldBe null
                                    runtimeLogger.info { "restored object asset remains outside scan membership: ${fault.path}" }
                                }
                            } else {
                                Files.delete(source)
                                builder.rebuild()
                            }
                        },
                        startServer = { starts++ },
                    ),
                )

                withClue("status=$status starts=$starts stderr=${world.errors()}") {
                    assertSoftly {
                        status shouldBe 1
                        starts shouldBe 0
                        world.errors().size shouldBe 1
                        world.errors().singleOrNull().orEmpty() shouldStartWith "serve: "
                        world.errors().singleOrNull().orEmpty() shouldContain fault.listedPath
                    }
                }
            }
        }
    }

    test("reader refuses an object replaced between its listing and GET") {
        withReader { world ->
            world.useFakeObjectStore = true
            world.seedRelease("releases/r1", ReaderReleases.r1)
            val key = "releases/r1/guides/alpha.md"
            val listedEtag = world.bucket.currentEtag(key).shouldNotBeNull()
            val original = ReaderReleases.r1.getValue("guides/alpha.md").decodeToString()
            val replacement = original.replace("Alpha release content.", "Alpha revised release content.")
            (replacement != original) shouldBe true
            replacement shouldContain "id: ${ReaderReleases.guideId.value}"
            var replacements = 0
            world.bucket.onGetKey = {
                if (it == key) {
                    world.bucket.seed(it, replacement.toByteArray())
                    replacements++
                }
            }
            var starts = 0

            val status = runServer(
                world.config(), world.output.output, world.openers, ServerRunControl(startServer = { starts++ }),
            )

            replacements shouldBe 1
            (world.bucket.currentEtag(key) != listedEtag) shouldBe true
            Files.readString(world.data.resolve("mirror/guides/alpha.md")) shouldBe replacement
            withClue("status=$status starts=$starts stderr=${world.errors()}") {
                assertSoftly {
                    status shouldBe 1
                    starts shouldBe 0
                    world.errors().size shouldBe 1
                    world.errors().singleOrNull().orEmpty() shouldStartWith "serve: "
                    world.errors().singleOrNull().orEmpty() shouldContain "did not see the whole listed generation"
                }
            }
        }
    }

    listOf(
        ReaderIndexFault("an empty prefix", "releases/none", "no page"),
        ReaderIndexFault("an asset-only prefix", "releases/a", "no page", seedAssetsOnly = true),
        ReaderIndexFault("a page removed before the first rebuild", "releases/r1", "guides/alpha.md", removePage = true),
        ReaderIndexFault(
            "the mirror removed before the first rebuild", "releases/r1", "the first pass skipped the root", removeMirror = true,
        ),
    ).forEach { fault ->
        test("reader refuses ${fault.description} before starting the server") {
            withReader(extraEnv = mapOf("PLAINBASE_S3_PREFIX" to fault.prefix)) { world ->
                world.useFakeObjectStore = true
                if (fault.seedAssetsOnly) {
                    world.seedRelease(fault.prefix, mapOf("assets/logo.svg" to "<svg/>".toByteArray()))
                } else if (fault.prefix == "releases/r1") {
                    world.seedRelease(fault.prefix, ReaderReleases.r1)
                }
                var starts = 0
                val status = runServer(
                    world.config(), world.output.output, world.openers,
                    ServerRunControl(
                        initialRebuild = { builder ->
                            if (fault.removePage) Files.delete(world.data.resolve("mirror/guides/alpha.md"))
                            if (fault.removeMirror) {
                                val mirror = world.data.resolve("mirror")
                                Files.walk(mirror).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                                Files.exists(mirror) shouldBe false
                                Files.exists(world.data.resolve("mirror-state")) shouldBe true
                                Files.exists(world.data.resolve("plainbase.db")) shouldBe true
                                world.bucket.listCount shouldBe 1
                            }
                            builder.rebuild()
                        },
                        startServer = { starts++ },
                    ),
                )

                withClue("status=$status starts=$starts stderr=${world.errors()}") {
                    assertSoftly {
                        status shouldBe 1
                        starts shouldBe 0
                        world.errors().size shouldBe 1
                        world.errors().singleOrNull().orEmpty() shouldStartWith "serve: "
                        world.errors().singleOrNull().orEmpty() shouldContain fault.reason
                    }
                }
            }
        }
    }

    listOf("plain release", "root folder metadata", "dot-prefixed page").forEach { control ->
        test("reader first index accepts $control") {
            withReader { world ->
                world.useFakeObjectStore = true
                world.seedRelease("releases/r1", ReaderReleases.r1)
                when (control) {
                    "root folder metadata" -> world.seedRelease("releases/r1", mapOf("_folder.yaml" to "title: Root\n".toByteArray()))
                    "dot-prefixed page" -> world.seedRelease("releases/r1", mapOf(".notes/x.md" to "# Hidden\n".toByteArray()))
                }
                var starts = 0

                val status = runServer(
                    world.config(), world.output.output, world.openers, ServerRunControl(startServer = { starts++ }),
                )

                assertSoftly {
                    status shouldBe 0
                    starts shouldBe 1
                    world.errors() shouldBe emptyList()
                }
            }
        }
    }

    test("reader does not poll after boot even with a one second interval and its watcher closes promptly") {
        withReader(extraEnv = mapOf("PLAINBASE_S3_POLL_SECONDS" to "1")) { world ->
            world.useFakeObjectStore = true
            world.seedRelease("releases/r1", ReaderReleases.r1)
            world.config().storage.pollSeconds shouldBe 1L
            lateinit var watcher: AutoCloseable
            var poller: Thread? = null
            var listsBefore = -1
            var listsAfter = -1
            var getsBefore = -1
            var getsAfter = -1
            var observedMillis = -1L
            var closeMillis = -1L

            val status = runServer(
                world.config(), world.output.output, world.openers,
                ServerRunControl(
                    onWatcherAcquired = { _, acquired -> watcher = acquired },
                    startServer = {
                        poller = world.stores.single().pollerForTest()
                        listsBefore = world.bucket.listCount
                        getsBefore = world.bucket.getCount
                        val observation = TimeSource.Monotonic.markNow()
                        runBlocking { delay(3_500) }
                        observedMillis = observation.elapsedNow().inWholeMilliseconds
                        listsAfter = world.bucket.listCount
                        getsAfter = world.bucket.getCount
                        val close = TimeSource.Monotonic.markNow()
                        watcher.close()
                        closeMillis = close.elapsedNow().inWholeMilliseconds
                        runtimeLogger.info {
                            "reader object poll observation: pollerPresent=${poller != null}, " +
                                "lists=$listsBefore->$listsAfter, gets=$getsBefore->$getsAfter, " +
                                "observedMillis=$observedMillis, closeMillis=$closeMillis"
                        }
                    },
                ),
            )

            withClue("lists=$listsBefore->$listsAfter gets=$getsBefore->$getsAfter waited=$observedMillis close=$closeMillis") {
                assertSoftly {
                    status shouldBe 0
                    world.errors() shouldBe emptyList()
                    poller shouldBe null
                    observedMillis shouldBeGreaterThanOrEqual 3_500L
                    listsAfter shouldBe listsBefore
                    getsAfter shouldBe getsBefore
                    closeMillis shouldBeLessThan 1_000L
                }
            }
        }
    }

    test("workspace keeps polling with a one second interval") {
        withReader(workspace = true, extraEnv = mapOf("PLAINBASE_S3_POLL_SECONDS" to "1")) { world ->
            world.useFakeObjectStore = true
            world.seedRelease("releases/r1", ReaderReleases.r1)
            world.config().profile shouldBe DeploymentProfile.WORKSPACE
            world.config().storage.pollSeconds shouldBe 1L
            var poller: Thread? = null
            var lists = -1
            var elapsedMillis = -1L

            val status = runServer(
                world.config(), world.output.output, world.openers,
                ServerRunControl(startServer = {
                    poller = world.stores.single().pollerForTest()
                    val observation = TimeSource.Monotonic.markNow()
                    runBlocking { eventually(5.seconds) { world.bucket.listCount shouldBeGreaterThanOrEqual 2 } }
                    elapsedMillis = observation.elapsedNow().inWholeMilliseconds
                    lists = world.bucket.listCount
                    runtimeLogger.info { "workspace object poll observation: lists=$lists, elapsedMillis=$elapsedMillis" }
                }),
            )

            assertSoftly {
                status shouldBe 0
                world.errors() shouldBe emptyList()
                poller.shouldNotBeNull()
                lists shouldBeGreaterThanOrEqual 2
            }
        }
    }

    test("reader health reports available roots without coverage and with zero limbo") {
        withReader(extraEnv = mapOf("PLAINBASE_S3_POLL_SECONDS" to "1")) { world ->
            world.useFakeObjectStore = true
            world.seedRelease("releases/r1", ReaderReleases.r1)
            lateinit var ctx: RouteContext
            val health = ReaderRequest("/healthz")
            var answers = emptyMap<ReaderRequest, ReaderAnswer>()

            val status = runServer(
                world.config(), world.output.output, world.openers,
                ServerRunControl(
                    onRuntimeContext = { ctx = it },
                    startServer = { answers = world.answers(ctx, listOf(health)) },
                ),
            )

            status shouldBe 0
            world.errors() shouldBe emptyList()
            val answer = answers.getValue(health)
            answer.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(answer.body).jsonObject
            body.getValue("status").jsonPrimitive.content shouldBe "ok"
            val roots = body.getValue("roots").jsonArray
            roots.size shouldBe 1
            roots.forEach { entry ->
                val root = entry.jsonObject
                root.getValue("available").jsonPrimitive.content shouldBe "true"
                root["coverage"] shouldBe null
                root.getValue("limbo").jsonPrimitive.content shouldBe "0"
            }
        }
    }

    val faults = listOf(
        ReaderHydrateFault("its GET fails", "GET") { world, key -> world.bucket.failNextGetFor += key },
        ReaderHydrateFault("it disappears before GET", "GET") { world, key ->
            world.bucket.onGetKey = { if (it == key) world.bucket.remove(it) }
        },
        ReaderHydrateFault("its mirror write fails", "mirror write") { world, key ->
            world.bucket.onGetKey = {
                if (it == key) {
                    val target = world.data.resolve("mirror/guides/alpha.md")
                    Files.createDirectories(target)
                    Files.writeString(target.resolve(".hidden"), "x")
                }
            }
        },
    )
    faults.forEach { fault ->
        test("reader refuses a listed page when ${fault.description}") {
            withReader { world ->
                world.useFakeObjectStore = true
                world.seedRelease("releases/r1", ReaderReleases.r1)
                fault.inject(world, "releases/r1/guides/alpha.md")
                var starts = 0

                val status = runServer(
                    world.config(), world.output.output, world.openers,
                    ServerRunControl(startServer = { starts++ }),
                )

                withClue("status=$status starts=$starts stderr=${world.errors()}") {
                    assertSoftly {
                        status shouldBe 1
                        starts shouldBe 0
                        world.errors().size shouldBe 1
                        world.errors().firstOrNull().orEmpty() shouldStartWith "serve: strict hydrate"
                        world.errors().firstOrNull().orEmpty() shouldContain "guides/alpha.md"
                        world.errors().firstOrNull().orEmpty() shouldContain fault.reason
                    }
                }
            }
        }
    }

    test("workspace still starts when a listed page GET fails") {
        withReader(workspace = true) { world ->
            world.useFakeObjectStore = true
            world.seedRelease("releases/r1", ReaderReleases.r1)
            world.bucket.failNextGetFor += "releases/r1/guides/alpha.md"
            var starts = 0

            val status = runServer(
                world.config(), world.output.output, world.openers,
                ServerRunControl(startServer = { starts++ }),
            )

            assertSoftly {
                status shouldBe 0
                starts shouldBe 1
                world.errors() shouldBe emptyList()
            }
        }
    }

    test("workspace with committed mirror history ships its bundle on clean stop") {
        withReader(workspace = true, extraEnv = mapOf("PLAINBASE_GIT_ENABLED" to "true")) { world ->
            world.useFakeObjectStore = true
            seedCompleteMirror(world.data)
            world.seedRelease("releases/r1", mapOf("seed.md" to "seed\n".toByteArray()))
            world.config().profile shouldBe DeploymentProfile.WORKSPACE

            val status = runServer(world.config(), world.output.output, world.openers, ServerRunControl(startServer = {}))

            assertSoftly {
                status shouldBe 0
                world.errors() shouldBe emptyList()
                world.bucket.putCount shouldBeGreaterThanOrEqual 1
                world.bucket.putKeys.any { it.endsWith(".plainbase/history.bundle") } shouldBe true
            }
        }
    }

    test("reader requests and a fresh prefix switch leave the bucket unwritten") {
        withReader { world ->
            world.useFakeObjectStore = true
            world.seedRelease("releases/r1", ReaderReleases.r1)
            world.seedRelease("releases/r2", ReaderReleases.r2)
            lateinit var ctx: RouteContext
            var first = emptyMap<ReaderRequest, ReaderAnswer>()
            val alpha = ReaderRequest("/docs/guides/alpha", markdown = true)
            val tree = ReaderRequest("/api/v1/tree")

            val firstStatus = runServer(
                world.config(), world.output.output, world.openers,
                ServerRunControl(
                    onRuntimeContext = { ctx = it },
                    startServer = { first = world.answers(ctx, listOf(alpha, tree)) },
                ),
            )

            assertSoftly {
                firstStatus shouldBe 0
                world.errors() shouldBe emptyList()
                first.getValue(alpha).status shouldBe HttpStatusCode.OK
                first.getValue(alpha).body shouldBe ReaderReleases.r1.getValue("guides/alpha.md").decodeToString()
                first.getValue(tree).status shouldBe HttpStatusCode.OK
                first.getValue(tree).body shouldContain "guides"
                world.bucket.putCount shouldBe 0
                world.bucket.deleteCount shouldBe 0
            }

            val fresh = world.fresh(mapOf("PLAINBASE_S3_PREFIX" to "releases/r2"))
            var second = emptyMap<ReaderRequest, ReaderAnswer>()
            val new = ReaderRequest("/docs/guides/new", markdown = true)
            val gone = ReaderRequest("/docs/guides/gone", markdown = true)
            val secondStatus = runServer(
                fresh.config(), fresh.output.output, fresh.openers,
                ServerRunControl(
                    onRuntimeContext = { ctx = it },
                    startServer = { second = fresh.answers(ctx, listOf(new, gone)) },
                ),
            )

            assertSoftly {
                secondStatus shouldBe 0
                fresh.errors() shouldBe emptyList()
                second.getValue(new).status shouldBe HttpStatusCode.OK
                second.getValue(new).body shouldBe ReaderReleases.r2.getValue("guides/new.md").decodeToString()
                second.getValue(gone).status shouldBe HttpStatusCode.NotFound
                fresh.bucket.putCount shouldBe 0
                fresh.bucket.deleteCount shouldBe 0
            }
        }
    }

    listOf("r2" to ReaderReleases.r2, "r3" to ReaderReleases.r3).forEach { (release, files) ->
        test("fresh $release reader resolves release aliases and permalinks without the removed page") {
            withReader(extraEnv = mapOf("PLAINBASE_S3_PREFIX" to "releases/$release")) { world ->
                world.useFakeObjectStore = true
                world.seedRelease("releases/$release", files)
                val new = ReaderRequest("/docs/guides/new", markdown = true)
                val gone = ReaderRequest("/docs/guides/gone", markdown = true)
                val goneByPath = ReaderRequest("/api/v1/pages/by-path/docs/guides/gone")
                val gonePermalink = ReaderRequest("/p/docs/${ReaderReleases.goneId.value}")
                val guidePermalink = ReaderRequest("/p/docs/${ReaderReleases.guideId.value}")
                val aliases = (if (release == "r2") listOf("alpha") else listOf("alpha", "beta")).map {
                    ReaderRequest("/docs/guides/$it", markdown = true)
                }
                lateinit var ctx: RouteContext
                var answers = emptyMap<ReaderRequest, ReaderAnswer>()

                val status = runServer(
                    world.config(), world.output.output, world.openers,
                    ServerRunControl(
                        onRuntimeContext = { ctx = it },
                        startServer = {
                            answers = world.answers(ctx, listOf(new, gone, goneByPath, gonePermalink, guidePermalink) + aliases)
                        },
                    ),
                )

                val canonical = if (release == "r2") "beta" else "gamma"
                assertSoftly {
                    status shouldBe 0
                    world.errors() shouldBe emptyList()
                    answers.getValue(new).status shouldBe HttpStatusCode.OK
                    answers.getValue(new).body shouldBe files.getValue("guides/new.md").decodeToString()
                    listOf(gone, goneByPath, gonePermalink).forEach { request ->
                        answers.getValue(request).status shouldBe HttpStatusCode.NotFound
                    }
                    aliases.forEach { request ->
                        answers.getValue(request).status shouldBe HttpStatusCode.OK
                        answers.getValue(request).body shouldBe files.getValue("guides/$canonical.md").decodeToString()
                    }
                    answers.getValue(guidePermalink).status shouldBe HttpStatusCode.Found
                    answers.getValue(guidePermalink).location shouldBe "/docs/guides/$canonical"
                    world.bucket.putCount shouldBe 0
                    world.bucket.deleteCount shouldBe 0
                }
            }
        }
    }
})

private fun assertReaderReleaseAnswers(
    answers: Map<ReaderRequest, ReaderAnswer>,
    files: Map<String, ByteArray>,
    guide: String,
) {
    answers.size shouldBe 6
    fun markdown(path: String, bytes: ByteArray) {
        val answer = answers.getValue(ReaderRequest(path, markdown = true))
        answer.status shouldBe HttpStatusCode.OK
        answer.body shouldBe bytes.decodeToString()
    }
    markdown("/docs/index", files.getValue("index.md"))
    markdown("/docs/guides/alpha", files.getValue("guides/$guide.md"))
    val gone = files["guides/gone.md"]
    if (gone != null) {
        markdown("/docs/guides/gone", gone)
    } else {
        answers.getValue(ReaderRequest("/docs/guides/gone", markdown = true)).status shouldBe HttpStatusCode.NotFound
    }
    val byPath = answers.getValue(ReaderRequest("/api/v1/pages/by-path/docs/guides/alpha"))
    byPath.status shouldBe HttpStatusCode.OK
    val page = Json.parseToJsonElement(byPath.body).jsonObject
    page.getValue("id").jsonPrimitive.content shouldBe ReaderReleases.guideId.value
    page.getValue("root").jsonPrimitive.content shouldBe "docs"
    page.getValue("path").jsonPrimitive.content shouldBe "guides/$guide.md"
    page.getValue("url").jsonPrimitive.content shouldBe "/docs/guides/$guide"
    val permalink = answers.getValue(ReaderRequest("/p/docs/${ReaderReleases.guideId.value}"))
    permalink.status shouldBe HttpStatusCode.Found
    permalink.location shouldBe "/docs/guides/$guide"
    val tree = answers.getValue(ReaderRequest("/api/v1/tree"))
    tree.status shouldBe HttpStatusCode.OK
    val root = Json.parseToJsonElement(tree.body).jsonObject.getValue("roots").jsonArray.single().jsonObject
    root.getValue("root").jsonPrimitive.content shouldBe "docs"
    root.getValue("available").jsonPrimitive.content shouldBe "true"
    val guides = root.getValue("tree").jsonObject.getValue("children").jsonArray.map { it.jsonObject }
        .single { it["name"]?.jsonPrimitive?.content == "guides" }
    guides.getValue("type").jsonPrimitive.content shouldBe "folder"
    guides.getValue("path").jsonPrimitive.content shouldBe "guides"
    guides.getValue("children").jsonArray.any {
        it.jsonObject["id"]?.jsonPrimitive?.content == ReaderReleases.guideId.value
    } shouldBe true
}

private data class ReaderOverlapCase(
    val description: String,
    val secondPrefix: String,
    val secondFiles: Map<String, ByteArray>,
    val secondGuide: String,
    val sameRelease: Boolean,
)

private data class ReaderHydrateFault(
    val description: String,
    val reason: String,
    val inject: (ReaderServeWorld, String) -> Unit,
)

private data class ReaderIndexFault(
    val description: String,
    val prefix: String,
    val reason: String,
    val seedAssetsOnly: Boolean = false,
    val removePage: Boolean = false,
    val removeMirror: Boolean = false,
)

private data class ReaderOtherFileFault(
    val description: String,
    val path: String,
    val listedPath: String = path,
    val restoreAfterRebuild: Boolean = true,
)

private val runtimeLogger = KotlinLogging.logger {}
