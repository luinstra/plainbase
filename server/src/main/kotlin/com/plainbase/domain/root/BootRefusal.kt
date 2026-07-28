package com.plainbase.domain.root

/**
 * ONE boot refusal, as a VALUE (multi-root C5, D-C5-17.3). [key] is its identity for the CLI's baseline
 * diff; [message] is what the operator reads. They are deliberately DIFFERENT things, and both halves of
 * that are load-bearing:
 *
 *  - the message names PATHS, so an unrelated `root add` can change its text without the fault changing;
 *  - the legacy and explicit arms of the topology matrix word the SAME fault differently (a legacy
 *    `DATA_DIR == CONTENT_DIR` says "DATA_DIR and CONTENT_DIR must be different directories"; the same
 *    install after one `root add` is EXPLICIT and says "roots.docs and DATA_DIR must be different
 *    directories"), so a diff over prose calls a PRE-EXISTING fault NEW and traps the operator inside a
 *    config only this command can repair.
 *
 * **Diff the KEY. Print the MESSAGE.** `plainbase root` refuses if and only if the candidate introduces a
 * key the current config does not already have; a key present in both is the operator's own, and warns.
 */
data class BootRefusal(val kind: Kind, val roots: Set<RootName>, val message: String) {

    /**
     * The refusal's identity. [roots] is what stops a coarse kind from masking a real fault: a pre-existing
     * [Kind.ROOT_VS_DATA_DIR] on `x` cannot hide a new one on `y`, because the keys differ. Within one root
     * and one kind the fault was already there, which is the definition of pre-existing.
     */
    val key: Pair<Kind, Set<RootName>> get() = kind to roots

    enum class Kind {
        /** main is missing, not a directory, unreadable/unsearchable, or will not canonicalize. */
        MAIN_UNUSABLE,

        /**
         * Two roots resolve to the same directory, or one nests inside the other. Keyed by the PAIR, as a
         * SET rather than an ordered couple. An ordered key would also work TODAY - the matrix walks
         * `(i, j)` with `j > i`, `add` appends and `remove` filters, so a surviving pair's orientation
         * cannot flip (Invariant R). The set is chosen precisely so the diff does not DEPEND on that: a key
         * that is correct only because of an invariant declared three decisions away is a key that breaks
         * the day someone weakens the invariant, silently, in the direction of writing an unbootable config.
         */
        ROOT_PAIR,

        /** A root collides with DATA_DIR (equal, inside it, or a declared-vs-real nesting mismatch). */
        ROOT_VS_DATA_DIR,

        /** The object-mode required-key matrix. Unreachable from the CLI; present so `serve` shares one type. */
        OBJECT_KEYS,

        /** The ADR-0008 fail-closed bind guard. Root-independent, so [roots] is empty. */
        BIND_GUARD,

        /** `gateCheck()` threw for this root: missing binary, version floor, access probe, or the D4 guard. */
        GIT_GATE,
    }
}
