package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageIndexView
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("native")
class CalloutsTaskListsNativeTest {
    @Test
    fun `native renderer handles callout task list and blocked link`() {
        val renderer = FlexmarkRenderer(
            object : PageIndexView {
                override fun kindOf(path: TreePath): PageIndexView.EntryKind? =
                    if (path.value == "page.md") PageIndexView.EntryKind.PAGE else null

                override fun pageUrl(page: TreePath): String = "/docs/page"

                override fun assetUrl(asset: TreePath): String = "/assets/docs/${asset.value}"
            },
        )
        val page = renderer.render(
            TreePath.require("page.md"),
            "> [!NOTE]\n> [done](javascript:alert(1))\n\n- [x] Complete\n".toByteArray(),
        )

        assertTrue(page.html.contains("data-pb-callout=\"note\""))
        assertTrue(page.html.contains("class=\"task-list-item-checkbox\""))
        assertTrue(page.html.contains("checked=\"checked\""))
        assertTrue(page.html.contains("data-pb-link-error=\"blocked_scheme\""))
    }
}
