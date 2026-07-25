package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath

/**
 * A content path qualified by its root: the composite key every path-bearing surface adopts once
 * multi-root lands (C2). [path] keeps its root-relative [TreePath] semantics unchanged.
 *
 * Introduced ahead of its consumers so C2 has one type to key on. Eventual coverage (the C2
 * checklist): pages, folders, assets, aliases, identity issues, checkpoints, dirty pages, and
 * create/write targets - every path-bearing surface, not just the page index.
 */
data class RootedPath(val root: RootName, val path: TreePath)
