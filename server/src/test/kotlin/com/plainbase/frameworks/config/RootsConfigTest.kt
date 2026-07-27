package com.plainbase.frameworks.config

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `roots {}` parse layer (ADR-0011, multi-root C1): the explicit-block grammar, the D7
 * origin-line-with-name-tiebreak ordering, every parse-time refusal (each naming the offending
 * entry), the D10/D11/D12 boundaries, and the back-compat synthesis that keeps every legacy config
 * byte-identical. Filesystem rules live in [RootsValidationTest]; parse stays pure.
 */
class RootsConfigTest : FunSpec({

    fun withDataDir(conf: String?, block: (Map<String, String>) -> Unit) {
        val data = Files.createTempDirectory("pb-roots-config")
        try {
            if (conf != null) Files.writeString(data.resolve("plainbase.conf"), conf)
            block(mapOf("DATA_DIR" to data.toString()))
        } finally {
            Files.walk(data).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun root(name: String) = Root(
        name = RootName.require(name),
        backend = RootBackend.Local(Path.of("/roots", name)),
        editable = true,
        history = HistoryMode.OFF,
    )

    // --- explicit-block parsing --------------------------------------------------------------------

    test("a full block round-trips: names, normalized absolute paths, explicit and defaulted knobs") {
        withDataDir(
            """
            roots {
              main    { path = "/roots/sub/../docs" }
              memoria { path = "/roots/memoria", history = "NaTiVe" }
              notes   { path = "/roots/notes", editable = true }
            }
            """.trimIndent(),
        ) { env ->
            val roots = PlainbaseConfig.fromEnvAndFile(env).roots
            roots.origin shouldBe RootsOrigin.EXPLICIT
            roots.list shouldBe listOf(
                Root(RootName.require("main"), RootBackend.Local(Path.of("/roots/docs")), editable = true, history = HistoryMode.AUTO),
                Root(
                    RootName.require("memoria"),
                    RootBackend.Local(Path.of("/roots/memoria")),
                    editable = false,
                    history = HistoryMode.NATIVE,
                ),
                Root(RootName.require("notes"), RootBackend.Local(Path.of("/roots/notes")), editable = true, history = HistoryMode.OFF),
            )
            roots.primary shouldBe roots.list.first()
        }
    }

    test("entries parse in origin-line order, not HOCON iteration order (D7)") {
        withDataDir(
            """
            roots {
              zeta  { path = "/roots/z" }
              main  { path = "/roots/m" }
              alpha { path = "/roots/a" }
            }
            """.trimIndent(),
        ) { env ->
            PlainbaseConfig.fromEnvAndFile(env).roots.list.map { it.name.value } shouldBe listOf("zeta", "main", "alpha")
        }
    }

    test("two roots declared on one line order alphabetically by the name tiebreak (D7)") {
        withDataDir(
            """
            roots {
              main { path = "/roots/m" }
              zeta { path = "/roots/z" }, alpha { path = "/roots/a" }
            }
            """.trimIndent(),
        ) { env ->
            PlainbaseConfig.fromEnvAndFile(env).roots.list.map { it.name.value } shouldBe listOf("main", "alpha", "zeta")
        }
    }

    // --- parse-time refusals (each names the offending entry) ---------------------------------------

    test("an invalid slug key fails naming the key and the rule") {
        withDataDir("""roots { main { path = "/roots/m" }, "Bad_Name" { path = "/roots/b" } }""") { env ->
            val failure = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            failure.message shouldContain "roots.Bad_Name"
            failure.message shouldContain "[a-z][a-z0-9]*(-[a-z0-9]+)*"
        }
    }

    test("a reserved segment fails naming the key, even though it is a legal slug") {
        // `api` parses fine; what refuses it is the reserved list, so the message has to say that rather than
        // repeat the grammar. The fixture declares `main` because a block that omits the primary is refused
        // FIRST, and this row would then pass without ever reaching the reserved check.
        withDataDir("""roots { main { path = "/roots/m" }, api { path = "/roots/a" } }""") { env ->
            val failure = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            failure.message shouldContain "roots.api"
            failure.message shouldContain "reserved"
        }
    }

    test("a page-id-shaped root key fails with the permalink ambiguity message") {
        val hex32 = "a".repeat(32)
        withDataDir("""roots { main { path = "/roots/m" }, "$hex32" { path = "/roots/b" } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "look like a page id"
        }
    }

    test("a block without a main root fails naming the required primary") {
        withDataDir("""roots { extra { path = "/roots/e" } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "must declare a root named 'main'"
        }
    }

    test("an empty roots block fails the required-main rule") {
        withDataDir("roots {}") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "must declare a root named 'main'"
        }
    }

    test("a missing path fails naming the entry") {
        withDataDir("""roots { main { editable = true } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "roots.main.path is required"
        }
    }

    test("a blank or whitespace path fails naming the entry (Path.of of a blank resolves to the working directory)") {
        listOf("""roots { main { path = "" } }""", """roots { main { path = "   " } }""").forEach { conf ->
            withDataDir(conf) { env ->
                shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                    .message shouldContain "roots.main.path is required and must be a non-blank directory path"
            }
        }
    }

    test("a non-local backend fails naming the entry, on main and on an extra alike") {
        withDataDir("""roots { main { path = "/roots/m", backend = object } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "roots.main.backend 'object'"
        }
        withDataDir("""roots { main { path = "/roots/m" }, extra { path = "/roots/e", backend = s3 } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "roots.extra.backend 's3'"
        }
    }

    test("a non-object entry fails telling the operator the block shape") {
        withDataDir("""roots { main = "/roots/m" }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "roots.main must be a block"
        }
    }

    test("a bad editable value fails naming the full key") {
        withDataDir("""roots { main { path = "/roots/m", editable = maybe } }""") { env ->
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                .message shouldContain "roots.main.editable must be one of 1/0/true/false"
        }
    }

    test("a bad history value fails naming the full key and the legal values") {
        withDataDir("""roots { main { path = "/roots/m", history = sometimes } }""") { env ->
            val failure = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            failure.message shouldContain "roots.main.history 'sometimes'"
            failure.message shouldContain "off, auto, native"
        }
    }

    test("explicit main history and the legacy git switch cannot contradict each other") {
        listOf(
            Triple("native", false, "history = native and git.enabled = false"),
            Triple("off", true, "history = off and git.enabled = true"),
        ).forEach { (history, gitEnabled, expected) ->
            withDataDir(
                """
                roots { main { path = "/roots/m", history = $history } }
                git { enabled = $gitEnabled }
                """.trimIndent(),
            ) { env ->
                shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
                    .message shouldContain expected
            }
        }
    }

    test("an explicit roots block combined with storage.backend=object is a boot error (D10)") {
        withDataDir("""roots { main { path = "/roots/m" } }""") { env ->
            val objectEnv = env + mapOf(
                "PLAINBASE_STORAGE_BACKEND" to "object",
                "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                "PLAINBASE_S3_BUCKET" to "docs",
                "PLAINBASE_S3_ACCESS_KEY_ID" to "k",
                "PLAINBASE_S3_SECRET_ACCESS_KEY" to "s",
            )
            shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(objectEnv) }
                .message shouldContain "roots {} cannot be combined with storage.backend=object"
        }
    }

    test("a directly constructed object config reasserts the explicit-roots refusal at the boot gate") {
        val config = PlainbaseConfig(
            contentDir = Path.of("/roots/ignored"),
            dataDir = Path.of("/data/plainbase"),
            host = "127.0.0.1",
            port = PlainbaseConfig.DEFAULT_PORT,
            storage = StorageConfig(
                backend = StorageBackend.OBJECT,
                endpoint = "https://acct.example.com",
                bucket = "docs",
                accessKeyId = "k",
                secretAccessKey = "s",
            ),
            roots = RootsConfig.of(listOf(root("main")), RootsOrigin.EXPLICIT),
        )

        config.bootRefusals().single().message shouldContain "roots {} cannot be combined with storage.backend=object"
    }

    // --- warnings (D11/D12) --------------------------------------------------------------------------

    test("an explicit block with an explicitly set CONTENT_DIR warns that the legacy key is ignored (D11)") {
        withDataDir("""roots { main { path = "/roots/m" } }""") { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/legacy"))
            config.rootsWarnings().any { it.contains("CONTENT_DIR") && it.contains("ignored") } shouldBe true
            config.mainContentRoot() shouldBe Path.of("/roots/m")
        }
    }

    test("the C1 unserved-extras warning is RETIRED: extras ARE served now, so warning about them would be a lie") {
        withDataDir("""roots { main { path = "/roots/m" }, memoria { path = "/roots/mem" }, notes { path = "/roots/n" } }""") { env ->
            PlainbaseConfig.fromEnvAndFile(env).rootsWarnings().any { it.contains("only main is served") } shouldBe false
        }
    }

    test("a synthesized (legacy) config emits no roots warnings, even with CONTENT_DIR set") {
        withDataDir(conf = null) { env ->
            PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/legacy")).rootsWarnings().shouldBeEmpty()
        }
    }

    test("the C1 dormant-knob warning is RETIRED: editable/history ARE enforced now") {
        withDataDir("""roots { main { path = "/roots/m", editable = false } }""") { env ->
            PlainbaseConfig.fromEnvAndFile(env).rootsWarnings().any { it.contains("not yet enforced") } shouldBe false
        }
    }

    // --- back-compat synthesis + the mainContentRoot equal-value invariant ---------------------------

    test("a contentDir-only config synthesizes main identically via env and via the file key") {
        val expectedRoot = { dir: Path ->
            listOf(Root(RootName.PRIMARY, RootBackend.Local(dir), editable = true, history = HistoryMode.AUTO))
        }
        withDataDir(conf = null) { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs"))
            config.roots.origin shouldBe RootsOrigin.SYNTHESIZED
            config.roots.list shouldBe expectedRoot(Path.of("/roots/docs"))
        }
        withDataDir("""contentDir = "/roots/docs"""") { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env)
            config.roots.origin shouldBe RootsOrigin.SYNTHESIZED
            config.roots.list shouldBe expectedRoot(Path.of("/roots/docs"))
        }
    }

    test("a legacy env-only config equals the hand-built expected config field for field") {
        withDataDir(conf = null) { env ->
            val legacyEnv = env + ("CONTENT_DIR" to "/roots/docs")
            PlainbaseConfig.fromEnvAndFile(legacyEnv) shouldBe PlainbaseConfig(
                contentDir = Path.of("/roots/docs"),
                dataDir = Path.of(env.getValue("DATA_DIR")),
                host = PlainbaseConfig.DEFAULT_HOST,
                port = PlainbaseConfig.DEFAULT_PORT,
                contentDirSource = ConfigSource.ENV,
            )
        }
    }

    test("object-mode synthesis carries the bucket descriptor and falls back to contentDir for the local seams") {
        val config = PlainbaseConfig.fromEnv(
            mapOf(
                "CONTENT_DIR" to "/roots/ignored",
                "PLAINBASE_STORAGE_BACKEND" to "object",
                "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                "PLAINBASE_S3_BUCKET" to "docs",
                "PLAINBASE_S3_PREFIX" to "corp",
                "PLAINBASE_S3_ACCESS_KEY_ID" to "k",
                "PLAINBASE_S3_SECRET_ACCESS_KEY" to "s",
            ),
        )
        config.roots.origin shouldBe RootsOrigin.SYNTHESIZED
        config.roots.primary.backend shouldBe RootBackend.Object(bucket = "docs", prefix = "corp")
        config.roots.primary.localPath shouldBe null
        config.mainContentRoot() shouldBe config.contentDir
    }

    test("mainContentRoot equals contentDir for local synthesis and roots.main.path for an explicit block") {
        withDataDir(conf = null) { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/roots/docs"))
            config.mainContentRoot() shouldBe config.contentDir
        }
        withDataDir("""roots { main { path = "/roots/elsewhere" } }""") { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env)
            config.mainContentRoot() shouldBe Path.of("/roots/elsewhere")
        }
    }

    test("copy(storage = object) keeps the stale synthesized main but mainContentRoot still equals contentDir") {
        // The roots default runs at CONSTRUCTION only - this pins the equal-value invariant that keeps
        // every copy()-built test config correct (the PlainbaseConfig.roots KDoc rule).
        val copied = PlainbaseConfig.fromEnv(emptyMap()).copy(
            storage = StorageConfig(
                backend = StorageBackend.OBJECT,
                endpoint = "https://acct.example.com",
                bucket = "docs",
                accessKeyId = "k",
                secretAccessKey = "s",
            ),
        )
        copied.mainContentRoot() shouldBe copied.contentDir
    }

    // --- the canonical snapshot ----------------------------------------------------------------------

    test("of() snapshots the caller's list: mutating it afterwards cannot desync list, primary or extras") {
        val declared = mutableListOf(root("zeta"), root("main"))
        val roots = RootsConfig.of(list = declared, origin = RootsOrigin.EXPLICIT)
        declared.add(root("alpha"))
        declared.removeAt(0)
        roots.list.map { it.name.value } shouldBe listOf("zeta", "main")
        roots.primary.name shouldBe RootName.PRIMARY
        roots.extras.map { it.name.value } shouldBe listOf("zeta")
    }

    test("a directly-constructed RootsConfig with no main is refused at construction, not at first access") {
        shouldThrow<IllegalArgumentException> {
            RootsConfig.of(list = listOf(root("zeta")), origin = RootsOrigin.EXPLICIT)
        }.message shouldContain "no 'main' root"
    }

    test("a directly-constructed RootsConfig with a reserved name is refused too - the parser is not the only door") {
        // main is present and there is no duplicate, so the two requires above this one both pass and the
        // reserved check is the sole possible cause.
        val failure = shouldThrow<IllegalArgumentException> {
            RootsConfig.of(list = listOf(root("main"), root("assets")), origin = RootsOrigin.EXPLICIT)
        }
        failure.message shouldContain "assets"
        failure.message shouldContain "reserved"
    }
})
