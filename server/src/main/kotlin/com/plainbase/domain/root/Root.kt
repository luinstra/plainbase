package com.plainbase.domain.root

import java.nio.file.Path

/**
 * How a root records page history (ADR-0011 D4). C1 stores the mode; nothing consumes it at runtime
 * until C4 enforces it - the `git.enabled` tri-state remains the only live history knob until then.
 * - [OFF] - no history; the default for extra roots (Plainbase never commits into a repo it does not own).
 * - [AUTO] - today's repo auto-detection, grandfathered for `main` (including its deliberate
 *   .git-as-file worktree acceptance).
 * - [NATIVE] - the operator explicitly claims the root's repo; C4 adds the strict fail-closed guard.
 */
enum class HistoryMode {
    OFF,
    AUTO,
    NATIVE,
}

/** Where a root's authoritative content lives. A topology descriptor only - the domain never touches the filesystem. */
sealed interface RootBackend {

    /**
     * A local directory: the DECLARED path, absolute and normalized but never symlink-resolved
     * (ADR-0011 D8) - validation canonicalizes with toRealPath for its comparisons and discards the
     * result, so served paths match what the operator wrote.
     */
    data class Local(val path: Path) : RootBackend

    /**
     * An S3-compatible bucket. Shape-only in v1 (ADR-0011 D10): the synthesized object-mode `main`
     * carries it, nothing reads its fields yet, and [bucket] may be empty for directly-constructed
     * test configs. An explicit `roots {}` block cannot declare it.
     */
    data class Object(val bucket: String, val prefix: String) : RootBackend
}

/** One document directory: its name, where its content lives, and its per-root policy knobs. */
data class Root(
    val name: RootName,
    val backend: RootBackend,
    /** Whether page-mutation write classes are allowed here; never gates browsing/search (ADR-0011 D6). */
    val editable: Boolean,
    val history: HistoryMode,
) {
    /** The root's local filesystem path, or null for an object-backed root - saves caller-side casts in the wiring. */
    val localPath: Path? get() = (backend as? RootBackend.Local)?.path
}
