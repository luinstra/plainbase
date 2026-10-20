package com.plainbase.domain.service

import com.plainbase.domain.page.PageIndex

/** Describes reader permalinks whose identities are absent from the published source files. */
fun idLessPageWarning(snapshot: PageIndex): String? {
    val pages = snapshot.pages.filterNot { it.materialized }
    if (pages.isEmpty()) return null
    val shown = pages.take(5).joinToString(", ") { "${it.root.value}:${it.path.value}" }
    val more = if (pages.size > 5) " (+${pages.size - 5} more)" else ""
    return "${pages.size} page(s) have no usable frontmatter id (missing, malformed or duplicated); " +
        "their /p/ permalinks depend on minted ids in DATA_DIR/plainbase.db; " +
        "object readers use a fresh DATA_DIR on every start, while local readers retain ids across restarts " +
        "if that database is kept: " +
        "$shown$more. Write unique ids into the source checkout (plainbase adopt --write-ids there) before publishing."
}
