package com.plainbase.frameworks.config

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The `requireContentDir` filesystem matrix (ADR-0011 D9/D13): a synthesized (legacy) config runs
 * today's guard verbatim - the byte-identical regression pins - while an explicit `roots {}` block
 * gets the strict matrix (toRealPath duplicates/nesting, DATA_DIR cross terms, D13 fallbacks).
 * Temp dirs are toRealPath'd up front so the declared-form fallbacks compare cleanly on macOS
 * (where the temp root itself is a symlink); the one symlink test builds its own link on purpose.
 */
class RootsValidationTest : FunSpec({

    fun withBase(block: (base: Path) -> Unit) {
        val base = Files.createTempDirectory("pb-roots-validation").toRealPath()
        try {
            block(base)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    fun explicitRoots(vararg roots: Pair<String, Path>) = RootsConfig.of(
        list = roots.map { (name, path) ->
            Root(
                name = RootName.require(name),
                backend = RootBackend.Local(path.toAbsolutePath().normalize()),
                editable = name == "docs",
                history = if (name == "docs") HistoryMode.AUTO else HistoryMode.OFF,
            )
        },
        origin = RootsOrigin.EXPLICIT,
    )

    fun config(dataDir: Path, contentDir: Path, roots: RootsConfig) = PlainbaseConfig(
        contentDir = contentDir,
        dataDir = dataDir,
        host = "127.0.0.1",
        port = PlainbaseConfig.DEFAULT_PORT,
        roots = roots,
    )

    fun legacyConfig(dataDir: Path, contentDir: Path) = PlainbaseConfig(
        contentDir = contentDir,
        dataDir = dataDir,
        host = "127.0.0.1",
        port = PlainbaseConfig.DEFAULT_PORT,
    )

    // --- D9 regression pins: a synthesized config runs today's guard verbatim ------------------------

    test("synthesized: a missing CONTENT_DIR keeps today's exact message") {
        withBase { base ->
            val failure = shouldThrow<IllegalArgumentException> {
                legacyConfig(dataDir = base.resolve("data"), contentDir = base.resolve("missing")).requireContentDir()
            }
            failure.message shouldContain "CONTENT_DIR does not exist or is not a directory"
        }
    }

    test("synthesized: a CONTENT_DIR that exists but cannot be traversed is fatal, like the explicit main it mirrors") {
        // The one guard the synthesized arm did NOT inherit, and the omission was not conservative: it made the two
        // arms raise MAIN_UNUSABLE on DIFFERENT conditions, so `plainbase root add` on a legacy install read this
        // permission fault (which its baseline could not see, and its explicit candidate could) as one IT had caused,
        // and refused. Same fault, same key, both arms - the prose is all that differs.
        withBase { base ->
            if (!base.fileSystem.supportedFileAttributeViews().contains("posix")) return@withBase
            val content = Files.createDirectories(base.resolve("content"))
            Files.setPosixFilePermissions(content, PosixFilePermissions.fromString("r--r--r--"))
            try {
                if (Files.isExecutable(content)) return@withBase // running as root: the permission drop is inert
                val failure = shouldThrow<IllegalArgumentException> {
                    legacyConfig(dataDir = base.resolve("data"), contentDir = content).requireContentDir()
                }
                failure.message shouldContain "CONTENT_DIR is not readable/searchable"
            } finally {
                Files.setPosixFilePermissions(content, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    test("synthesized: DATA_DIR == CONTENT_DIR keeps today's exact message") {
        withBase { base ->
            val failure = shouldThrow<IllegalArgumentException> {
                legacyConfig(dataDir = base, contentDir = base).requireContentDir()
            }
            failure.message shouldContain "DATA_DIR and CONTENT_DIR must be different directories"
        }
    }

    test("synthesized: strict nesting either way stays LEGAL, exactly as today") {
        withBase { base ->
            val content = Files.createDirectories(base.resolve("content"))
            val nestedData = Files.createDirectories(content.resolve("data"))
            legacyConfig(dataDir = nestedData, contentDir = content).requireContentDir() shouldBe content

            val data = Files.createDirectories(base.resolve("outer"))
            val nestedContent = Files.createDirectories(data.resolve("content"))
            legacyConfig(dataDir = data, contentDir = nestedContent).requireContentDir() shouldBe nestedContent
        }
    }

    test("synthesized wiring seam: the registry's primary resolves to contentDir for a legacy config") {
        withBase { base ->
            val content = Files.createDirectories(base.resolve("content"))
            val config = legacyConfig(dataDir = base.resolve("data"), contentDir = content)
            RootRegistry.of(config.roots.list).primary.localPath shouldBe content
            config.mainContentRoot() shouldBe content
        }
    }

    // --- the explicit matrix --------------------------------------------------------------------------

    test("explicit: a missing main path is fatal, naming roots.docs") {
        withBase { base ->
            val failure = shouldThrow<IllegalArgumentException> {
                config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to base.resolve("missing")))
                    .requireContentDir()
            }
            failure.message shouldContain "roots.docs.path does not exist or is not a directory"
        }
    }

    test("explicit: a missing extra path boots, and rootsWarnings names it (D13)") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val cfg = config(
                base.resolve("data"),
                base.resolve("legacy"),
                explicitRoots("docs" to main, "extra" to base.resolve("gone")),
            )
            cfg.requireContentDir() shouldBe main
            cfg.rootsWarnings().any { it.contains("roots.extra.path") && it.contains(base.resolve("gone").toString()) } shouldBe true
        }
    }

    test("explicit: a duplicate path via a symlink is refused (toRealPath catches it)") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val link = Files.createSymbolicLink(base.resolve("docs-link"), main)
            val failure = shouldThrow<IllegalArgumentException> {
                config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main, "twin" to link))
                    .requireContentDir()
            }
            failure.message shouldContain "resolve to the same directory"
            failure.message shouldContain "twin"
        }
    }

    test("explicit: nested roots are refused, naming ancestor and descendant") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val nested = Files.createDirectories(main.resolve("sub"))
            val failure = shouldThrow<IllegalArgumentException> {
                config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main, "inner" to nested))
                    .requireContentDir()
            }
            failure.message shouldContain "nested inside"
            failure.message shouldContain "roots.inner"
            failure.message shouldContain "roots.docs"
        }
    }

    test("explicit: a root equal to DATA_DIR is refused") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val data = Files.createDirectories(base.resolve("data"))
            val failure = shouldThrow<IllegalArgumentException> {
                config(data, base.resolve("legacy"), explicitRoots("docs" to main, "extra" to data)).requireContentDir()
            }
            failure.message shouldContain "roots.extra and DATA_DIR must be different directories"
        }
    }

    // A root strictly INSIDE DATA_DIR used to be REFUSED here and PERMITTED in the legacy arm, which is one condition
    // with two answers, chosen by which arm the install happened to be in. It was not a stricter reading of a shared
    // rule - it was a rule the other arm did not have, and it bricked `plainbase root add` on any legacy install whose
    // CONTENT_DIR sat inside DATA_DIR (`DATA_DIR=/data CONTENT_DIR=/data/content` - a layout the legacy arm has always
    // permitted, that boots fine, and that a single `root add` turned into a config the server refused to start: the
    // baseline saw no fault, the explicit candidate saw one, so the CLI read it as a fault IT had introduced).
    //
    // It is now a WARN, in BOTH arms and for EVERY root. Nothing the app writes lands under such a root - app state
    // sits directly in DATA_DIR, a sibling of it - so there is no rebuild loop and nothing is mis-served. What IS true
    // is that ADR-0004 tells operators DATA_DIR holds derived state they may delete to rebuild, and here that would
    // take the corpus: an operator trap, which is what rootsWarnings() is for.
    test("explicit: a root strictly inside DATA_DIR boots with a WARN, symmetrically with the legacy arm") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val data = Files.createDirectories(base.resolve("data"))
            val inside = Files.createDirectories(data.resolve("root"))
            val cfg = config(data, base.resolve("legacy"), explicitRoots("docs" to main, "extra" to inside))
            cfg.requireContentDir() shouldBe main
            cfg.rootsWarnings().any { it.contains("roots.extra") && it.contains("is INSIDE DATA_DIR") } shouldBe true
        }
    }

    test("legacy: a CONTENT_DIR inside DATA_DIR gets the SAME warning, and main is not special about it") {
        withBase { base ->
            val data = Files.createDirectories(base.resolve("data"))
            val content = Files.createDirectories(data.resolve("content"))
            val cfg = legacyConfig(dataDir = data, contentDir = content)
            cfg.requireContentDir() shouldBe content
            cfg.rootsWarnings().any { it.contains("roots.docs") && it.contains("is INSIDE DATA_DIR") } shouldBe true
        }
    }

    // The half that IS still fatal, and the reason the demotion above is safe: DATA_DIR physically inside a root but
    // DECLARED through an alias dodges the store's LEXICAL DATA_DIR exclusion, so the app's own state gets indexed and
    // served as content. The legacy arm never had this guard - it does now, keyed identically.
    test("legacy: DATA_DIR inside CONTENT_DIR only via a symlink-aliased declaration is refused, as in the explicit arm") {
        withBase { base ->
            val docs = Files.createDirectories(base.resolve("docs"))
            val link = Files.createSymbolicLink(base.resolve("docs-link"), docs)
            val data = Files.createDirectories(docs.resolve("data"))
            val failure = shouldThrow<IllegalArgumentException> {
                legacyConfig(dataDir = data, contentDir = link).requireContentDir()
            }
            failure.message shouldContain "declare CONTENT_DIR and DATA_DIR through consistent paths"
        }
    }

    test("explicit: DATA_DIR strictly inside a root stays legal (it feeds that root's watcher exclusion in C4)") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val data = Files.createDirectories(main.resolve("data"))
            config(data, base.resolve("legacy"), explicitRoots("docs" to main)).requireContentDir() shouldBe main
        }
    }

    test("explicit: DATA_DIR inside a root only via a symlink-aliased declaration is refused (the exclusion is lexical)") {
        withBase { base ->
            val docs = Files.createDirectories(base.resolve("docs"))
            val link = Files.createSymbolicLink(base.resolve("docs-link"), docs)
            val data = Files.createDirectories(docs.resolve("data"))
            // On disk DATA_DIR sits inside the root, but the root is DECLARED through the symlink, so
            // the store's lexical DATA_DIR exclusion would never match and app state would be indexed.
            val failure = shouldThrow<IllegalArgumentException> {
                config(data, base.resolve("legacy"), explicitRoots("docs" to link)).requireContentDir()
            }
            failure.message shouldContain "declare the root and DATA_DIR through consistent paths"
        }
    }

    test("explicit: a MISSING DATA_DIR declared through a symlinked ancestor into a root is refused (first boot)") {
        withBase { base ->
            val docs = Files.createDirectories(base.resolve("docs"))
            val alias = Files.createSymbolicLink(base.resolve("alias"), docs)
            // DATA_DIR does not exist yet; a plain normalize() would keep the alias form, miss the
            // nesting, and DataDirLock would then CREATE the data dir physically inside the served
            // tree. The best-effort canonicalization resolves the existing symlinked ancestor.
            shouldThrow<IllegalArgumentException> {
                config(alias.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to docs)).requireContentDir()
            }.message shouldContain "declare the root and DATA_DIR through consistent paths"
        }
    }

    test("explicit: a nonexistent DATA_DIR (first boot) validates with the best-effort fallback, nothing escapes") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            shouldNotThrowAny {
                config(base.resolve("data-not-created-yet"), base.resolve("legacy"), explicitRoots("docs" to main))
                    .requireContentDir()
            }
        }
    }

    test("explicit: two extras with the same nonexistent path still collide via the declared-form fallback (D13)") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val gone = base.resolve("gone")
            val failure = shouldThrow<IllegalArgumentException> {
                config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main, "one" to gone, "two" to gone))
                    .requireContentDir()
            }
            failure.message shouldContain "resolve to the same directory"
        }
    }

    // D13 participation is what is actually pinned here, and it survives the demotion: an extra whose path does not
    // exist still gets a comparable form, so it is still SEEN against DATA_DIR rather than silently skipped. The
    // verdict it earns is now the WARN (see above), not a refusal - what must not happen is that it is not judged.
    test("explicit: a nonexistent extra nested inside DATA_DIR still participates via the declared-form fallback (D13)") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val data = Files.createDirectories(base.resolve("data"))
            val cfg = config(data, base.resolve("legacy"), explicitRoots("docs" to main, "extra" to data.resolve("gone")))
            cfg.requireContentDir() shouldBe main
            cfg.rootsWarnings().any { it.contains("roots.extra") && it.contains("is INSIDE DATA_DIR") } shouldBe true
        }
    }

    test("explicit: an unreadable extra still participates in the duplicate check; without a collision it boots with the WARN") {
        withBase { base ->
            if (!base.fileSystem.supportedFileAttributeViews().contains("posix")) return@withBase
            val main = Files.createDirectories(base.resolve("docs"))
            val locked = Files.createDirectories(base.resolve("locked"))
            Files.setPosixFilePermissions(locked, emptySet<PosixFilePermission>())
            try {
                if (Files.isReadable(locked)) return@withBase // running as root: the permission drop is inert, the case is unprovable

                // Participation: two roots declaring the unreadable path still collide via the declared form.
                shouldThrow<IllegalArgumentException> {
                    config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main, "one" to locked, "two" to locked))
                        .requireContentDir()
                }.message shouldContain "resolve to the same directory"

                // No collision: boots, and the D13 warning names the unreadable extra - never a silent skip.
                val cfg = config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main, "extra" to locked))
                cfg.requireContentDir() shouldBe main
                cfg.rootsWarnings().any { it.contains("roots.extra.path") } shouldBe true
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    test("explicit: a read-only (unsearchable) extra counts as unavailable and gets the D13 WARN") {
        withBase { base ->
            if (!base.fileSystem.supportedFileAttributeViews().contains("posix")) return@withBase
            val main = Files.createDirectories(base.resolve("docs"))
            val readonly = Files.createDirectories(base.resolve("readonly"))
            Files.setPosixFilePermissions(readonly, PosixFilePermissions.fromString("r--r--r--"))
            try {
                if (Files.isExecutable(readonly)) return@withBase // running as root: the permission drop is inert
                val cfg = config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main, "extra" to readonly))
                cfg.requireContentDir() shouldBe main
                cfg.rootsWarnings().any { it.contains("roots.extra.path") } shouldBe true
            } finally {
                Files.setPosixFilePermissions(readonly, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    test("explicit: a main directory missing the read bit or the execute bit fails fast via the permission guard") {
        // Two halves of the same guard: execute-only (unreadable) and read-only (unsearchable - a
        // directory needs x to be traversed, so a scan over it would die mid-walk).
        listOf("--x--x--x" to { p: Path -> Files.isReadable(p) }, "r--r--r--" to { p: Path -> Files.isExecutable(p) })
            .forEach { (perms, inertWhen) ->
                withBase { base ->
                    if (!base.fileSystem.supportedFileAttributeViews().contains("posix")) return@withBase
                    val main = Files.createDirectories(base.resolve("docs"))
                    Files.setPosixFilePermissions(main, PosixFilePermissions.fromString(perms))
                    try {
                        if (inertWhen(main)) return@withBase // running as root: the permission drop is inert
                        val failure = shouldThrow<IllegalArgumentException> {
                            config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main)).requireContentDir()
                        }
                        failure.message shouldContain "roots.docs.path is not readable/searchable"
                    } finally {
                        Files.setPosixFilePermissions(main, PosixFilePermissions.fromString("rwxr-xr-x"))
                    }
                }
            }
    }

    // A main path under an UNSEARCHABLE parent cannot even be stat'ed, so it is the existence guard
    // that fires (an actionable IAE, never a raw IOException). The toRealPath IOException rethrow in
    // validateExplicitRoots stays untested here by design: with existence and read/execute permissions
    // already guarded it is TOCTOU defense-in-depth, reachable only by a permission change racing validation.
    test("explicit: a main path under an unsearchable parent fails via the existence guard, never a raw IOException") {
        withBase { base ->
            if (!base.fileSystem.supportedFileAttributeViews().contains("posix")) return@withBase
            val locked = Files.createDirectories(base.resolve("locked"))
            val main = Files.createDirectories(locked.resolve("docs"))
            Files.setPosixFilePermissions(locked, emptySet<PosixFilePermission>())
            try {
                if (Files.isReadable(locked)) return@withBase // running as root: the permission drop is inert
                val failure = shouldThrow<IllegalArgumentException> {
                    config(base.resolve("data"), base.resolve("legacy"), explicitRoots("docs" to main)).requireContentDir()
                }
                failure.message shouldContain "roots.docs.path does not exist or is not a directory"
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    test("explicit: the validated/returned path is roots.docs's, never the ignored legacy contentDir") {
        withBase { base ->
            val main = Files.createDirectories(base.resolve("docs"))
            val legacy = Files.createDirectories(base.resolve("legacy"))
            val cfg = config(base.resolve("data"), legacy, explicitRoots("docs" to main))
            cfg.requireContentDir() shouldBe main
            cfg.mainContentRoot() shouldBe main
        }
    }
})
