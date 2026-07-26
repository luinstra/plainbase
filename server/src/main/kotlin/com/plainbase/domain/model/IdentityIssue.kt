package com.plainbase.domain.model

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

/**
 * A non-fatal identity-assignment anomaly recorded for the admin issues list and surfaced by `adopt`
 * output (chunk 4b). An issue NEVER blocks indexing: the page keeps a usable identity (its `id_map`
 * entry or a freshly minted UUIDv7) and the anomaly is routed here instead of being silently honored.
 *
 * The [Kind] enum is append-only: Phase 1 defines the five known classes; later phases may add more,
 * never remove or repurpose. C2 appended a cross-root kind and C7 removed it again - the one permitted
 * exception, because per-root identity (C5) made that kind unconstructible before any RELEASED build
 * could write a row of it, so no released data references it (ADR-0012). The rule stands unchanged for
 * every kind that ever shipped. Each variant carries exactly the context an operator needs to
 * act: which root and paths collided, which id, the patcher's rule-naming refusal message.
 *
 * Every variant is root-qualified AND a within-root fact: both paths of a two-path kind share the
 * root. There is no cross-root kind, because per-root identity (ADR-0012) makes the same id under two
 * roots legal rather than an anomaly.
 *
 * Pure domain code: only chunk 1.5's [TreePath], chunk 4a's [PageId], and the C1 root types appear;
 * no framework type.
 */
sealed interface IdentityIssue {

    /** The frozen, append-only issue class (matches §5.8's `IdentityIssue` model list). */
    val kind: Kind

    /** The append-only set of identity-issue classes (§5.8), back to the five Phase-1 kinds. */
    enum class Kind {
        DUPLICATE_ID,
        PATCH_REFUSED,
        REDIRECT_CONFLICT,
        PATH_COLLISION,
        PATH_SLUG_COLLISION,
    }

    /**
     * Two paths under one [root] claim the same frontmatter `id` (a copied file). The previously
     * bound [keptPath] keeps the id; [reassignedPath] gets a fresh UUIDv7 (not materialized).
     * "Older path keeps the id" is operationalized as "previously-bound path keeps it" —
     * deterministic without Git/mtime.
     */
    data class DuplicateId(
        val id: PageId,
        val root: RootName,
        val keptPath: TreePath,
        val reassignedPath: TreePath,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.DUPLICATE_ID
    }

    /**
     * The [FrontmatterPatcher] refused to materialize the id of [path] (under [root]). [message] is
     * the patcher's rule-naming refusal text (§A3) so `adopt` output tells the operator what to
     * change. The page keeps its `id_map` identity.
     */
    data class PatchRefused(
        val root: RootName,
        val path: TreePath,
        val message: String,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.PATCH_REFUSED
    }

    /** A `redirect_from` alias conflicts with a live canonical path or another redirect within [root] (chunk 5). */
    data class RedirectConflict(
        val root: RootName,
        val path: TreePath,
        val message: String,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.REDIRECT_CONFLICT
    }

    /**
     * Two distinct on-disk names normalize to the same indexed [keptPath] (the B3 NFC collision,
     * chunk 5). [loserRawName] is the excluded sibling's raw on-disk filename, kept verbatim:
     * normalizing it (e.g. into a [TreePath]) would collapse it back into [keptPath] — the two
     * names differ ONLY in raw bytes, and that difference is what makes the issue actionable.
     */
    data class PathCollision(
        val root: RootName,
        val keptPath: TreePath,
        val loserRawName: String,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.PATH_COLLISION
    }

    /**
     * Two sibling paths slugify to the same canonical URL segment (chunk 5). The raw-byte-order
     * winner [keptPath] owns the URL; [loserPath] is resolvable only by id (`url = null`).
     */
    data class PathSlugCollision(
        val root: RootName,
        val keptPath: TreePath,
        val loserPath: TreePath,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.PATH_SLUG_COLLISION
    }
}
