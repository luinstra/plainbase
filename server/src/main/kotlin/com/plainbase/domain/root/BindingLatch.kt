package com.plainbase.domain.root

import com.plainbase.domain.repository.RootTopologyRepository
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * **A successful complete bucket LIST is positive proof of absence - but only about the bucket it LISTED.** (C3)
 *
 * That sentence is the whole of this class. A wrong bucket name, a wrong prefix, or credentials scoped to a different
 * EMPTY bucket produce a **successful empty LIST**, which reads as an authoritative "the corpus is gone" and deletes
 * everything, with no ceremony and no error, BY DESIGN. It is the bind-mount bug one level up: a readable, complete,
 * entirely valid view of the WRONG UNIVERSE, accepted as proof about the right one.
 *
 * So a LIST is not authority. A LIST **under a binding we have verified is the same universe our rows describe** is:
 *
 * ```
 * UNRESOLVED --[every at-risk binding WITNESSED BY IDENTITY]--> TRUSTED   (or an operator accepts - C5)
 *     mints NOTHING while it waits, across polls AND restarts.
 *
 * TRUSTED --[a complete, generation-bound LIST]--> AbsenceProof(OBJECT_LIST) over rowsAtStart - listed
 *     a DRAINED bucket under a TRUSTED binding reaps normally: that is an ordinary delete, and it converges.
 *
 * any binding CHANGE --> UNRESOLVED, at_risk re-snapshotted, observation token revoked.
 * ```
 *
 * **Witnessing by IDENTITY, not by name, and the difference is the corpus.** A bucket LIST returns keys and etags; it
 * does NOT return frontmatter ids. So "the LIST saw every at-risk path" proves nothing at all: a wrong bucket holding
 * IDENTICAL PATHS (a stale copy, a clone, a restore from somewhere else) satisfies it and promotes ITSELF to TRUSTED.
 * The witness must therefore carry the id the file actually holds ([Witness.observedId]), which the rebuild already
 * reads for every page it scans. It costs nothing extra here, once, on the rarest event in the system - and it is the
 * event where being wrong costs the corpus.
 *
 * **The honest residue, said out loud rather than papered over:** an UNMATERIALIZED page carries no id in its bytes,
 * so it cannot be witnessed this way at all. A root holding any of them can never earn TRUSTED by witness, and needs
 * the operator path (`plainbase root reconcile --accept`, C5). It still SERVES every page it can read; what it does
 * not get is the right to delete anything. That is the correct trade, and it is not a bug to be optimized away.
 */
class BindingLatch(private val topology: RootTopologyRepository) {

    /**
     * **BOOT: where this root now points.** Called once per root, BEFORE the first LIST, from the one place that knows
     * the configured binding. First sight or ANY change latches [BindingStatus.UNRESOLVED] with a fresh at-risk
     * snapshot; an unchanged binding keeps whatever trust it had earned.
     *
     * The status comes back because the caller owes an UNRESOLVED binding one more thing: it must re-derive whatever
     * DERIVED VIEW it serves that root from (the object backend's mirror), so that what the latch later witnesses is
     * the tree we are now bound to rather than a cached copy of the one we used to be.
     */
    fun observe(root: RootName, binding: RootBinding): BindingStatus {
        val latched = topology.observeBinding(root, binding)
        when (latched.status) {
            BindingStatus.TRUSTED -> logger.info { "root '$root' is bound to a TRUSTED ${binding.value}" }
            BindingStatus.UNRESOLVED -> logger.warn {
                "root '$root' is bound to an UNRESOLVED ${binding.value} (${describe(latched.atRisk)}): it will SERVE what it " +
                    "can read there, and it will PROVE NOTHING - no page of this root is deleted until every at-risk binding " +
                    "is witnessed by identity in that tree, or an operator accepts the difference"
            }
        }
        return latched.status
    }

    /**
     * **What a complete LIST under [manifest] proves gone for [root]** - and nothing else. Empty unless the binding is
     * TRUSTED, which this attempts to earn first ([promote]).
     *
     * [witnessed] is the pass's own witness map, carrying the id each page's bytes actually held. A page this pass
     * READ is never covered: a witness is positive evidence of PRESENCE, and proving a page we are looking at to be
     * absent is not a proof, it is a contradiction.
     */
    fun proven(root: RootName, manifest: ObjectManifest, witnessed: Map<RootedPath, Witness>): Set<BindingRef> {
        val latched = topology.topology(root) ?: return emptySet() // no durable latch: no authority, ever
        if (manifest.binding != latched.binding) {
            // A generation listed under a binding we are no longer latched to. It described a different universe, and
            // whatever it failed to see there says nothing about this one.
            logger.warn {
                "discarding root '$root''s bucket listing: it was taken against ${manifest.binding.value}, and the root is " +
                    "now bound to ${latched.binding.value}"
            }
            return emptySet()
        }
        if (!trusted(root, latched, witnessed)) return emptySet()
        val gone = manifest.rowsAtStart.filterTo(mutableSetOf()) {
            it.path !in manifest.listed && RootedPath(root, it.path) !in witnessed
        }
        if (gone.isNotEmpty()) {
            logger.info {
                "a complete LIST of root '$root''s TRUSTED ${latched.binding.value} does not hold ${gone.size} binding(s) it " +
                    "held when the listing began: they are DELETED - ${gone.joinToString { it.path.value }}"
            }
        }
        return gone
    }

