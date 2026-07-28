package com.plainbase

import com.plainbase.domain.root.BootRefusal
import com.plainbase.frameworks.config.PlainbaseConfig
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **THE GATE WRITES NOTHING. This is the test that makes that a guarantee instead of a paragraph.**
 *
 * `plainbase root` runs the SERVER'S OWN boot gate over the candidate config it is about to write. That is the
 * whole design - and it puts the CLI one method away from the boot path's MUTATING half. `gateCheck()` PROBES;
 * `prepare()` does `git init` plus `Files.createDirectories(gitHome)`, and `serve()` calls them at two different
 * times for a reason (prepare runs AFTER the DATA_DIR lock). **An executor who "helpfully" readies the git-home
 * so the git probes work has turned `plainbase root add` into a command that writes into an operator's
 * repository during VALIDATION.** That would be a far worse bug than the one the whole mechanism exists to fix.
 *
 * So: snapshot every path under DATA_DIR and under every root (path, size, mtime), run the gate, snapshot again,
 * assert byte-and-mtime IDENTICAL. It goes RED the day someone wires `prepare()` into the gate.
 *
 * `@Tag("native")`: NIO edge behavior plus real `git` process execution, two of the divergence surfaces project
 * policy native-tags by default.
 */
@Tag("native")
class BootGatePurityTest {

    private data class Entry(val path: String, val size: Long, val modified: Long)

    private fun snapshot(vararg roots: Path): List<Entry> = roots.flatMap { root ->
        if (!Files.exists(root)) {
            emptyList()
        } else {
            Files.walk(root).use { stream ->
                stream.map { p ->
                    Entry(
                        path = p.toString(),
                        size = if (Files.isRegularFile(p)) Files.size(p) else -1,
                        modified = Files.getLastModifiedTime(p).toMillis(),
                    )
                }.toList()
            }
        }
    }.sortedBy { it.path }

