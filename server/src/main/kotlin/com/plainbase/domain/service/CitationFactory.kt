package com.plainbase.domain.service

import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.IndexedPage
import java.security.MessageDigest

/**
 * Owns the one [contentHash] definition (frozen: `sha256:` + 64 lowercase hex over the EXACT raw
 * file bytes — pre-decode, BOM included — the same value Phase 3 CAS will key on) and the
 * [pageLevel] convenience the page read path uses. Citation ASSEMBLY is wider than this class:
 * `SearchService` builds heading-level §5.3 [Citation]s directly from snapshot data (§B7), and
 * the URI grammar lives on [Citation] itself — every construction derives the same frozen `uri`.
 */
class CitationFactory {

    /** The frozen content-hash form over [rawBytes]: `sha256:` + 64 lowercase hex. */
    fun contentHash(rawBytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(rawBytes).toHexString()

    /** A page-level citation ([Citation.headingId] null); `commit` rides the snapshot — the page's last
     *  commit in Git mode (W5), null off Git. */
    fun pageLevel(page: IndexedPage, contentHash: String): Citation =
        Citation(pageId = page.id, headingId = null, path = page.path, contentHash = contentHash, commit = page.commit)
}
