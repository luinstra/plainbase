package com.plainbase.frameworks.config

import com.plainbase.domain.root.Root
import com.plainbase.frameworks.filesystem.FileAtomics
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The writer for `DATA_DIR/roots.conf` (C5 D-C5-1) - the file `plainbase root` owns end to end.
 *
 * **IT IS WRITE-ONLY, AND THERE IS NO `read`.** `PlainbaseConfig.fromEnvAndFile` is the ONLY code in the
 * repository that parses `roots.conf`, and every consumer takes the managed roots off the ONE config snapshot
 * it produces (`config.roots.managed`). A reader here would be a SECOND parser of a file that `root add`
 * replaces atomically: a `list` racing an `add` would print a topology from read #1 annotated with a
 * provenance from read #2, which is one claim built from two observations of a file that changed in between.
 * Two atomic reads of a mutating file are not one atomic read (D-C5-10).
 *
 * [serialize] is PURE, which is what lets the CLI validate the exact STRING it is about to write - hand it to
 * the real loader, run the real boot gate over the result, and only then promote those same bytes. "Did the
 * bytes I validated equal the bytes I wrote" is then not a question anyone can get wrong.
 */
object ManagedRootsFile {

    private val logger = KotlinLogging.logger {}

    /** The last-known-good sibling the no-atomic-rename fallback leaves behind when it cannot restore one itself. */
    const val BACKUP_SUFFIX: String = ".bak"

    private val HEADER = """
        # Managed by `plainbase root` - do not edit by hand.
        # Rewritten in full by every `plainbase root add|remove`; hand edits are lost.
        # Declare hand-written roots in plainbase.conf instead (they merge with these).
    """.trimIndent()

    /**
     * [roots] as the HOCON `roots {}` block, in the given order - which is D-C5-4 order: parsed order
     * preserved, new roots appended at the end, so a newcomer always ranks LAST and therefore LOSES a
     * cross-root duplicate-id contest against every incumbent (Invariant R).
     *
     * We own every byte, so the output is deterministic and diffable. `main` never appears here and the writer
     * does not check for it: the invariant has ONE home in the loader's refusal plus the CLI's argv refusal,
     * and a third defensive check would be a third thing to keep in step.
     *
     * **The KEY is quoted, and that is not decoration.** A [RootName] is a slug, but a slug is not automatically
     * an inert HOCON key: `include` is a legal root name AND a HOCON directive, so a bare `include {` is read as
     * an include statement and the block does not parse at all. Quoting the key settles the whole class - every
     * reserved token this format has and any it grows later - instead of blacklisting the one we happened to
     * find. (`root add include <path>` did not corrupt anything even before this: the gate parses the candidate
     * before it writes, so it refused. But it refused with a HOCON parse error against a legal name, which is
     * this command failing at the only job it has.)
     */
    fun serialize(roots: List<Root>): String = buildString {
        appendLine(HEADER)
        appendLine("roots {")
        roots.forEach { root ->
            appendLine("  ${hoconQuote(root.name.value)} {")
            appendLine("    backend = local")
            appendLine("    path = ${hoconQuote(requireNotNull(root.localPath) { "a managed root must be local-backed" }.toString())}")
            appendLine("    editable = ${root.editable}")
            appendLine("    history = ${root.history.name.lowercase()}")
            appendLine("  }")
        }
        appendLine("}")
    }

