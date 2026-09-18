package com.plainbase.frameworks.protocol

import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.ProposeCommand
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM/native-compatible characterization for the B3 parser and canonical boundary grammar. */
@Tag("native")
class ProposeCommandParserNativeTest {

    @Test
    fun `command parsing preserves compact ids roots paths precedence and UTF-8 bytes`() {
        val compactId = "0197a3f28c4d7e91b3a24f8e9d1c6b5a"
        val editResult =
            parseProposeCommand(
                ProposeChangeRequest(
                    operation = "edit",
                    root = "unregistered",
                    pageId = compactId,
                    baseHash = "sha256:${"a".repeat(64)}",
                    targetPath = "notes/edit.md",
                    proposedContent = "編集 🚀",
                    rationale = "because",
                ),
                setOf(RootName.PRIMARY),
            )
        assertTrue(editResult is ProposeCommandParse.Ok, "expected Ok, got $editResult")
        val edit = editResult.command
        assertTrue(edit is ProposeCommand.Edit, "expected Edit, got $edit")
        val editCommand = edit
        assertEquals("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", editCommand.pageId.value)
        assertEquals("unregistered", editCommand.root?.value)
        assertEquals("notes/edit.md", editCommand.clientTargetPath?.value)
        assertEquals("編集 🚀".encodeToByteArray().toList(), editCommand.proposedContent.toList())

        val createResult =
            parseProposeCommand(
                ProposeChangeRequest(
                    operation = "create",
                    root = "archive",
                    targetPath = "notes/new.md",
                    proposedContent = "新規",
                    rationale = "because",
                ),
                setOf(RootName.PRIMARY, RootName.require("archive")),
            )
        assertTrue(createResult is ProposeCommandParse.Ok, "expected Ok, got $createResult")
        val create = createResult.command
        assertTrue(create is ProposeCommand.Create, "expected Create, got $create")
        val createCommand = create
        assertEquals("archive", createCommand.root.value)
        assertEquals("notes/new.md", createCommand.targetPath.value)
        assertEquals("新規".encodeToByteArray().toList(), createCommand.proposedContent.toList())

        val invalidCreateResult =
            parseProposeCommand(
                ProposeChangeRequest(
                    operation = "create",
                    root = "ghost",
                    targetPath = "../escape.md",
                    proposedContent = "new",
                    rationale = "because",
                ),
                setOf(RootName.PRIMARY),
            )
        assertTrue(invalidCreateResult is ProposeCommandParse.Invalid, "expected Invalid, got $invalidCreateResult")
        val invalidCreate = invalidCreateResult
        assertEquals(ErrorCodes.INVALID_ROOT, invalidCreate.code)
        assertEquals("Unknown root: 'ghost'", invalidCreate.message)
    }

    @Test
    fun `canonical id grammar accepts uppercase hyphenated and rejects compact malformed forms`() {
        val uppercase = "0197A3F2-8C4D-7E91-B3A2-4F8E9D1C6B5A"
        val compact = "0197a3f28c4d7e91b3a24f8e9d1c6b5a"
        val malformed = listOf(compact, "1-1-1-1-1", "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5z")

        assertTrue(CANONICAL_PAGE_ID.matches(uppercase))
        assertTrue(CANONICAL_PROPOSAL_ID.matches(uppercase))
        for (value in malformed) {
            assertFalse(CANONICAL_PAGE_ID.matches(value))
            assertFalse(CANONICAL_PROPOSAL_ID.matches(value))
        }
        assertTrue(CANONICAL_PAGE_ID === CANONICAL_PROPOSAL_ID)
    }
}
