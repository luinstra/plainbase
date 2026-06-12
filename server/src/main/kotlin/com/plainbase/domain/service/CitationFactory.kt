package com.plainbase.domain.service

import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.IndexedPage
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Builds §5.3 [Citation]s and owns the one [contentHash] definition (frozen: `sha256:` + 64
 * lowercase hex over the EXACT raw file bytes — pre-decode, BOM included — the same value Phase 3
 * CAS will key on). Heading-level citations arrive with Phase 5's `read_page`; Phase 1 only ever
 * emits page-level ones.
 */
class CitationFactory {

    /** The frozen content-hash form over [rawBytes]: `sha256:` + 64 lowercase hex. */
    fun contentHash(rawBytes: ByteArray): String =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes))

    /** A page-level citation ([Citation.headingId] null); `commit` is always null in Phase 1. */
    fun pageLevel(page: IndexedPage, contentHash: String): Citation =
        Citation(pageId = page.id, headingId = null, path = page.path, contentHash = contentHash, commit = null)
}
