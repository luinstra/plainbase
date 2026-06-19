package com.plainbase.domain.history

import com.plainbase.domain.content.TreePath

/** A two-commit unified diff for one file (non-frozen Phase-3 read shape; W5 owns its evolution). */
data class FileDiff(val from: String, val to: String, val path: TreePath, val unifiedDiff: String)
