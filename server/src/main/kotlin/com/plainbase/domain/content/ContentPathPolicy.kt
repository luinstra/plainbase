package com.plainbase.domain.content

import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * Pure root-content eligibility decisions. The filesystem/configuration adapter compiles the configured rules;
 * consumers use this seam so a path is not independently reinterpreted by scanning, indexing, serving, or writes.
 */
class ContentPathPolicy private constructor(
    private val fileEligibility: (TreePath) -> Boolean,
    private val traversalEligibility: (TreePath?) -> Boolean,
    private val metadataEligibility: (TreePath) -> Boolean,
) {
    fun allowsFile(path: TreePath): Boolean = fileEligibility(path)

    fun mayTraverse(path: TreePath?): Boolean = traversalEligibility(path)

    fun allowsMetadata(folder: TreePath): Boolean = metadataEligibility(folder)

    companion object {
        /** Domain policy for roots whose backing adapter owns its own membership rules. */
        val ALL: ContentPathPolicy = create(
            fileEligibility = { true },
            traversalEligibility = { true },
            metadataEligibility = { true },
        )

        /** The compatibility policy used by directly-constructed stores and object mirrors. */
        fun legacy(): ContentPathPolicy = create(
            fileEligibility = { path -> path.segments.none { it == ".git" || it.startsWith(".") } },
            traversalEligibility = { path -> path == null || path.segments.none { it == ".git" || it.startsWith(".") } },
            metadataEligibility = { path -> path.segments.none { it == ".git" || it.startsWith(".") } },
        )

        fun create(
            fileEligibility: (TreePath) -> Boolean,
            traversalEligibility: (TreePath?) -> Boolean,
            metadataEligibility: (TreePath) -> Boolean,
        ): ContentPathPolicy = ContentPathPolicy(fileEligibility, traversalEligibility, metadataEligibility)
    }
}

/** Root-policy lookups fail closed when wiring omits or misnames a root. */
fun Map<RootName, ContentPathPolicy>.allowsFile(path: RootedPath): Boolean =
    get(path.root)?.allowsFile(path.path) == true

fun Map<RootName, ContentPathPolicy>.allowsFile(root: RootName, path: TreePath): Boolean =
    get(root)?.allowsFile(path) == true
