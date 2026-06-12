package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.ColumnAdapter
import com.plainbase.domain.content.TreePath

/**
 * [TreePath] <-> TEXT at the repository boundary: columns store the canonical NFC `/`-joined form
 * ([TreePath.value]); decoding re-runs the chunk 1.5 validation, so a corrupt row fails loudly
 * instead of leaking an invalid path into the domain. Reflection-free (native gate).
 */
object TreePathColumnAdapter : ColumnAdapter<TreePath, String> {

    override fun decode(databaseValue: String): TreePath = TreePath.require(databaseValue)

    override fun encode(value: TreePath): String = value.value
}
