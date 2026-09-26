package com.plainbase.domain.discussion

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.root.RootName

/**
 * Reads the current page selected by its stable page ID through the index and content-read policy.
 * Implementations never resolve a page from the marker's stored `page_path`.
 */
fun interface DiscussionPageSource {
    fun read(root: RootName, page: PageRef): ContentRead
}
