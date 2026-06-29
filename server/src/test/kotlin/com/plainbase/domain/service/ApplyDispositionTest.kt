package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The FROZEN `WriteOutcome` -> [ApplyDisposition] table (P1b, WI-3): ONE test per `WriteOutcome` variant + the two
 * `Conflict` sub-branches (idempotent-replay vs real drift). Pure: the mapper takes no I/O, no clock — only the
 * outcome + the proposed-content hash.
 */
class ApplyDispositionTest : FunSpec({

    val proposed = "sha256:" + "a".repeat(64)
    val other = "sha256:" + "b".repeat(64)
    val path = TreePath.require("guides/deploy.md")

    test("Written -> Applied(reindexDeferred=false), carrying the commit") {
        val d = dispositionOf(WriteOutcome.Written(newHash = proposed, commit = "abc"), proposed)
        d shouldBe ApplyDisposition.Applied(proposed, "abc", reindexDeferred = false)
    }

    test("WrittenButUnindexed -> Applied(reindexDeferred=true, commit=null)") {
        val d = dispositionOf(WriteOutcome.WrittenButUnindexed(newHash = proposed, cause = "fts boom"), proposed)
        d shouldBe ApplyDisposition.Applied(proposed, commit = null, reindexDeferred = true)
    }

    test("Conflict with currentHash != proposedHash -> Conflicted (real drift)") {
        val d =
            dispositionOf(WriteOutcome.Conflict("content_changed", currentContent = "x", currentHash = other, currentPath = path), proposed)
        d shouldBe ApplyDisposition.Conflicted("content_changed", other, path)
    }

    test("Conflict with currentHash == proposedHash -> Applied (idempotent-replay; keys on EXACT bytes)") {
        // The disk ALREADY equals the proposed bytes -> APPLIED, not CONFLICTED.
        val d =
            dispositionOf(
                WriteOutcome.Conflict("content_changed", currentContent = "x", currentHash = proposed, currentPath = path),
                proposed,
            )
        d shouldBe ApplyDisposition.Applied(proposed, commit = null, reindexDeferred = false)
        // A one-byte difference flips it back to CONFLICTED (the brittleness guard).
        dispositionOf(
            WriteOutcome.Conflict("content_changed", "x", other, path),
            proposed,
        ).shouldBeInstanceOf<ApplyDisposition.Conflicted>()
    }

    test("Conflict with currentHash == null (page_deleted) -> Conflicted") {
        val d =
            dispositionOf(WriteOutcome.Conflict("page_deleted", currentContent = null, currentHash = null, currentPath = null), proposed)
        d shouldBe ApplyDisposition.Conflicted("page_deleted", null, null)
    }

    test("Unreadable -> Failed('unreadable') — the EXACT stable string, a varying cause does NOT change the reason (no leak)") {
        dispositionOf(WriteOutcome.Unreadable(cause = "/secret/fs/path: permission denied"), proposed) shouldBe
            ApplyDisposition.Failed("unreadable")
        dispositionOf(WriteOutcome.Unreadable(cause = "totally different cause"), proposed) shouldBe ApplyDisposition.Failed("unreadable")
    }

    test("UnsupportedEdit -> Failed('unsupported_edit: <field>')") {
        dispositionOf(WriteOutcome.UnsupportedEdit(field = "id"), proposed) shouldBe ApplyDisposition.Failed("unsupported_edit: id")
    }

    test("the three create-only variants map to a STABLE, no-interpolation create_* Failed reason (C1, REACHABLE)") {
        // No interpolation: a varying path/url/reason does NOT change the wire status_reason (deterministic + no leak).
        dispositionOf(WriteOutcome.AlreadyExists(path), proposed) shouldBe ApplyDisposition.Failed("create_path_taken")
        dispositionOf(WriteOutcome.AlreadyExists(TreePath.require("other/x.md")), proposed) shouldBe
            ApplyDisposition.Failed("create_path_taken")
        dispositionOf(WriteOutcome.SlugConflict("guides/x"), proposed) shouldBe ApplyDisposition.Failed("create_slug_conflict")
        dispositionOf(WriteOutcome.InvalidLocation("bad"), proposed) shouldBe ApplyDisposition.Failed("create_invalid_location")
    }
})
