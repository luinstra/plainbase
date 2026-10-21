package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LandingParityTest : FunSpec({
    val cases = Json.parseToJsonElement(
        checkNotNull(LandingParityTest::class.java.getResource("/landing-cases.json")).readText(),
    ).jsonArray

    cases.forEach { entry ->
        val fixture = entry.jsonObject
        test("landing selection matches ${fixture.getValue("name").jsonPrimitive.content}") {
            val children = fixture.getValue("children").jsonArray.map { element ->
                val child = element.jsonObject
                val path = TreePath.require(child.getValue("path").jsonPrimitive.content)
                when (child.getValue("type").jsonPrimitive.content) {
                    "page" -> TreeNode.Page(
                        id = PageId.require(child.getValue("id").jsonPrimitive.content),
                        title = path.value,
                        slug = "unrelated",
                        path = path,
                        url = "/docs/unrelated",
                        status = "active",
                        updated = null,
                    )
                    "folder" -> TreeNode.Folder(
                        name = path.name,
                        title = null,
                        description = null,
                        path = path,
                        url = "/docs/${path.value}",
                        pageCount = 0,
                        children = emptyList(),
                    )
                    else -> error("Unknown fixture node type")
                }
            }
            val folder = TreeNode.Folder("", null, null, null, "/docs", children.count { it is TreeNode.Page }, children)
            TreeBuilder.landingPage(folder)?.id?.value shouldBe fixture.getValue("expected").jsonPrimitive.contentOrNull
        }
    }
})
