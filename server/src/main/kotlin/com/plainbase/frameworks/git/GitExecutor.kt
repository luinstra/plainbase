package com.plainbase.frameworks.git

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The single hermetic `git` chokepoint (ADR-0006, chunk W4): EVERY git invocation funnels through
 * [run]. The process is pinned reproducible and isolated — pinned `-c` config on every call, a cleared
 * + nulled environment, hooks disabled, args always a `List<String>` (no shell), a bounded wait, and
 * SEPARATE concurrent stdout/stderr drains so a stderr warning can NEVER corrupt a parsed SHA (F1).
 *
 * No reflection, no `@Volatile`, kotlin stdlib only — native-image safe. The provider above this never
 * touches `ProcessBuilder`; this never knows the commit recipe.
 */
class GitExecutor(
    private val workTree: Path,
    private val home: Path,
    // Bounded wait — never an unbounded waitFor() under the W1 writer monitor (P1-1). Injectable so a
    // timeout test can prove the force-kill path without a 30s wall-clock cost; production uses the default.
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    // The git executable — "git" (resolved on PATH) in production. Injectable so the hardening tests can
    // point at a fake `git` script that shapes stdout/stderr deterministically (the F1 stderr proof, the
    // flood/timeout proofs). ProcessBuilder resolves a bare name via the JVM's PATH, not the child env.
    private val gitBinary: String = "git",
) {

    /**
     * Runs `git [args]` in [workTree] with the pinned config + isolated env (caller [env] overlaid last;
     * [stdin] written-then-closed when present). Never throws on a non-zero exit or a missing binary — a
     * failed `start()` ([IOException]) and a timeout both come back as a [GitResult] failure so the W1
     * monitor is always released and dirty-page recovery stays intact.
     *
     * stdout and stderr are drained on separate threads and kept DISTINCT in the result: stdout is parsed
     * as data (SHAs, trees), stderr is diagnostic only.
     */
    fun run(args: List<String>, env: Map<String, String> = emptyMap(), stdin: ByteArray? = null): GitResult {
        val command = buildList {
            add(gitBinary)
            add("-C")
            add(workTree.toString())
            addAll(PINNED_CONFIG)
            addAll(args)
        }
        val builder = ProcessBuilder(command)
        builder.environment().apply {
            clear()
            put("HOME", home.toString())
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_CONFIG_SYSTEM", "/dev/null")
            put("LC_ALL", "C")
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_ASKPASS", "true")
            put("GIT_OPTIONAL_LOCKS", "0")
            putAll(env)
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            logger.warn(e) { "git ${args.firstOrNull()} could not be started (is git installed?)" }
            return GitResult(exitCode = -1, stdout = ByteArray(0), stderr = e.message ?: "git could not be started")
        }

        // Drain stdout AND stderr on dedicated threads — both at once — so the streams stay distinct (F1,
        // never a redirectErrorStream merge that pollutes a parsed SHA) AND neither pipe can deadlock when
        // git floods them (P1-1). Draining stdout off the calling thread is ALSO what lets the bounded
        // waitFor below enforce the timeout even when a hung git never closes its stdout (the force-kill path
        // — a calling-thread readBytes() would otherwise block past the timeout).
        // The drain/writer threads are DAEMON (P2-1): if git spawned a child that inherited a pipe, a stuck
        // drain must never block JVM shutdown. The normal (process-exited) path still joins them promptly.
        val stdoutBuffer = ByteArrayOutputStream()
        val stdoutDrain = thread(name = "git-stdout-drain", isDaemon = true) { process.inputStream.copyTo(stdoutBuffer) }
        val stderrBuffer = StringBuilder()
        val stderrDrain = thread(name = "git-stderr-drain", isDaemon = true) {
            process.errorStream.reader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    stderrBuffer.append(chunk, 0, n)
                }
            }
        }

        // Write stdin on its OWN thread too (P2): a calling-thread write of a >pipe-buffer payload (~64 KiB)
        // to a hung git that never reads it would block BEFORE waitFor even starts, holding the W1 monitor
        // forever. Off-thread, the bounded waitFor below still fires; the force-kill then breaks the pipe and
        // the write fails with a swallowed broken-pipe IOException. No stdin → close the stream as before.
        val stdinWriter: Thread? = if (stdin != null) {
            thread(name = "git-stdin-writer", isDaemon = true) {
                try {
                    process.outputStream.use { it.write(stdin) }
                } catch (e: IOException) {
                    logger.debug(e) { "git ${args.firstOrNull()} stdin write ended early (process exited/was killed)" }
                }
            }
        } else {
            process.outputStream.close()
            null
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            // Kill DESCENDANTS too (P2-1): destroyForcibly() kills only the direct process, so a grandchild
            // that inherited stdout/stderr would keep the pipes open and block the drains. ProcessHandle
            // .descendants() is standard JDK / native-safe; guard it anyway.
            runCatching { process.descendants().forEach { runCatching { it.destroyForcibly() } } }
            process.destroyForcibly()
            runCatching { process.descendants().forEach { runCatching { it.destroyForcibly() } } }
            // BOUNDED joins: even if a stuck child still holds a pipe, the calling thread (the W1 writer) is
            // guaranteed to return within timeoutSeconds + grace — never the unbounded join that defeated P1-1.
            stdinWriter?.join(TIMEOUT_DRAIN_GRACE_MILLIS)
            stdoutDrain.join(TIMEOUT_DRAIN_GRACE_MILLIS)
            stderrDrain.join(TIMEOUT_DRAIN_GRACE_MILLIS)
            logger.error { "git ${args.firstOrNull()} exceeded ${timeoutSeconds}s and was force-killed" }
            return GitResult(
                exitCode = -1,
                stdout = stdoutBuffer.toByteArray(),
                stderr = "git timed out after ${timeoutSeconds}s and was force-killed",
            )
        }
        // Normal path: the process exited, so its pipes are at EOF — these joins return immediately.
        stdinWriter?.join()
        stdoutDrain.join()
        stderrDrain.join()
        return GitResult(exitCode = process.exitValue(), stdout = stdoutBuffer.toByteArray(), stderr = stderrBuffer.toString())
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Default bounded wait — never an unbounded `waitFor()` under the W1 writer monitor (P1-1). */
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        /** Bounded grace for the post-force-kill drain joins (P2-1) — caps the call at timeout + this. */
        private const val TIMEOUT_DRAIN_GRACE_MILLIS = 2000L

        /**
         * Pinned on EVERY invocation: deterministic byte handling (`autocrlf`/`eol`), no gpg, no hooks
         * (belt-and-suspenders over the plumbing recipe, which runs none anyway — B3), a default branch
         * for `init`, and verbatim path bytes (`quotePath=false` + `precomposeUnicode=false`). The latter
         * stops macOS git (default `true`) from NFC-folding our explicit raw path args before writing the
         * index — without it `update-index --cacheinfo <NFD path>` would record the NFC form, defeating r6b.
         */
        private val PINNED_CONFIG = listOf(
            "-c", "core.autocrlf=false",
            "-c", "core.eol=lf",
            "-c", "commit.gpgsign=false",
            "-c", "core.hooksPath=/dev/null",
            "-c", "init.defaultBranch=main",
            "-c", "core.quotePath=false",
            "-c", "core.precomposeUnicode=false",
        )

        // 40 hex (SHA-1) OR 64 hex (SHA-256, `git init --object-format=sha256`) — full-width object ids only.
        private val SHA_LINE = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")

        /**
         * The first stdout line that is a full object id (40-hex SHA-1 or 64-hex SHA-256), or null. Strict by
         * design (F1): never a whole-buffer `trim()` — a stderr warning merged into the data could otherwise
         * masquerade as a SHA.
         */
        fun parseSha(stdout: ByteArray): String? =
            stdout.toString(Charsets.UTF_8).lineSequence().map { it.trim() }.firstOrNull { SHA_LINE.matches(it) }
    }
}

/**
 * One git invocation's outcome. [stdout] (parsed as data) and [stderr] (diagnostic) are kept DISTINCT —
 * never merged (F1). A non-zero [exitCode] is a normal outcome the caller inspects, never a thrown
 * exception; [ok] is the success predicate.
 */
// Array field on a value carrier (never a map key) — no generated equals/hashCode (house style).
class GitResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val ok: Boolean get() = exitCode == 0

    /** stdout decoded as UTF-8 text (for non-SHA reads: `git show -s`, log, diff). */
    val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
}
