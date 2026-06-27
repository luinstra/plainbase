package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.ProposeCommand
import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The shared, CALL-FREE propose-command parser (WI-3): the SAME F4 malformed-shape matrix the REST route enforced,
 * extracted so MCP `propose_change` can't drift from `proposalRoutes`. Enumerates the FULL Invalid matrix (incl. the
 * `target_path`-invalid rows for BOTH the edit + create branches) with the EXACT message strings the REST 400s used,
 * plus the Ok rows (proving `proposed_content` is UTF-8-encoded into the command bytes).
 */
class ProposeCommandParserTest : FunSpec({

    val validPageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val validBaseHash = "sha256:" + "a".repeat(64)

    fun req(
        operation: String = "edit",
        pageId: String? = null,
        baseHash: String? = null,
        targetPath: String? = null,
        proposedContent: String = "# Body",
        rationale: String = "because",
    ) = ProposeChangeRequest(operation, pageId, baseHash, targetPath, proposedContent, rationale)

    fun invalidMessage(request: ProposeChangeRequest): String =
        (parseProposeCommand(request) as ProposeCommandParse.Invalid).message

    test("Ok edit: a valid edit yields ProposeCommand.Edit with the UTF-8 proposed_content bytes") {
        val parse = parseProposeCommand(req(pageId = validPageId, baseHash = validBaseHash, proposedContent = "edited 🚀"))
        val command = parse.shouldBeInstanceOf<ProposeCommandParse.Ok>().command.shouldBeInstanceOf<ProposeCommand.Edit>()
        command.proposedContent.toList() shouldBe "edited 🚀".encodeToByteArray().toList()
    }

    test("Ok create: a valid create yields ProposeCommand.Create with the target path + bytes") {
        val parse = parseProposeCommand(req(operation = "create", targetPath = "notes/new.md", proposedContent = "新規"))
        val command = parse.shouldBeInstanceOf<ProposeCommandParse.Ok>().command.shouldBeInstanceOf<ProposeCommand.Create>()
        command.targetPath.value shouldBe "notes/new.md"
        command.proposedContent.toList() shouldBe "新規".encodeToByteArray().toList()
    }

    test("Invalid shared-field rows") {
        invalidMessage(req(proposedContent = "   ")) shouldBe "proposed_content must not be empty"
        invalidMessage(req(rationale = "   ")) shouldBe "rationale must not be blank"
        invalidMessage(req(operation = "delete")) shouldBe "operation must be one of edit, create"
    }

    test("Invalid edit rows") {
        invalidMessage(req(operation = "edit", pageId = null, baseHash = validBaseHash)) shouldBe "an edit requires page_id"
        invalidMessage(req(operation = "edit", pageId = "not-a-uuid", baseHash = validBaseHash)) shouldBe "page_id is not a valid UUID"
        invalidMessage(req(operation = "edit", pageId = validPageId, baseHash = null)) shouldBe "an edit requires base_hash"
        invalidMessage(req(operation = "edit", pageId = validPageId, baseHash = "deadbeef")) shouldBe
            "base_hash must be the sha256:<64-hex> form"
    }

    test("Invalid create rows") {
        invalidMessage(req(operation = "create", pageId = validPageId, targetPath = "x.md")) shouldBe
            "a create has no existing page; page_id is contradictory"
        invalidMessage(req(operation = "create", baseHash = validBaseHash, targetPath = "x.md")) shouldBe
            "a new page has no base; base_hash is contradictory"
        invalidMessage(req(operation = "create", targetPath = null)) shouldBe "a create requires target_path"
    }

    test("Invalid target_path rows reject traversal / absolute / empty-segment for BOTH edit and create") {
        for (bad in listOf("../escape.md", "/x.md", "a//b.md")) {
            // Edit: an OPTIONAL client target_path that is structurally invalid is still a 400.
            invalidMessage(req(operation = "edit", pageId = validPageId, baseHash = validBaseHash, targetPath = bad)) shouldBe
                "target_path is not a valid content-relative path: '$bad'"
            // Create: the REQUIRED authoritative target_path.
            invalidMessage(req(operation = "create", targetPath = bad)) shouldBe
                "target_path is not a valid content-relative path: '$bad'"
        }
    }
})
