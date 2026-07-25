package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath

/**
 * **What ONE complete bucket LIST saw** - the object backend's only honest statement about what the bucket holds, and
 * therefore the only thing an `OBJECT_LIST` proof can be built from.
 *
 * A generation is published WHOLE or not at all. **LIST pagination is not atomic**, so a run that errored on any page
 * (or did not reach the end) publishes NOTHING and the previous generation stands: an incomplete manifest is not a
 * smaller corpus, it is an unknown one, and the difference is a mass delete.
 *
 * [rowsAtStart] is the boundary that makes the non-atomicity safe. It is the root's durable bindings as they stood
 * **before the first LIST page went out**, so a page CREATED during the pagination cannot be in it, and therefore can
 * never be covered by this generation's proof - it simply waits for the next cycle. Without it, a create racing a
 * LIST is reaped by the very LIST that could not have seen it. (Unchanged keys are guaranteed present in a paginated
 * S3 LIST, so a STABLE object cannot be falsely absent; the boundary handles the unstable ones.)
 *
 * [binding] is stamped in so the manifest can never be cashed against a bucket it did not list: a generation from the
 * binding we USED to point at proves nothing about the one we point at now.
 *
 * [bindingEpoch] is the SECOND freshness stamp (revoke-before-stamp, C5), co-read with [rowsAtStart] at the pagination
 * boundary - NOT at mint time, which is a whole poll cycle later. The negative evidence an `OBJECT_LIST` proof rests on
 * is `rowsAtStart - listed`, snapshotted when the LIST began; stamping the proof with the epoch AS OF THAT SNAPSHOT is
 * what lets a restore's re-bind landing between the poll and the reap advance the root's binding_epoch past it, so the
 * proof loses `applyProofs`' two-token compare. Stamped at mint instead, the stamp would already reflect that re-bind
 * and the compare would MATCH the reap it must forbid.
 */
data class ObjectManifest(
    val binding: RootBinding,
    /** Every eligible key the LIST returned, as the [TreePath] the mirror serves it under. */
    val listed: Set<TreePath>,
    /** The durable bindings for this root, read BEFORE the first LIST page. The boundary - see the class doc. */
    val rowsAtStart: Set<BindingRef>,
    /** The root's binding_epoch, co-read with [rowsAtStart] at that same boundary (revoke-before-stamp). */
    val bindingEpoch: BindingEpoch,
)

/**
 * The pagination-boundary snapshot the object store reads ONCE per generation, BEFORE the first LIST page: the root's
 * durable [rows] and the [bindingEpoch] as of that same instant, co-read so no bind slips between them (the epoch is
 * read FIRST, so a bind landing in the gap advances it past this value and the proof it stamps fails closed).
 */
data class RowsAtStart(val rows: Set<BindingRef>, val bindingEpoch: BindingEpoch)

/**
 * The port the rebuild asks an object-backed root for its latest COMPLETE listing. A LOCAL root has none: its absence
 * authority is the observation epoch ([ObservationEpoch]), which is a different kind of evidence entirely.
 */
fun interface ObjectManifestProvider {

    /**
     * The latest generation this store published, or **null when it has never completed a LIST** - a store that has
     * listed nothing vouches for nothing, and that includes vouching that its mirror is a whole view of a corpus.
     */
    fun latestManifest(): ObjectManifest?
}
