package com.plainbase.frameworks.git

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
@OptIn(ExperimentalAtomicApi::class)
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
    // Byte ceilings on what a single git invocation may emit. The timeout bounds TIME, not BYTES — but
    // W5's reads return attacker/size-controlled output (`/history` full `git log`, `/diff` full diff, the
    // startup `lastCommits` walk), so a repo with huge messages / very deep history / a huge diff could
    // emit enough WITHIN the timeout to exhaust JVM/native-image memory. On exceed the process is
    // force-killed and the call comes back a failure GitResult (NOT a throw — the provider's fail-loud
    // path turns it into a GitCommandException, preserving the "executor never throws, W1 monitor always
    // releases" contract). The defaults are generous so a normal/large-but-sane repo never trips them;
    // injectable so a test can drive the path cheaply.
    private val maxStdoutBytes: Long = DEFAULT_MAX_STDOUT_BYTES,
    private val maxStderrBytes: Long = DEFAULT_MAX_STDERR_BYTES,
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
            // Every read path arg after `--` (log/diff/lastCommits) is a PATHSPEC, not a literal filename:
            // a page named `foo[1].md` would be read as a glob/magic pathspec and match OTHER pages (wrong
            // commits/diff) or none (dropped citation) — `--` separates options from paths, it does NOT force
            // literal. One env var here makes ALL pathspecs literal. Harmless to the commit recipe, whose only
            // path arg (`update-index --cacheinfo`) is already a literal pathname, not a pathspec.
            put("GIT_LITERAL_PATHSPECS", "1")
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
        // True once either drain saw its stream exceed its cap and force-killed the process. Set on a drain
        // thread, read on the calling thread AFTER its join() (which establishes the happens-before).
        val overflowed = AtomicBoolean(false)
        val stdoutBuffer = ByteArrayOutputStream()
        val stdoutDrain = thread(name = "git-stdout-drain", isDaemon = true) {
            drainCapped(process.inputStream, stdoutBuffer, maxStdoutBytes, process, overflowed)
        }
        val stderrBuffer = ByteArrayOutputStream()
        val stderrDrain = thread(name = "git-stderr-drain", isDaemon = true) {
            drainCapped(process.errorStream, stderrBuffer, maxStderrBytes, process, overflowed)
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
        // Normal path: the process exited (or a drain force-killed it on overflow), so its pipes are at EOF —
        // these joins return immediately AND publish the overflow flag the drain set before exiting.
        stdinWriter?.join()
        stdoutDrain.join()
        stderrDrain.join()
        if (overflowed.load()) {
            // A drain hit its byte ceiling and force-killed git. Fail loud (NOT silently truncate): the
            // provider's fail-loud path turns this non-zero result into a GitCommandException — `/history`
            // and `/diff` surface a 500, the startup `lastCommits` aborts serve, never an OOM. The actionable
            // text names the cap so an operator knows the repo's history/diff is too large for an in-memory read.
            logger.error { "git ${args.firstOrNull()} output exceeded the in-memory read cap and was force-killed" }
            return GitResult(
                exitCode = -1,
                stdout = stdoutBuffer.toByteArray(),
                stderr = "git ${args.firstOrNull()} output exceeded the in-memory read cap " +
                    "(${maxStdoutBytes / (1024 * 1024)} MiB stdout / ${maxStderrBytes / (1024 * 1024)} MiB stderr) and was " +
                    "force-killed — repo history/diff too large for an in-memory read",
            )
        }
        return GitResult(
            exitCode = process.exitValue(),
            stdout = stdoutBuffer.toByteArray(),
            stderr = stderrBuffer.toString(Charsets.UTF_8),
        )
    }

    /**
     * Drains [stream] into [buffer] up to [cap] bytes; on the first read that would exceed [cap] it
     * force-kills [process] (and descendants) and flags [overflowed], stopping the drain. The kill makes
     * [run]'s bounded `waitFor` observe a finished process promptly; the calling thread then sees the flag
     * after joining this thread. A flood on EITHER stream trips it — both share one flag. Bounded grace
     * stays the timeout's job; this caps BYTES.
     */
    private fun drainCapped(stream: InputStream, buffer: ByteArrayOutputStream, cap: Long, process: Process, overflowed: AtomicBoolean) {
        val chunk = ByteArray(DRAIN_CHUNK_BYTES)
        var total = 0L
        stream.use {
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                total += n
                if (total > cap) {
                    overflowed.store(true)
                    runCatching { process.descendants().forEach { d -> runCatching { d.destroyForcibly() } } }
                    process.destroyForcibly()
                    break
                }
                buffer.write(chunk, 0, n)
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Default bounded wait — never an unbounded `waitFor()` under the W1 writer monitor (P1-1). */
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        /** Bounded grace for the post-force-kill drain joins (P2-1) — caps the call at timeout + this. */
        private const val TIMEOUT_DRAIN_GRACE_MILLIS = 2000L

        /**
         * Default stdout byte ceiling per git invocation (W5): 64 MiB. GENEROUS by design — a normal or
         * even large-but-sane repo's `git log`/`diff` is far under this, so the cap is a safety floor that
         * should never bind in practice, only stop a pathological repo (giant commit messages, a huge diff,
         * extreme history depth) from OOM-ing the in-memory drain. Kept a documented constant rather than a
         * per-deploy `git.maxReadBytes` knob: a knob adds config surface (env parse + Koin wiring +
         * allowlist doc) disproportionate to a ceiling that exists only to convert an OOM into a clean
         * fail-loud error; injectable via the ctor for the cheap force-kill test.
         */
        const val DEFAULT_MAX_STDOUT_BYTES = 64L * 1024 * 1024

        /** Default stderr byte ceiling: 1 MiB. stderr is diagnostic only — a flood there is still a DoS
         *  vector, so it shares the stdout cap's force-kill path, just at a far smaller ceiling. */
        const val DEFAULT_MAX_STDERR_BYTES = 1L * 1024 * 1024

        /** Drain read-chunk size — the granularity at which the byte cap is checked. */
        private const val DRAIN_CHUNK_BYTES = 64 * 1024

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
