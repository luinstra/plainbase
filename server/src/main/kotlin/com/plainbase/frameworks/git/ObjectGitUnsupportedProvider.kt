package com.plainbase.frameworks.git

import com.plainbase.domain.history.HistoryProvider

/**
 * The C4-era object-mode refusal for an EXPLICIT `git.enabled=true` (bound decision 2):
 * git-over-the-mirror lands in a later change, so forcing git on in object mode must fail fast,
 * operator-actionably - never silently record no history. Every operation delegates to
 * [NoOpHistoryProvider] except [gateCheck], whose throw surfaces through the existing
 * `Application.serve()` gate-check catch (System.err + exit 1) with NO serve() change.
 */
object ObjectGitUnsupportedProvider : HistoryProvider by NoOpHistoryProvider {

    override fun gateCheck(): Unit = throw IllegalStateException(
        "git history over the object backend lands in a later change; unset PLAINBASE_GIT_ENABLED or set it false",
    )
}