    /**
     * Promotes [hocon] to [path] atomically: a sibling temp, then an `ATOMIC_MOVE`, so a reader (or a boot)
     * racing the write sees either the whole old file or the whole new one, never a truncated husk. The temp
     * is removed on any outcome, so a failed promote leaves the previous file exactly as it was.
     *
     * A DATA_DIR on a filesystem with no atomic rename (a network mount, an exotic FS) degrades to a BACKED-UP
     * copy-replace through the [FileAtomics] seam rather than throwing - the same fallback `LocalContentStore`
     * and `MirrorState` already make, warning included. Refusing outright would mean `plainbase root` simply
     * does not run on that mount, which is a worse answer than a warned non-atomic write of a file the operator
     * is explicitly rewriting.
     *
     * **The copy loses the atomicity, and WITHOUT THE BACKUP it would lose the BYTES too** - it replaces the
     * target IN PLACE, so a failure midway through leaves `roots.conf` truncated, and a truncated `roots.conf`
     * is an install that will not boot (the store's own copy-fallback models exactly that outcome, which is why
     * `CasResult.Unreadable` carries `targetMutated`). So [copyPreservingPrevious] takes the last-known-good
     * aside FIRST: an I/O failure puts it back, and a KILL mid-copy leaves it on disk next to the wreckage. A
     * dead process cannot recover anything; the most it can do is leave the operator the file.
     *
     * ONE call, and it is the LAST thing a verb does: by the time it runs, the decision is already made and
     * the bytes are already validated.
     */
    fun writeAtomically(path: Path, hocon: String, atomics: FileAtomics = FileAtomics.Real) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            Files.writeString(temp, hocon, Charsets.UTF_8)
            try {
                atomics.atomicMove(temp, path)
            } catch (_: AtomicMoveNotSupportedException) {
                logger.warn { "ATOMIC_MOVE unsupported for $path; falling back to a backed-up copy+delete (non-atomic)" }
                copyPreservingPrevious(temp, path, atomics)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * The non-atomic promote, made RECOVERABLE: back the target up, copy over it, and on failure put the backup
     * back. Backup and restore go through plain [Files.copy], never [FileAtomics] - the seam stands for the FS
     * primitive whose availability varies, and the recovery is ours rather than the filesystem's.
     *
     * The worst state an operator can be left in is therefore never "a truncated roots.conf and nothing else":
     * it is a truncated `roots.conf` with [BACKUP_SUFFIX] beside it, one `mv` from the config that booted this
     * morning. The backup is kept ONLY when it is still needed - a failed restore, or a process killed before it
     * could run one. On a first-ever promote there is nothing to keep: absence is that install's last-known-good,
     * so a partial file is removed instead.
     */
    private fun copyPreservingPrevious(temp: Path, path: Path, atomics: FileAtomics) {
        val backup = if (Files.isRegularFile(path)) path.resolveSibling("${path.fileName}$BACKUP_SUFFIX") else null
        backup?.let { Files.copy(path, it, StandardCopyOption.REPLACE_EXISTING) }
        try {
            atomics.copyReplace(temp, path)
        } catch (e: Exception) {
            if (backup == null) {
                Files.deleteIfExists(path) // a partial FIRST write is not a config, and absence is what preceded it
            } else {
                runCatching { Files.copy(backup, path, StandardCopyOption.REPLACE_EXISTING) }
                    .onSuccess { Files.deleteIfExists(backup) }
                    .onFailure { logger.error(it) { "could not restore $path after a failed write: the previous config is in $backup" } }
            }
            throw e
        }
        backup?.let { Files.deleteIfExists(it) }
    }

    /**
     * Unlinks the file. `root remove` of the LAST managed root does this rather than leaving an empty
     * `roots {}` husk: for the MANAGED file emptiness IS absence (no refusal hangs off its presence), so the
     * unlink returns the install to `SYNTHESIZED` and byte-identical legacy behavior instead of stranding it in
     * the strict EXPLICIT matrix over a file with nothing in it.
     */
    fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    /**
     * [value] as a HOCON quoted string - used for every value we emit that HOCON could read as anything but a
     * literal: the root KEY and the path. Backslash and quote are escaped; a control character is REFUSED rather
     * than round-tripped cleverly - a path with a newline in it is not something to be smart about.
     */
    private fun hoconQuote(value: String): String {
        require(value.none { it.isISOControl() }) { "cannot be written to roots.conf, it contains a control character: '$value'" }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
