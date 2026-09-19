package com.plainbase.frameworks.protocol

/**
 * The frozen drift-only `reason` enum (PB-WRITE-1): the set is `{content_changed, page_moved,
 * page_deleted}` and only ever grows (additive). `page_moved` is PRODUCER-RESERVED — no §H mover
 * emits it yet, but the value is pinned so a future producer adds no new vocabulary. **`id_changed`
 * is deliberately NOT a member** (the debate's sharpest fix): id/slug/redirect_from rejections are
 * 422 + code + field, never a drift discriminator.
 */
object WriteConflictReason {
    const val CONTENT_CHANGED: String = "content_changed"
    const val PAGE_MOVED: String = "page_moved"
    const val PAGE_DELETED: String = "page_deleted"

    /** The frozen reason set (additive-only). Pinned by the golden suite's reason-enum assertion. */
    val ALL: Set<String> = setOf(CONTENT_CHANGED, PAGE_MOVED, PAGE_DELETED)
}
