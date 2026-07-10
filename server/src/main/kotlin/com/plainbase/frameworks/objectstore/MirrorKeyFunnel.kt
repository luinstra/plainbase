package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.filesystem.IgnoreRules
import java.nio.file.Path

/**
 * The ONE bucket-key eligibility decision for the object-store mirror (seam a), shared by
 * [ObjectContentStore]'s hydrate/poll/delete diff AND its native funnel test - a single source of
 * truth so the test can exercise the REAL logic rather than a drift-prone copy. Pure and logging-free
 * (the caller owns the skip WARN/DEBUG); every input is a value, so it holds no state.
 *
 * A raw key is eligible iff it (1) cannot escape [mirrorRoot] ([escapesRoot]), (2) has no ignored
 * segment (dotfiles - so `.git` and the reserved `.plainbase/` prefix are invisible by existing law),
 * and (3) NFC-normalizes to a valid [TreePath] (the R8 funnel). `_folder.yaml` sidecars are eligible
 * (not dot-prefixed, not ignored) so they hydrate and are sweepable.
 */
object MirrorKeyFunnel {

    /** The eligible [TreePath] for [rawRelative], or null when any funnel condition fails. */
    fun eligible(rawRelative: String, mirrorRoot: Path, ignoreRules: IgnoreRules): TreePath? {
        if (escapesRoot(rawRelative, mirrorRoot)) return null
        var prefix = ""
        for (segment in rawRelative.split('/')) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            if (segment.isEmpty() || ignoreRules.isIgnored(segment, prefix)) return null
        }
        return TreePath.of(rawRelative)
    }

    /**
     * True iff a raw bucket key could resolve OUTSIDE [mirrorRoot] - a cross-platform, real-path-free
     * containment test (the mirror target does not exist yet, so the toRealPath-based
     * [com.plainbase.frameworks.filesystem.isWithinRoot] cannot serve here). Rejects a backslash
     * separator and a Windows drive/device form (`X:...`) unconditionally - illegal in a legit mirror
     * key on every host, yet path-escaping on Windows - and, belt-and-suspenders, any key whose lexical
     * resolve+normalize does not stay under [mirrorRoot] on THIS host. POSIX `..` segments are already
     * rejected by `TreePath.of`, but `..\x` is a single valid POSIX segment there, hence this guard.
     */
    fun escapesRoot(rawRelative: String, mirrorRoot: Path): Boolean {
        if ('\\' in rawRelative) return true
        if (rawRelative.split('/').any { it.length >= 2 && it[1] == ':' && it[0].isLetter() }) return true
        return !mirrorRoot.resolve(rawRelative).normalize().startsWith(mirrorRoot)
    }
}