    @Test
    fun `the boot gate creates nothing - not the git-home, not a repo, not a single byte`() {
        val base = Files.createTempDirectory("pb-gate-purity")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main = Files.createDirectory(base.resolve("main"))
            val claimed = Files.createDirectory(base.resolve("claimed"))
            val missing = base.resolve("not-mounted") // deliberately never created

            Files.writeString(main.resolve("page.md"), "---\ntitle: P\n---\n\n# P\n")
            Files.writeString(claimed.resolve("doc.md"), "---\ntitle: D\n---\n\n# D\n")
            // main IS a git repo (so its AUTO arm takes the live detection path, the one non-pure thing on the
            // gate's route - a single read-only `rev-parse`), while `claimed` declares `history = native` and is
            // NOT a repo (so the D4 guard actually runs and refuses).
            gitInit(main)

            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                roots {
                  docs    { path = "$main" }
                  claimed { path = "$claimed", history = native }
                  absent  { path = "$missing" }
                }
                """.trimIndent(),
            )
            val config = PlainbaseConfig.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            val gitHome = data.resolve("git-home")
            assertFalse(Files.exists(gitHome), "precondition: the git-home must not exist before the gate runs")

            val before = snapshot(data, main, claimed)
            val gate = bootGateFor(config)
            val after = snapshot(data, main, claimed)

            // The gate DID work - it is not vacuously pure because it did nothing.
            assertTrue(
                gate.refusals.any { it.kind == BootRefusal.Kind.GIT_GATE },
                "the claimed-but-not-a-repo root must have been REFUSED, or this test proves nothing",
            )
            assertEquals(before, after, "the boot gate mutated the filesystem: it must PROBE and never CREATE")
            assertFalse(
                Files.exists(gitHome),
                "the boot gate created DATA_DIR/git-home. That is prepare()'s job, and prepare() runs AFTER the " +
                    "DATA_DIR lock - which `plainbase root` never takes. A validation command that mkdirs or " +
                    "`git init`s an operator's tree is the bug this whole mechanism exists to avoid.",
            )
            assertFalse(Files.exists(claimed.resolve(".git")), "the gate `git init`ed a repo it does not own")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * **THE `git init` PATH, which the case above cannot reach.**
     *
     * That case pins the git-HOME mkdir, and it exercises a `native` root - but `git init` can never fire on a
     * CLAIMED root by construction: `ensureRepo()` THROWS for one rather than creating a repository it does not
     * own. So the only route by which the gate could ever `git init` is MAIN's git arm over a main that has no
     * `.git` yet, and the case above gave main a real repo, where `git init` would be a no-op.
     *
     * Here main is FORCED on (`PLAINBASE_GIT_ENABLED=true`) over a directory with NO repository. That is exactly
     * the config where `prepare()` creates one - `serve()` calls it after the DATA_DIR lock, deliberately - and
     * it is the single worst thing a validation command could do: `plainbase root add` would be initialising a
     * git repository inside an operator's content tree while merely CHECKING whether a config is valid.
     */
    @Test
    fun `a forced-on git main with NO repository is not git-inited by the gate`() {
        val base = Files.createTempDirectory("pb-gate-purity-init")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main = Files.createDirectory(base.resolve("main"))
            Files.writeString(main.resolve("page.md"), "---\ntitle: P\n---\n\n# P\n")
            assertFalse(Files.exists(main.resolve(".git")), "precondition: main must NOT be a repo yet")

            val config = PlainbaseConfig.fromEnvAndFile(
                mapOf(
                    "DATA_DIR" to data.toString(),
                    "CONTENT_DIR" to main.toString(),
                    "PLAINBASE_GIT_ENABLED" to "true", // forces the git provider on, with no repo to detect
                ),
            )

            val before = snapshot(data, main)
            bootGateFor(config)
            val after = snapshot(data, main)

            assertFalse(
                Files.exists(main.resolve(".git")),
                "the boot gate ran `git init` inside the operator's content tree. That is prepare()'s job, it runs " +
                    "AFTER the DATA_DIR lock, and `plainbase root` never calls it - a command that CREATES a git " +
                    "repository while VALIDATING a config is a far worse bug than the one this mechanism exists to fix.",
            )
            assertFalse(Files.exists(data.resolve("git-home")), "the boot gate created the git-home")
            assertEquals(before, after, "the boot gate mutated the filesystem")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * The app DB is not merely un-opened by the gate, it is UNREACHABLE: `bootGateFor`'s graph has no
     * `repositoryModule`, so an accidental object-mode resolution fails LOUD with a missing definition instead of
     * quietly opening and MIGRATING a database from a command that holds no DATA_DIR lock. This pins the
     * CONSEQUENCE - no `plainbase.db` on disk. (The complementary counter-proof, that the gate constructs zero
     * `ObjectContentStore`s and zero `S3ObjectClient`s, reads the internal construction counters and so lives in
     * the JVM suite beside `LocalBootNoObjectConstructionTest`, which established that idiom.)
     */
    @Test
    fun `the boot gate opens no database - the first open would run the migration, unlocked`() {
        val base = Files.createTempDirectory("pb-gate-nodb")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main = Files.createDirectory(base.resolve("main"))
            Files.writeString(main.resolve("page.md"), "---\ntitle: P\n---\n\n# P\n")

            val config = PlainbaseConfig.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to main.toString()),
            )
            bootGateFor(config)

            assertFalse(
                Files.exists(config.appDatabasePath),
                "the gate opened (and therefore MIGRATED) the app database. The first open runs the migration, and a " +
                    "second process racing it is exactly what the DATA_DIR lock prevents - which `plainbase root` " +
                    "deliberately does not hold.",
            )
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun gitInit(dir: Path) {
        val process = ProcessBuilder("git", "init", "-q").directory(dir.toFile()).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { "git init failed in $dir" }
    }
}
