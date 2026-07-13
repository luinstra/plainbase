package com.plainbase.frameworks.cli

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.filesystem.DataDirLock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * `plainbase root add|remove|list` (multi-root C5). The gate itself is tested in `BootGateTest` (config + FS)
 * and the native suite (git); what is tested HERE is what the COMMAND promises an operator:
 *
 *  - it never opens `plainbase.conf` for writing (asserted on the BYTES);
 *  - a refusal writes nothing at all;
 *  - it refuses iff it INTRODUCES a boot refusal, and WARNS about one it inherited;
 *  - the refusals it does not own come from the LOADER, verbatim - so a hand-written CLI duplicate of one would
 *    fail these tests rather than pass them.
 */
class RootCommandTest : FunSpec({

    /** A DATA_DIR plus a real main content tree, both under one disposable base. */
    fun world(plainbaseConf: String? = null, rootsConf: String? = null, block: (World) -> Unit) {
        val base = Files.createTempDirectory("pb-root-cli")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            plainbaseConf?.let { Files.writeString(data.resolve("plainbase.conf"), it) }
            rootsConf?.let { Files.writeString(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), it) }
            block(World(base, data, content))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    // --- T-CLI-1 / T-CLI-2: the happy path, and the file the CLI must never touch ----------------------

    test("T-CLI-1: add writes roots.conf, exits 0, prints the ABSOLUTE path and 'restart to apply'") {
        world { w ->
            val extra = Files.createDirectory(w.tmp("notes"))
            val out = captureStdout { w.root("add", "notes", extra.toString()) shouldBe 0 }
            out shouldContain extra.toString() // ABSOLUTE: the server's CWD is not the operator's
            out shouldContain "restart the server to apply"
            Files.exists(w.rootsConf) shouldBe true

            // The loader sees it, which is the only claim that matters.
            val roots = w.config().roots
            roots.list.map { it.name.value } shouldBe listOf("main", "notes")
            roots.managed.map { it.value } shouldBe listOf("notes")
            roots.extras.single().localPath shouldBe extra
        }
    }

    test("T-CLI-2: plainbase.conf is BYTE-IDENTICAL before and after every add and remove, comments and all") {
        // D-C5-1's guarantee, and it must fail if anyone ever "improves" the CLI into editing that file. The
        // refusal-to-corrupt is an ABSENCE OF CODE, not a best-effort round trip.
        val conf = """
            # an operator's hand-written file, with comments they care about
            host = "127.0.0.1"   # trailing comment

            roots {
              main { path = "MAIN_PLACEHOLDER" }
            }
        """.trimIndent()
        world { w ->
            Files.writeString(w.data.resolve("plainbase.conf"), conf.replace("MAIN_PLACEHOLDER", w.content.toString()))
            val before = Files.readAllBytes(w.data.resolve("plainbase.conf"))

            val extra = Files.createDirectory(w.tmp("notes"))
            captureStdout { w.root("add", "notes", extra.toString()) shouldBe 0 }
            captureStdout { w.root("remove", "notes") shouldBe 0 }

            Files.readAllBytes(w.data.resolve("plainbase.conf")) shouldBe before
        }
    }

    // --- T-CLI-3 / T-CLI-8 / T-CLI-10: the argv grammar, which makes some boot rules UNREACHABLE ---------

    test("T-CLI-3: `root add main` and `root remove main` are USAGE errors (exit 2) - main is never CLI-managed") {
        world { w ->
            val err = captureStderr {
                w.root("add", "main", "/tmp/whatever") shouldBe 2
                w.root("remove", "main") shouldBe 2
            }
            err shouldContain "'main' is never CLI-managed"
            Files.exists(w.rootsConf) shouldBe false
        }
    }

    test("T-CLI-8: `--history auto` is exit 2 - `auto` is not in the flag's GRAMMAR, so nothing can construct it") {
        // NOT a duplicate of the boot rule that refuses AUTO on an extra. It means the CLI has no code path that
        // can build one at all: a smaller input space, not a second check.
        world { w ->
            val err = captureStderr { w.root("add", "notes", "/tmp/x", "--history", "auto") shouldBe 2 }
            err shouldContain "--history accepts off|native"
        }
    }

    test("T-CLI-10: an unknown subcommand, a missing arg and a surplus arg are all exit 2 with USAGE on stderr") {
        world { w ->
            val err = captureStderr {
                w.root("frobnicate") shouldBe 2
                w.root("add", "onlyname") shouldBe 2
                w.root("remove") shouldBe 2
                w.root("remove", "a", "b") shouldBe 2
                w.root("list", "surplus") shouldBe 2
            }
            err shouldContain "usage: plainbase root"
        }
    }

    // --- T-CLI-3b / T-CLI-6 / T-CLI-7: the refusals the CLI DERIVES rather than writes -------------------

    test("T-CLI-3b: adding a name plainbase.conf already declares fails with the LOADER's overlap message") {
        // DERIVED, not hand-written: the candidate carries `notes` in roots.conf, the two-file overlap `require`
        // fires during the candidate LOAD, and the CLI prints the loader's own text. Asserting on that distinctive
        // text is what makes a hand-written CLI duplicate of this rule FAIL this test rather than pass it.
        world(
            plainbaseConf = """
                roots {
                  main  { path = "CONTENT" }
                  notes { path = "/roots/hand-notes" }
                }
            """.trimIndent(),
        ) { w ->
            w.rewriteMain()
            val err = captureStderr { w.root("add", "notes", w.tmp("x").toString()) shouldBe 1 }
            err shouldContain "declared BOTH in plainbase.conf"
            err shouldContain "Declare each root ONCE"
            Files.exists(w.rootsConf) shouldBe false
        }
    }

    test("T-CLI-6: removing a root while its agentDirectCommit globs exist fails with the LOADER's message") {
        // THE MONOTONICITY COUNTEREXAMPLE. An earlier design proved `remove` could only ever REMOVE violations, so
        // it could always proceed. The proof was true of the topology matrix and FALSE of the boot path: a removal
        // that leaves `auth.agentDirectCommit.roots.notes` behind makes the config UNLOADABLE. It falls straight
        // out of the baseline diff - no per-verb special case, no CLI code at all.
        world(
            plainbaseConf = """
                auth { agentDirectCommit { roots { notes = ["drafts/**"] } } }
            """.trimIndent(),
            rootsConf = """roots { notes { path = "/roots/notes", editable = true } }""",
        ) { w ->
            val before = Files.readAllBytes(w.rootsConf)
            val err = captureStderr { w.root("remove", "notes") shouldBe 1 }
            err shouldContain "names no configured root"
            withClue("a refusal writes NOTHING") { Files.readAllBytes(w.rootsConf) shouldBe before }
        }
    }

    test("T-CLI-6 control: with the glob gone, the same remove SUCCEEDS - so the refusal was the config, not a check") {
        // The load-bearing half: it proves the CLI refused because the config WOULD NOT BOOT, rather than because
        // somebody remembered to write a check.
        world(rootsConf = """roots { notes { path = "/roots/notes", editable = true } }""") { w ->
            captureStdout { w.root("remove", "notes") shouldBe 0 }
            Files.exists(w.rootsConf) shouldBe false
        }
    }

    test("T-CLI-7: `root add` in object mode fails with the loader's roots-plus-object message") {
        // DERIVED. The candidate always carries a `roots {}` block, and object mode plus a roots block does not
        // LOAD - which is also the proof that keeps the gate (and therefore the app DB) out of object mode.
        world { w ->
            val err = captureStderr {
                w.root(
                    "add",
                    "notes",
                    "/tmp/x",
                    env = w.env + mapOf(
                        "PLAINBASE_STORAGE_BACKEND" to "object",
                        "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                        "PLAINBASE_S3_BUCKET" to "docs",
                        "PLAINBASE_S3_ACCESS_KEY_ID" to "k",
                        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "s",
                    ),
                ) shouldBe 1
            }
            err shouldContain "cannot be combined with storage.backend=object"
            Files.exists(w.rootsConf) shouldBe false
        }
    }

    // --- T-CLI-4: a refusal writes NO CANDIDATE BYTES ---------------------------------------------------

    test("T-CLI-4: a nesting add is refused, and NOTHING lands in DATA_DIR but the lock itself") {
        world { w ->
            val outer = Files.createDirectory(w.tmp("outer"))
            val inner = Files.createDirectories(outer.resolve("inner"))
            captureStdout { w.root("add", "outer", outer.toString()) shouldBe 0 }
            val before = Files.readAllBytes(w.rootsConf)

            val err = captureStderr { w.root("add", "inner", inner.toString()) shouldBe 1 }
            err shouldContain "nested inside"

            withClue("roots.conf is byte-identical - no candidate bytes reached disk") {
                Files.readAllBytes(w.rootsConf) shouldBe before
            }
            withClue("and no temp sibling survives - there IS no temp file on the refusal path") {
                Files.list(w.data).use { stream ->
                    stream.map { it.fileName.toString() }
                        .filter { it.endsWith(".tmp") || it.contains(".conf.") }
                        .toList()
                }.shouldBeEmpty()
            }
            // The lock marker and the DATA_DIR it created are EXEMPT, explicitly. `tryAcquire` createDirectories()
            // the DATA_DIR and CREATEs the lock file before the gate ever runs - that is what an advisory lock IS.
            // A blanket "no new file in DATA_DIR" leg would assert something the implementation cannot satisfy, and
            // the first person to run it would delete it. Assert the property that MATTERS and is TRUE.
            Files.exists(w.data.resolve(DataDirLock.ROOTS_LOCK_FILE_NAME)) shouldBe true
        }
    }

    // --- T-CLI-5: the SHADOW refusal - the one place the CLI is STRICTER than boot -----------------------

    context("T-CLI-5 - the shadow refusal catches all THREE collision kinds, and --force overrides") {

        test("(a) a top-level DIRECTORY named guides") {
            world { w ->
                Files.createDirectories(w.content.resolve("guides"))
                Files.writeString(w.content.resolve("guides/page.md"), "---\ntitle: P\n---\n\n# P\n")
                val err = captureStderr { w.root("add", "guides", w.tmp("g").toString()) shouldBe 1 }
                err shouldContain "already a top-level segment of the main root"
                Files.exists(w.rootsConf) shouldBe false
                // --force is the escape, because ADR-0011 D3 rules the check best-effort by nature.
                captureStdout { w.root("add", "guides", w.tmp("g2").toString(), "--force") shouldBe 0 }
                Files.exists(w.rootsConf) shouldBe true
            }
        }

        test("(b) a top-level PAGE whose frontmatter says slug: guides - a check listing directories sails past this") {
            world { w ->
                Files.writeString(w.content.resolve("anything.md"), "---\ntitle: A\nslug: guides\n---\n\n# A\n")
                captureStderr { w.root("add", "guides", w.tmp("g").toString()) shouldBe 1 }
                Files.exists(w.rootsConf) shouldBe false
            }
        }

        test("(c) a top-level FOLDER whose _folder.yaml says slug: guides") {
            world { w ->
                Files.createDirectories(w.content.resolve("Whatever"))
                Files.writeString(w.content.resolve("Whatever/_folder.yaml"), "slug: guides\n")
                Files.writeString(w.content.resolve("Whatever/p.md"), "---\ntitle: P\n---\n\n# P\n")
                captureStderr { w.root("add", "guides", w.tmp("g").toString()) shouldBe 1 }
                Files.exists(w.rootsConf) shouldBe false
            }
        }

        test("the negative control: a name that matches NOTHING is added with no --force") {
            world { w ->
                Files.writeString(w.content.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
                captureStdout { w.root("add", "unrelated", w.tmp("u").toString()) shouldBe 0 }
                Files.exists(w.rootsConf) shouldBe true
            }
        }
    }

    // --- T-CLI-11: removing the LAST managed root DELETES the file --------------------------------------

    test("T-CLI-11: removing the last managed root DELETES roots.conf, returning the install to SYNTHESIZED") {
        // Not an empty `roots {}` husk: that would strand a legacy install in the strict EXPLICIT matrix over a
        // file with nothing in it. The gate ran over the artifact the DELETE actually produces - "there is no
        // roots.conf" - so the artifact validated IS the artifact promoted, even when promoting it means unlinking.
        world { w ->
            val extra = Files.createDirectory(w.tmp("notes"))
            captureStdout { w.root("add", "notes", extra.toString()) shouldBe 0 }
            w.config().roots.origin shouldBe RootsOrigin.EXPLICIT

            captureStdout { w.root("remove", "notes") shouldBe 0 }
            Files.exists(w.rootsConf) shouldBe false
            w.config().roots.origin shouldBe RootsOrigin.SYNTHESIZED
        }
    }

    test("removing one of TWO managed roots rewrites the file and keeps the survivor") {
        world { w ->
            val a = Files.createDirectory(w.tmp("alpha"))
            val b = Files.createDirectory(w.tmp("beta"))
            captureStdout { w.root("add", "alpha", a.toString()) shouldBe 0 }
            captureStdout { w.root("add", "beta", b.toString()) shouldBe 0 }
            captureStdout { w.root("remove", "alpha") shouldBe 0 }

            Files.exists(w.rootsConf) shouldBe true
            w.config().roots.list.map { it.name.value } shouldBe listOf("main", "beta")
        }
    }

    // --- T-CLI-17: the consequence the gate structurally CANNOT check, so it is PRINTED ------------------

    test("T-CLI-17: `root remove` prints the detached-rows outcome AND the all-detached boot refusal") {
        // The D15 refusal reads the app DB, which may not be opened without the DATA_DIR lock - and this command
        // deliberately does not take it. So the exclusion is MITIGATED by saying it out loud. A silent exclusion is
        // how an operator meets an unexplained refusal on their next restart.
        world(rootsConf = """roots { notes { path = "/roots/notes" } }""") { w ->
            val out = captureStdout { w.root("remove", "notes") shouldBe 0 }
            out shouldContain "DETACHED"
            out shouldContain "THE NEXT BOOT WILL REFUSE TO SERVE"
            out shouldContain "APPENDS its rank" // the rename-path consequence
        }
    }

    // --- T-CLI-12: a PRE-EXISTING refusal warns and blocks nothing ---------------------------------------

    test("T-CLI-12: a pre-existing refusal never blocks a verb - the CLI does not hold an operator hostage") {
        // `plainbase.conf` declares two roots NESTED in each other: the config LOADS but `serve` refuses. Under any
        // "refuse if the candidate would refuse" rule, `root remove` refuses too - so THE ONLY COMMAND THAT CAN
        // REPAIR THE CONFIG DECLINES TO RUN BECAUSE THE CONFIG IS BROKEN. That is the single worst thing this CLI
        // could do. Under the baseline diff the refusal is in the BASELINE, so it warns and the verb proceeds.
        world { w ->
            val outer = Files.createDirectory(w.tmp("outer"))
            val inner = Files.createDirectories(outer.resolve("inner"))
            Files.writeString(
                w.data.resolve("plainbase.conf"),
                """
                roots {
                  main  { path = "${w.content}" }
                  outer { path = "$outer" }
                  inner { path = "$inner" }
                }
                """.trimIndent(),
            )
            val extra = Files.createDirectory(w.tmp("notes"))

            val err = captureStderr { captureStdout { w.root("add", "notes", extra.toString()) shouldBe 0 } }
            withClue("the residual failure is printed as a WARNING, naming it") {
                err shouldContain "WARNING"
                err shouldContain "did not cause it"
                err shouldContain "nested inside"
            }
            withClue("and the add SUCCEEDED - the CLI never made this config less bootable") {
                w.config().roots.list.map { it.name.value } shouldBe listOf("main", "outer", "inner", "notes")
            }
        }
    }

    test("T-CLI-12 sibling: on that SAME broken install, an add that introduces a NEW fault is still REFUSED") {
        // Inherit a fault, get warned. INTRODUCE one, get refused. The two cases together are the policy; either
        // alone is a half-test - and the message must name the NEW fault, not the old one.
        world { w ->
            val outer = Files.createDirectory(w.tmp("outer"))
            val inner = Files.createDirectories(outer.resolve("inner"))
            Files.writeString(
                w.data.resolve("plainbase.conf"),
                """
                roots {
                  main  { path = "${w.content}" }
                  outer { path = "$outer" }
                  inner { path = "$inner" }
                }
                """.trimIndent(),
            )
            val nestedInMain = Files.createDirectories(w.content.resolve("deeper"))

            val err = captureStderr { w.root("add", "deeper", nestedInMain.toString()) shouldBe 1 }
            withClue("the message names the NEW nesting (deeper inside main), not the pre-existing outer/inner one") {
                err shouldContain "roots.deeper"
            }
            Files.exists(w.rootsConf) shouldBe false
        }
    }

    // --- T-CLI-9 / T-CLI-9b: list ------------------------------------------------------------------------

    test("T-CLI-9: list shows provenance and an ON-DISK probe, and never claims to know serving state") {
        world(
            plainbaseConf = """
                roots {
                  main { path = "CONTENT" }
                  hand { path = "/roots/hand" }
                }
            """.trimIndent(),
        ) { w ->
            w.rewriteMain()
            val cli = Files.createDirectory(w.tmp("cli"))
            captureStdout { w.root("add", "cli", cli.toString()) shouldBe 0 }

            val out = captureStdout { w.root("list") shouldBe 0 }
            out shouldContain "plainbase.conf"
            out shouldContain PlainbaseConfig.MANAGED_ROOTS_FILE
            out shouldContain "NOT PRESENT" // `hand` points at a path that is not there
            out shouldContain "present"
            out shouldContain "/healthz" // live state is the SERVER's to know, not ours
            withClue("a separate process cannot know in-memory availability, so it must not pretend to") {
                out shouldNotContain "unavailable"
            }
        }
    }

    test("T-CLI-9b: a SYNTHESIZED main is reported as coming from CONTENT_DIR, not from plainbase.conf") {
        // Provenance is a pure function of the ONE snapshot the loader built - `mainDeclared` + `managed` - never a
        // second parse of roots.conf and never a path comparison. There is exactly ONE read, which is the whole
        // reason `list` is safe without a lock.
        world(rootsConf = """roots { notes { path = "/roots/notes" } }""") { w ->
            val out = captureStdout { w.root("list") shouldBe 0 }
            out shouldContain "CONTENT_DIR"
            out shouldContain PlainbaseConfig.MANAGED_ROOTS_FILE
        }
    }

    test("T-CLI-9c: a root it cannot READ is reported NOT READABLE, never `present` - the server would 503 on it") {
        // `present` has to mean what the SERVER means by it. An isDirectory() probe of its own called an
        // unreadable root present, and then the operator met the 503 on their own with the CLI's assurance in
        // hand. The column answers with `rootIsTraversable` now - boot's availability predicate, not a second one.
        world { w ->
            val locked = Files.createDirectory(w.tmp("locked"))
            captureStdout { w.root("add", "locked", locked.toString()) shouldBe 0 }
            if (!w.data.fileSystem.supportedFileAttributeViews().contains("posix")) return@world
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r--r--r--"))
            try {
                if (Files.isExecutable(locked)) return@world // running as root: the permission drop is inert
                val out = captureStdout { w.root("list") shouldBe 0 }
                val row = out.lines().single { it.startsWith("locked") }
                withClue("the locked row, which must not claim to be healthy: '$row'") {
                    row shouldContain "NOT READABLE"
                    row shouldNotContain "present"
                }
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    // --- T-CLI-14: the pre-lock DATA_DIR locator IS the config's own -------------------------------------

    test("T-CLI-14: dataDirFrom(env) equals config.dataDir - set, unset, and against a conf that tries to set it") {
        // If these two ever diverge, the CLI locks a different directory than the one it edits.
        world { w ->
            PlainbaseConfig.dataDirFrom(w.env) shouldBe PlainbaseConfig.fromEnvAndFile(w.env).dataDir
        }
        withClue("unset: the ./data default, resolved identically on both sides") {
            PlainbaseConfig.dataDirFrom(emptyMap()) shouldBe PlainbaseConfig.fromEnvAndFile(emptyMap()).dataDir
        }
        world { w ->
            // dataDir LOCATES the file, so it is the one field that can never come FROM it. A conf that declares
            // one must be ignored.
            Files.writeString(w.data.resolve("plainbase.conf"), """dataDir = "/somewhere/else"""")
            PlainbaseConfig.dataDirFrom(w.env) shouldBe PlainbaseConfig.fromEnvAndFile(w.env).dataDir
            PlainbaseConfig.fromEnvAndFile(w.env).dataDir shouldBe w.data
        }
    }

    // --- the CLI-owned policies boot has no opinion on ---------------------------------------------------

    test("adding a name that already exists is refused - HOCON would field-merge it and silently repoint the path") {
        world { w ->
            val a = Files.createDirectory(w.tmp("notes"))
            val b = Files.createDirectory(w.tmp("notes2"))
            captureStdout { w.root("add", "notes", a.toString()) shouldBe 0 }
            val err = captureStderr { w.root("add", "notes", b.toString()) shouldBe 1 }
            err shouldContain "already exists"
            // Idempotence is NOT silently re-adding: a re-add with a different path is a topology change the
            // operator must state.
            w.config().roots.extras.single().localPath shouldBe a
        }
    }

    test("removing a root that is not there, and removing one plainbase.conf declares, are both refused") {
        world(
            plainbaseConf = """
                roots {
                  main { path = "CONTENT" }
                  hand { path = "/roots/hand" }
                }
            """.trimIndent(),
        ) { w ->
            w.rewriteMain()
            captureStderr { w.root("remove", "ghost") shouldBe 1 } shouldContain "no such root"
            captureStderr { w.root("remove", "hand") shouldBe 1 } shouldContain "declared in plainbase.conf"
        }
    }

    test("every legal root name ROUND-TRIPS through the real loader - including the HOCON directives among them") {
        // `include` is a legal RootName ([a-z0-9][a-z0-9-]*) AND a HOCON keyword. Written as a bare key,
        // `include {` is read as an include DIRECTIVE, not a key, and the file `plainbase root` had just
        // certified bootable does not parse - the one thing this command exists to make impossible.
        //
        // The assertion is the ROUND TRIP, deliberately: a test that asserted on serialize()'s STRING would have
        // watched this go past, because the string looked fine. What HOCON DOES with those bytes is the bug, so
        // the real loader has to be the one to answer.
        world { w ->
            val names = listOf("include", "9lives", "a-b-c", "true")
            names.forEach { name ->
                captureStdout { w.root("add", name, Files.createDirectory(w.tmp(name)).toString()) shouldBe 0 }
            }
            w.config().roots.list.map { it.name.value } shouldBe listOf("main") + names
            names.forEach { name ->
                w.config().roots.extras.single { it.name.value == name }.localPath shouldBe w.tmp(name)
            }
        }
    }

    test("an UNEXPECTED failure is exit 1 through the command's own funnel, never a stack trace at the operator") {
        // The reachable trigger, and the reason the catch is not theatre: a path carrying a control character is
        // REFUSED by `hoconQuote` (a newline in a path is not something to be clever about), and that refusal is
        // thrown from the serializer - outside the loader's error funnel, which only catches what LOADING raises.
        // Without the top-level catch the operator's reward for a stray newline is a JVM trace from the first tool
        // in this codebase that writes their config.
        world { w ->
            w.root("add", "notes", "/tmp/with\nnewline") shouldBe 1
            withClue("and it wrote nothing") { Files.exists(w.rootsConf) shouldBe false }
        }
    }

    test("a missing extra path is ACCEPTED - the server degrades it to 503, so the CLI must not be stricter") {
        // `serve` marks an extra whose store is not there MISSING_AT_BOOT and SKIPS its gate check. A CLI that
        // refused here would be stricter than the server it is configuring - leaky in the OTHER direction.
        world { w ->
            captureStdout { w.root("add", "notyet", "/mnt/not-mounted-yet") shouldBe 0 }
            w.config().roots.extras.single().localPath shouldBe Path.of("/mnt/not-mounted-yet")
        }
    }
})

/** The DATA_DIR + main content tree a `plainbase root` invocation runs against, all under one disposable base. */
private class World(private val base: Path, val data: Path, val content: Path) {
    val env: Map<String, String> = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
    val rootsConf: Path get() = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)

    /** Drives the REAL command, through the REAL env seam - never an injected config (INVARIANT W). */
    fun root(vararg args: String, env: Map<String, String> = this.env): Int = RootCommand.run(args.toList(), env)

    fun config(): PlainbaseConfig = PlainbaseConfig.fromEnvAndFile(env)

    /** A candidate root directory, a SIBLING of DATA_DIR and CONTENT_DIR so it nests inside neither. */
    fun tmp(name: String): Path = base.resolve(name)

    /** Substitutes the real main path into a fixture conf that used a placeholder. */
    fun rewriteMain() {
        val conf = data.resolve("plainbase.conf")
        Files.writeString(conf, Files.readString(conf).replace("CONTENT", content.toString()))
    }
}

private fun captureStdout(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.out
    System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}

private fun captureStderr(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.err
    System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setErr(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}