    /**
     * **The at-risk incumbents an UNRESOLVED [root] will not let a suspect tree DISPLACE**, keyed by the id each row
     * still holds. Asked AFTER [proven], so a root that just earned its trust protects nothing.
     *
     * The latch was built to guard the ABSENCE half and this door sat open beside it: a DECOY tree whose files carry
     * DIFFERENT ids at the same paths does not need an absence proof to destroy anything. Displacement is POSITIVE
     * evidence ("we read the file, and it no longer holds that id"), so it retires the incumbent with no proof at all
     * - turning every protected page into a 410 before the binding is trusted at all.
     *
     * "We saw it" only means something once we know WHAT WE ARE LOOKING AT.
     */
    fun protects(root: RootName): Set<BindingRef> {
        val latched = topology.topology(root) ?: return emptySet()
        if (latched.status == BindingStatus.TRUSTED) return emptySet()
        return when (val atRisk = latched.atRisk) {
            is AtRisk.Bindings -> atRisk.refs
            AtRisk.Unreadable -> emptySet() // unknowable: it can satisfy nothing, and it can name nothing to protect
        }
    }

    /**
     * TRUSTED already, or earning it now: every at-risk binding witnessed BY IDENTITY in the tree we are now bound to.
     *
     * **"By identity" means "the recorded id, AT ITS RECORDED PATH"** - and that is a deliberate key choice, not an
     * oversight, so it is worth naming. Its cost: a page RENAMED inside an otherwise legitimate tree can never satisfy
     * the at-risk set by witness, so a rebind plus a rename needs the operator path (`plainbase root reconcile`). Its
     * benefit is the reason we pay that: an id-ANYWHERE test would let a RESTRUCTURED CLONE - the same corpus, reorganised,
     * restored from somewhere else - witness every at-risk id and promote ITSELF to TRUSTED, which is the wrong-universe
     * bug wearing the fix's clothes. Trust is the one place we can afford to be strict, because it costs LIVENESS
     * (a 503 and a ceremony) and never the corpus.
     *
     * That is the opposite trade from `IndexBuilder.refuted`, which asks "did we read that id ANYWHERE?" - and the two
     * are consistent, because they answer opposite questions: REFUTING an absence is a claim that a page is PRESENT
     * (weaker evidence is safe - it only ever declines to delete), while EARNING TRUST is a claim that we are looking at
     * the right universe (weaker evidence is a corpus).
     */
    private fun trusted(root: RootName, latched: RootTopology, witnessed: Map<RootedPath, Witness>): Boolean {
        if (latched.status == BindingStatus.TRUSTED) return true
        val atRisk = latched.atRisk
        if (atRisk !is AtRisk.Bindings) {
            logger.warn {
                "root '$root''s at-risk snapshot is UNREADABLE, so nothing can satisfy it: the binding stays UNRESOLVED and " +
                    "proves nothing. Run `plainbase root reconcile` to accept what is actually there."
            }
            return false
        }
        val unwitnessed = atRisk.refs.filterNot { witnessed[RootedPath(root, it.path)]?.observedId == it.id }
        if (unwitnessed.isNotEmpty()) {
            logger.warn {
                "root '$root''s binding ${latched.binding.value} stays UNRESOLVED: ${unwitnessed.size} of ${atRisk.refs.size} " +
                    "at-risk binding(s) are not witnessed BY IDENTITY there (the path is absent, or the file at it carries a " +
                    "different id - or none, which an UNMATERIALIZED page never can). It deletes NOTHING; the pages it cannot " +
                    "account for read 503 until they are seen again or an operator reconciles"
            }
            return false
        }
        topology.trust(root)
        logger.info {
            "root '$root' has WITNESSED all ${atRisk.refs.size} of its at-risk binding(s) BY IDENTITY in " +
                "${latched.binding.value}: the binding is TRUSTED, and a complete LIST of it may now prove a page gone"
        }
        return true
    }

    private fun describe(atRisk: AtRisk): String = when (atRisk) {
        is AtRisk.Bindings ->
            if (atRisk.refs.isEmpty()) {
                "nothing was at risk - it holds no durable rows, so a wrong binding here costs an EMPTY site, never a lost one"
            } else {
                "${atRisk.refs.size} binding(s) at risk"
            }
        AtRisk.Unreadable -> "its at-risk snapshot is UNREADABLE, and an unreadable snapshot can never be satisfied"
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
