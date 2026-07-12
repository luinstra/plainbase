package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryCommandException
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException

/**
 * The ONE root-loss exit boundary (ADR-0011 D5, and the D-C4-19/23/25 rules made shareable): when a call
 * against a root's BACKEND fails, decide whether the root went away - and if it did, MARK it before anyone
 * answers.
 *
 * **Two-sided, and both sides matter.** A failure whose re-probe FAILS is a disappearance: mark it, so every
 * later read serves an honest 503 instead of the carried-forward section's stale bytes, and answer
 * [RootUnavailable]. A failure whose re-probe still PASSES is NOT a disappearance - a corrupt repo, an unknown
 * git flag, a parser bug - and it RETHROWS unchanged, because laundering a genuine operational fault into
 * "the disk is gone" is the mirror-image lie, and it would leave the root permanently, wrongly Unavailable.
 *
 * **Which failures are even candidates** is derived from what a ROOTED call can collaborate with, never from a
 * blanket `catch (Exception)` (which is exactly what would launder a bug):
 *  - the content store's NIO surface raises [IOException];
 *  - every history call is `git -C <workTree>`, so a work tree that vanished exits non-zero and raises a
 *    [HistoryCommandException] (the port's own type - which is WHY it lives in the domain).
 * A store call that has ALREADY classified itself (the store's own mark-then-throw) arrives as
 * [RootUnavailable] and passes straight through: it is marked, and there is nothing left to decide.
 *
 * The probe is the store's [ContentStore.available], for the history port too: a root's work tree IS its
 * content tree, so one probe answers for both backends and there is exactly one notion of "is it there".
 *
 * Constructed off the [RootAvailability] holder every user already holds (`IndexBuilder`, `WritePipeline`,
 * `GuardedReadFacade`), so putting the rule in one place costs no new wiring.
 */
class RootLossClassifier(private val availability: RootAvailability) {

    /**
     * Probes [store] and MARKS [root] if it is gone, answering whether it was. A live root answers false and
     * marks nothing, so a mark is true by construction. The callers that must still produce their own outcome
     * (the write pipeline's post-write catches: bytes ARE on disk, the dirty mark IS retained) use this
     * directly - marking and answering are separate obligations.
     */
    fun markIfGone(root: RootName, store: ContentStore): Boolean {
        if (store.available()) return false
        availability.markUnavailable(root, UnavailableCause.VANISHED)
        logger.warn {
            "root '$root' is no longer available; it will serve 503 until it is restored and the server restarted"
        }
        return true
    }

    /**
     * Runs [call] against [root]'s backend under the exit-boundary rule: a root-loss carrier that the probe
     * CONFIRMS becomes a marked [RootUnavailable] (the wire's one 503), and anything else propagates as itself.
     *
     * This is what a request-serving surface wants. A rebuild instead wants to SKIP and carry the root's
     * last-good section, so it uses [markIfGone] behind its own classifier rather than this.
     */
    fun <T> guarding(root: RootName, store: ContentStore, call: () -> T): T =
        try {
            call()
        } catch (e: RootUnavailable) {
            throw e // the store already classified and marked on its way out; this is only the carrier
        } catch (e: IOException) {
            rootGone(root, store, e)
        } catch (e: HistoryCommandException) {
            rootGone(root, store, e)
        }

    private fun rootGone(root: RootName, store: ContentStore, failure: Exception): Nothing {
        if (!markIfGone(root, store)) throw failure
        throw RootUnavailable(root, UnavailableCause.VANISHED)
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
