package com.plainbase.domain.content

import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.ServerTopLevel

/** URL and classification rules for standalone lowercase Mermaid assets. */
object DiagramAsset {

    fun isStandalone(path: TreePath): Boolean = path.name.endsWith(".mmd")

    fun isBrowserAddressable(path: TreePath): Boolean = isStandalone(path) && '\\' !in path.value

    fun browserUrl(root: RootName, path: TreePath): String =
        "/${ServerTopLevel.BROWSE}/${root.value}/${PercentCoding.encodePath(path.value)}"

    fun sourceUrl(root: RootName, path: TreePath): String =
        "/${ServerTopLevel.ASSETS}/${root.value}/${PercentCoding.encodePath(path.value)}"
}
