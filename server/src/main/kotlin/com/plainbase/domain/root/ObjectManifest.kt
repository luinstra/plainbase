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
 */
data class ObjectManifest(
    val binding: RootBinding,
    /** Every eligible key the LIST returned, as the [TreePath] the mirror serves it under. */
    val listed: Set<TreePath>,
    /** The durable bindings for this root, read BEFORE the first LIST page. The boundary - see the class doc. */
    val rowsAtStart: Set<BindingRef>,
)

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
