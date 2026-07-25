package com.plainbase.domain.service

import com.plainbase.domain.root.RootName

/**
 * WHICH kind of write a gate is being asked to authorize (ADR-0011 D6). The [Action] mapping is not
 * one-to-one - [PageEdit] and [AssetWrite] are both EDIT-action writes - so the CLASS is what a
 * per-root policy can discriminate on, and the facade passes the class it means.
 *
 * [gatedByEditable] is the forward constraint: every write class that MUTATES A PAGE is gated by the
 * root's `editable` flag. A future non-page write (a comment, an annotation) adds a class with
 * `gatedByEditable = false` and needs no reshaping of the gate.
 */
sealed interface WriteClass {

    val action: Action

    val gatedByEditable: Boolean

    /** A save into an existing page (`PUT /pages/{id}`, an apply's edit write, an edit proposal). */
    data object PageEdit : WriteClass {
        override val action: Action = Action.EDIT
        override val gatedByEditable: Boolean = true
    }

    /** A new page (`POST /pages`, an apply's create write, a create proposal). */
    data object PageCreate : WriteClass {
        override val action: Action = Action.CREATE
        override val gatedByEditable: Boolean = true
    }

    /** An asset upload into a page's folder - an EDIT of the page it belongs to. */
    data object AssetWrite : WriteClass {
        override val action: Action = Action.EDIT
        override val gatedByEditable: Boolean = true
    }
}

/**
 * The target of an authorization decision, qualified by the root that owns it - and the ONE formatting
 * rule for a rooted audit/`checkRead` resource string ([audit]: `{root}:{resource}`).
 *
 * [root] is NULL exactly when NO REGISTERED root owns the target: an unknown page id, or an id bound
 * only under a DETACHED root (ADR-0011 D15). Those still gate-and-audit BEFORE their 404 - a pre-gate
 * 404 would turn the write surface into an existence oracle for anonymous callers - so the null arm's
 * [audit] is deliberately the BARE resource, byte-identical to the pre-C4 unknown-id audit row.
 */
data class RootedResource(val root: RootName?, val resource: String) {

    val audit: String get() = root?.let { "${it.value}:$resource" } ?: resource
}
