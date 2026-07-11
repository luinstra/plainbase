package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.ColumnAdapter
import com.plainbase.domain.root.RootName

/**
 * [RootName] <-> TEXT at the repository boundary: columns store the validated slug
 * ([RootName.value]); decoding re-runs the C1 slug validation, so a corrupt row fails loudly
 * instead of leaking an invalid root name into the domain. Reflection-free (native gate).
 */
object RootNameColumnAdapter : ColumnAdapter<RootName, String> {

    override fun decode(databaseValue: String): RootName = RootName.require(databaseValue)

    override fun encode(value: RootName): String = value.value
}
