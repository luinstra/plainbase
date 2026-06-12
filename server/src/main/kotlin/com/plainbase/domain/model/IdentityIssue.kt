package com.plainbase.domain.model

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId

/**
 * A non-fatal identity-assignment anomaly recorded for the admin issues list and surfaced by `adopt`
 * output (chunk 4b). An issue NEVER blocks indexing: the page keeps a usable identity (its `id_map`
 * entry or a freshly minted UUIDv7) and the anomaly is routed here instead of being silently honored.
 *
 * The [Kind] enum is append-only — Phase 1 defines the five known classes; later phases may add
 * more, never remove or repurpose. Each variant carries exactly the context an operator needs to act:
 * which paths collided, which id, the patcher's rule-naming refusal message.
 *
 * Pure domain code: only chunk 1.5's [TreePath] and chunk 4a's [PageId] appear; no framework type.
 */
sealed interface IdentityIssue {

    /** The frozen, append-only issue class (matches §5.8's `IdentityIssue` model list). */
    val kind: Kind

    /** The append-only set of identity-issue classes (§5.8). */
    enum class Kind {
        DUPLICATE_ID,
        PATCH_REFUSED,
        REDIRECT_CONFLICT,
        PATH_COLLISION,
        PATH_SLUG_COLLISION,
    }

    /**
     * Two paths claim the same frontmatter `id` (a copied file). The previously-bound [keptPath]
     * keeps the id; [reassignedPath] gets a fresh UUIDv7 (not materialized). "Older path keeps the
     * id" is operationalized as "previously-bound path keeps it" — deterministic without Git/mtime.
     */
    data class DuplicateId(
        val id: PageId,
        val keptPath: TreePath,
        val reassignedPath: TreePath,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.DUPLICATE_ID
    }

    /**
     * The [FrontmatterPatcher] refused to materialize [path]'s id. [message] is the patcher's
     * rule-naming refusal text (§A3) so `adopt` output tells the operator what to change. The page
     * keeps its `id_map` identity.
     */
    data class PatchRefused(
        val path: TreePath,
        val message: String,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.PATCH_REFUSED
    }

    /** A `redirect_from` alias conflicts with a live canonical path or another redirect (chunk 5). */
    data class RedirectConflict(
        val path: TreePath,
        val message: String,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.REDIRECT_CONFLICT
    }

    /** Two distinct content paths collide as the same indexed entry (chunk 5). */
    data class PathCollision(
        val keptPath: TreePath,
        val collidingPath: TreePath,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.PATH_COLLISION
    }

    /**
     * Two sibling paths slugify to the same canonical URL segment (chunk 5). The raw-byte-order
     * winner [keptPath] owns the URL; [loserPath] is resolvable only by id (`url = null`).
     */
    data class PathSlugCollision(
        val keptPath: TreePath,
        val loserPath: TreePath,
    ) : IdentityIssue {
        override val kind: Kind get() = Kind.PATH_SLUG_COLLISION
    }
}
