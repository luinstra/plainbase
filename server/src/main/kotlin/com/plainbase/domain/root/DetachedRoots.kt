package com.plainbase.domain.root

/**
 * The boot-time detached-root verdict (ADR-0011 D1/D15; synthesis: the data half of the
 * rename-trap package, C1's required-main validation being the config half). A DETACHED root is a
 * name holding id_map bindings but absent from the configured registry: its pages are unserved and
 * its permalinks dormant until a root with the same name returns (names are permanent identifiers).
 *
 * Partial detachment is a WARN; a nonempty id_map whose roots are ENTIRELY disjoint from the
 * configuration is almost certainly a wrong DATA_DIR or a wholesale-rewritten roots block, so it is
 * a FATAL refusal (D15) - fail closed, with remediation text that is config-first and never
 * delete-the-database (the app DB also holds users, sessions, tokens, roles, proposals, and the
 * audit log).
 */
object DetachedRoots {

    sealed interface Verdict {
        /** No detached bindings (including the empty id_map). */
        data object Clean : Verdict

        /** Some bindings are detached ([roots]); serve continues with a WARN. */
        data class Detached(val roots: Set<RootName>) : Verdict

        /** EVERY binding is detached ([roots] nonempty): refuse to serve (D15). */
        data class AllDetached(val roots: Set<RootName>) : Verdict
    }

    fun evaluate(boundRoots: Set<RootName>, configured: Set<RootName>): Verdict {
        val detached = boundRoots - configured
        return when {
            detached.isEmpty() -> Verdict.Clean
            detached == boundRoots -> Verdict.AllDetached(detached)
            else -> Verdict.Detached(detached)
        }
    }
}
