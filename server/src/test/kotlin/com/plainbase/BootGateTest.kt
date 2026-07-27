package com.plainbase

import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.config.PlainbaseConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * **The CONFIG + FILESYSTEM half of the shared boot gate (multi-root C5, D-C5-17): COMPLETE, and STRUCTURED.**
 *
 * `requireContentDir()` throws its FIRST failure - which is what an operator sees at boot, and what
 * `RootsValidationTest` has always pinned. `bootRefusals()` returns them ALL, as values. **One implementation,
 * two shapes**, and the completeness is not a nicety: `plainbase root` refuses iff the candidate config
 * INTRODUCES a refusal the current config does not already have, and a validator that stops at its first
 * failure cannot support that diff. A pre-existing fault would MASK a new one, the baseline and the candidate
 * would look identical, and `root add` would write a fresh nesting violation while reporting success.
 *
 * The GIT_GATE half shells out to real `git`, so it lives in the native-tagged suite (project policy tags
 * process execution by default) - see `BootGateNativeTest`.
 */
class BootGateTest : FunSpec({

    fun withDataDir(conf: String? = null, block: (Path, Map<String, String>) -> Unit) {
        val data = Files.createTempDirectory("pb-gate")
        try {
            conf?.let { Files.writeString(data.resolve("plainbase.conf"), it) }
            block(data, mapOf("DATA_DIR" to data.toString()))
        } finally {
            Files.walk(data).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

    // --- T-GATE-1: the gate produces the same refusals boot raises, with the right KIND -----------------

    test("T-GATE-1: a missing CONTENT_DIR is MAIN_UNUSABLE, with the message requireContentDir throws") {
        withDataDir { _, env ->
            val config = PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to "/nope/not/here"))
            val refusal = config.bootRefusals().single()
            refusal.kind shouldBe BootRefusal.Kind.MAIN_UNUSABLE
            refusal.roots shouldBe setOf(RootName.PRIMARY)
            // Message EQUALITY, not similarity: a paraphrase would mean somebody re-implemented something.
            refusal.message shouldBe shouldThrow<IllegalArgumentException> { config.requireContentDir() }.message
        }
    }

    test("T-GATE-1: a NESTED pair is ROOT_PAIR, keyed by BOTH names") {
        val outer = tempDir("pb-gate-outer")
        try {
            val inner = Files.createDirectories(outer.resolve("inner"))
            withDataDir(
                """
                roots {
                  main  { path = "$outer" }
                  inner { path = "$inner" }
                }
                """.trimIndent(),
            ) { _, env ->
                val refusal = PlainbaseConfig.fromEnvAndFile(env).bootRefusals().single()
                refusal.kind shouldBe BootRefusal.Kind.ROOT_PAIR
                // Keyed by the PAIR, so a pre-existing violation between (a, b) cannot mask a new one on (a, c).
                refusal.roots shouldBe setOf(RootName.PRIMARY, RootName.require("inner"))
                refusal.message shouldContain "nested inside"
            }
        } finally {
            outer.toFile().deleteRecursively()
        }
    }

    test("T-GATE-1: two roots at ONE path are ROOT_PAIR - and it is reported ONCE, not three times") {
        val shared = tempDir("pb-gate-shared")
        try {
            withDataDir(
                """
                roots {
                  main { path = "$shared" }
                  twin { path = "$shared" }
                }
                """.trimIndent(),
            ) { _, env ->
                // A path trivially "nests" inside itself both ways, so a naive collector would emit three refusals
                // for one fault. The matrix short-circuits, exactly as the require chain it replaces did.
                val refusal = PlainbaseConfig.fromEnvAndFile(env).bootRefusals().single()
                refusal.kind shouldBe BootRefusal.Kind.ROOT_PAIR
                refusal.message shouldContain "resolve to the same directory"
            }
        } finally {
            shared.toFile().deleteRecursively()
        }
    }

    test("T-GATE-1: the ADR-0008 bind guard is BIND_GUARD, and it is root-independent") {
        val content = tempDir("pb-gate-content")
        try {
            withDataDir { _, env ->
                val config = PlainbaseConfig.fromEnvAndFile(
                    env + mapOf("CONTENT_DIR" to content.toString(), "PLAINBASE_HOST" to "0.0.0.0"),
                )
                val refusal = config.bootRefusals().single { it.kind == BootRefusal.Kind.BIND_GUARD }
                refusal.roots shouldBe emptySet()
                refusal.message shouldBe config.bindGuardRefusal()
            }
        } finally {
            content.toFile().deleteRecursively()
        }
    }

    // --- T-GATE-3: EVERY stage is evaluated. The completeness the baseline diff rests on ----------------

    test("T-GATE-3(b): a config with TWO topology faults reports TWO - this is the one a throw-first validator CANNOT do") {
        // WRITE THIS FIRST AND WATCH IT FAIL against a throw-first `validateExplicitRoots`. Without it a
        // pre-existing fault MASKS a new one, the baseline diff sees no delta, and `root add` writes a fresh
        // nesting violation while reporting success. That RED is the whole argument for the collector, in one line.
        val outer = tempDir("pb-gate-two-outer")
        try {
            val inner = Files.createDirectories(outer.resolve("inner"))
            withDataDir(
                """
                roots {
                  main  { path = "/nope/not/a/directory" }
                  outer { path = "$outer" }
                  inner { path = "$inner" }
                }
                """.trimIndent(),
            ) { _, env ->
                val refusals = PlainbaseConfig.fromEnvAndFile(env).bootRefusals()
                refusals.map { it.kind } shouldContainExactly listOf(
                    BootRefusal.Kind.MAIN_UNUSABLE, // main is not a directory
                    BootRefusal.Kind.ROOT_PAIR, // AND outer/inner nest - a fault BEHIND the first one
                )
                withClue("boot must still refuse with the FIRST message, byte-identical to what it always printed") {
                    shouldThrow<IllegalArgumentException> {
                        PlainbaseConfig.fromEnvAndFile(env).requireContentDir()
                    }.message shouldBe refusals.first().message
                }
            }
        } finally {
            outer.toFile().deleteRecursively()
        }
    }

    // --- T-GATE-3b: the KEY survives the legacy-to-explicit arm switch ----------------------------------

    test("T-GATE-3b: the same DATA_DIR fault keeps its KEY across the legacy/explicit arm switch, with DIFFERENT prose") {
        // THE REASON KINDS EXIST. A legacy `DATA_DIR == CONTENT_DIR` says "DATA_DIR and CONTENT_DIR must be
        // different directories"; the SAME install after one `root add` is EXPLICIT and says "roots.main and
        // DATA_DIR must be different directories". Same fault, same root, DIFFERENT PROSE - so a diff over
        // messages would call it NEW and refuse an add that introduced nothing, trapping exactly the operator the
        // policy exists to protect. Diff the KEY.
        val data = Files.createTempDirectory("pb-gate-armswitch")
        try {
            val legacy = PlainbaseConfig.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to data.toString()),
            )
            Files.writeString(
                data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE),
                """roots { notes { path = "/roots/notes" } }""",
            )
            val explicit = PlainbaseConfig.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to data.toString()),
            )

            val legacyRefusal = legacy.bootRefusals().single { it.kind == BootRefusal.Kind.ROOT_VS_DATA_DIR }
            val explicitRefusal = explicit.bootRefusals().single { it.kind == BootRefusal.Kind.ROOT_VS_DATA_DIR }

            withClue("equal KEYS: the diff must see one unchanged fault, not a new one") {
                legacyRefusal.key shouldBe explicitRefusal.key
                legacyRefusal.key shouldBe (BootRefusal.Kind.ROOT_VS_DATA_DIR to setOf(RootName.PRIMARY))
            }
            withClue("DIFFERENT prose: this is what proves a message diff would have called it new") {
                legacyRefusal.message shouldNotBe explicitRefusal.message
                legacyRefusal.message shouldContain "DATA_DIR and CONTENT_DIR"
                explicitRefusal.message shouldContain "roots.main and DATA_DIR"
            }
        } finally {
            Files.walk(data).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    test("T-GATE-3b: a READABLE-BUT-NOT-SEARCHABLE main keys the same across the arm switch, in BOTH arms") {
        // The other half of T-GATE-3b, and the half that was WRONG: equal KINDS are only equal KEYS if both arms
        // raise the fault on the same CONDITION. The legacy arm probed `isDirectory` alone, so a main with no
        // search bit was silently fine there and MAIN_UNUSABLE in the explicit matrix - and `root add` on a
        // synthesized-main install then saw a key its baseline structurally could not produce, called the
        // operator's own broken permissions a fault IT had introduced, and refused an add that introduced nothing.
        val data = Files.createTempDirectory("pb-gate-perm-data")
        val content = tempDir("pb-gate-perm-content")
        if (!content.fileSystem.supportedFileAttributeViews().contains("posix")) return@test
        // r-- : the directory lists but nothing under it can be opened - a directory needs `x` to be traversed.
        Files.setPosixFilePermissions(content, PosixFilePermissions.fromString("r--r--r--"))
        if (Files.isExecutable(content)) return@test // running as root: the permission drop is inert
        try {
            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
            val legacy = PlainbaseConfig.fromEnvAndFile(env)
            Files.writeString(
                data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE),
                """roots { notes { path = "/roots/notes" } }""",
            )
            val explicit = PlainbaseConfig.fromEnvAndFile(env)

            val legacyRefusal = legacy.bootRefusals().single { it.kind == BootRefusal.Kind.MAIN_UNUSABLE }
            val explicitRefusal = explicit.bootRefusals().single { it.kind == BootRefusal.Kind.MAIN_UNUSABLE }

            withClue("equal KEYS: one unchanged fault, so `root add` warns and proceeds instead of taking a hostage") {
                legacyRefusal.key shouldBe explicitRefusal.key
                legacyRefusal.key shouldBe (BootRefusal.Kind.MAIN_UNUSABLE to setOf(RootName.PRIMARY))
            }
            withClue("DIFFERENT prose: each arm still names the key the operator actually wrote") {
                legacyRefusal.message shouldContain "CONTENT_DIR is not readable/searchable"
                explicitRefusal.message shouldContain "roots.main.path is not readable/searchable"
            }
        } finally {
            Files.setPosixFilePermissions(content, PosixFilePermissions.fromString("rwxr-xr-x"))
            content.toFile().deleteRecursively()
            data.toFile().deleteRecursively()
        }
    }

    test("a clean config produces NO refusals - the anti-vacuous leg") {
        val content = tempDir("pb-gate-clean")
        try {
            withDataDir { _, env ->
                PlainbaseConfig.fromEnvAndFile(env + ("CONTENT_DIR" to content.toString()))
                    .bootRefusals()
                    .shouldContainExactly(emptyList())
            }
        } finally {
            content.toFile().deleteRecursively()
        }
    }
})
