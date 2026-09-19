package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("native")
class LocalContentPolicyNativeTest {
    @Test
    fun `jdk glob semantics are preserved for immediate and top-level files`() {
        val rootPath = Path.of("/content")
        val root = Root(
            name = RootName.PRIMARY,
            backend = RootBackend.Local(rootPath),
            editable = true,
            history = HistoryMode.OFF,
            includes = listOf("docs/**", "**/*.md"),
        )
        val policy = localContentPathPolicy(root, rootPath, IgnoreRules(), emptyList())

        assertTrue(policy.allowsFile(TreePath.require("docs/readme.md")))
        assertTrue(policy.allowsFile(TreePath.require("nested/readme.md")))
        assertFalse(policy.allowsFile(TreePath.require("readme.md")))
    }
}
