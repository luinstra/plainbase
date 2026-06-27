package com.plainbase.domain.service

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.AgentMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * P5 — the PURE decision core + glob matcher (WI-1/WI-2). The load-bearing invariant is FAIL-SAFE: every matcher
 * ambiguity resolves to NO-MATCH -> DEGRADE. A wrong degrade is an unnecessary human review (safe); a wrong direct
 * commit is an unreviewed privileged write (escalation). The adversarial matrix below pins every escape edge to
 * DEGRADE, and the truth table pins COMMIT-in-glob alone to a direct commit.
 */
class AgentDirectCommitDecisionTest : FunSpec({

    fun glob(raw: String) = CommitGlob.parse(raw)
    fun path(raw: String) = TreePath.require(raw)
    infix fun String.matches(p: String) = glob(this).matches(path(p))

    // ---- the matcher matrix (case-sensitive, post-NFC) -----------------------------------------------

    test("a plain multi-segment glob matches the exact path only") {
        ("docs/guide.md" matches "docs/guide.md") shouldBe true
        ("docs/guide.md" matches "docs/other.md") shouldBe false
        ("docs/guide.md" matches "docs/guide.md/extra") shouldBe false
    }

    test("`*` matches within ONE segment and NEVER crosses `/`") {
        ("docs/*" matches "docs/a.md") shouldBe true
        ("docs/*" matches "docs/a-long-name.md") shouldBe true
        ("docs/*" matches "docs/a/b.md") shouldBe false // the crux: * does not cross /
        ("docs/*" matches "docs") shouldBe false // * needs a segment to match
    }

    test("`?` matches exactly one non-`/` char") {
        ("docs/?.md" matches "docs/a.md") shouldBe true
        ("docs/?.md" matches "docs/ab.md") shouldBe false
        ("docs/?.md" matches "docs/.md") shouldBe false
    }

    test("`**` matches ZERO OR MORE whole segments — both directions") {
        ("docs/**" matches "docs") shouldBe true // ZERO segments (PINNED)
        ("docs/**" matches "docs/a.md") shouldBe true // one
        ("docs/**" matches "docs/a/b.md") shouldBe true // two
        ("docs/**" matches "other/a.md") shouldBe false
    }

    test("`**/foo.md` matches foo.md at root AND nested") {
        ("**/foo.md" matches "foo.md") shouldBe true // ** absorbs zero segments
        ("**/foo.md" matches "a/b/foo.md") shouldBe true
        ("**/foo.md" matches "a/foo.md.bak") shouldBe false
    }

    test("a `**` in the middle absorbs zero-or-more segments") {
        ("docs/**/x.md" matches "docs/x.md") shouldBe true
        ("docs/**/x.md" matches "docs/a/b/x.md") shouldBe true
        ("docs/**/x.md" matches "docs/a/x.md.bak") shouldBe false
    }

    test("matching is CASE-SENSITIVE -> a case mismatch degrades") {
        ("Docs/**" matches "docs/x.md") shouldBe false
        ("docs/Guide.md" matches "docs/guide.md") shouldBe false
    }

    // ---- parse normalization (leading/trailing slash, NFC) -------------------------------------------

    test("a leading `/` is stripped at parse (equivalent to the slash-free glob)") {
        ("/docs/**" matches "docs/a.md") shouldBe true
        ("/docs/**" matches "docs") shouldBe true
    }

    test("a single trailing `/` drops the empty trailing segment") {
        ("docs/" matches "docs") shouldBe true
        ("docs/" matches "docs/a.md") shouldBe false // `docs/` is the single segment `docs`, NOT `docs/**`
    }

    test("an NFD-form glob is NFC-normalized at parse so it matches the always-NFC TreePath") {
        // "cafe-acute/notes.md" authored via escapes so the input is unambiguously NFD regardless of how this source
        // file is normalized on disk. TreePath.value is always NFC, so without the parse-time NFC normalize an NFD
        // glob would silently NEVER match the NFC path.
        val nfd = "caf\u0065\u0301/notes.md" // ASCII e + U+0301 combining acute (decomposed, NFD)
        val nfcPath = "caf\u00e9/notes.md" // precomposed U+00E9 (composed, NFC)
        (nfd != Nfc.normalize(nfd)) shouldBe true // the input really was NFD
        CommitGlob.parse(nfd).matches(TreePath.require(nfcPath)) shouldBe true
    }

    // ---- parse rejections (fail-fast, naming the bad pattern) ----------------------------------------

    test("a blank, empty, `..`, `.`, or empty-interior-segment glob is rejected naming the pattern") {
        shouldThrow<IllegalArgumentException> { CommitGlob.parse("") }.message shouldContain "blank"
        shouldThrow<IllegalArgumentException> { CommitGlob.parse("   ") }.message shouldContain "blank"
        shouldThrow<IllegalArgumentException> { CommitGlob.parse("docs/../secrets") }.message shouldContain "docs/../secrets"
        shouldThrow<IllegalArgumentException> { CommitGlob.parse("docs/./x") }.message shouldContain "docs/./x"
        shouldThrow<IllegalArgumentException> { CommitGlob.parse("docs//x") }.message shouldContain "docs//x"
        shouldThrow<IllegalArgumentException> { CommitGlob.parse("/") }.message shouldContain "empty segment"
    }

    // ---- the decision truth table --------------------------------------------------------------------

    test("agentWriteDecision: COMMIT in-glob -> DirectCommit; everything else -> DegradeToProposal") {
        val globs = listOf(glob("docs/**"))
        val inGlob = path("docs/a.md")
        val outGlob = path("guides/a.md")

        agentWriteDecision(AgentMode.COMMIT, globs, inGlob).shouldBeInstanceOf<AgentWriteDecision.DirectCommit>()
        agentWriteDecision(AgentMode.COMMIT, globs, outGlob).shouldBeInstanceOf<AgentWriteDecision.DegradeToProposal>()
        agentWriteDecision(AgentMode.PROPOSE, globs, inGlob).shouldBeInstanceOf<AgentWriteDecision.DegradeToProposal>()
        agentWriteDecision(AgentMode.READ_ONLY, globs, inGlob).shouldBeInstanceOf<AgentWriteDecision.DegradeToProposal>()
        agentWriteDecision(AgentMode.COMMIT, emptyList(), inGlob).shouldBeInstanceOf<AgentWriteDecision.DegradeToProposal>()
    }

    test("no-divergence: the decision's targetPath is the SAME object passed in (reference identity, WI-3)") {
        val target = path("docs/a.md")
        val decision = agentWriteDecision(AgentMode.COMMIT, listOf(glob("docs/**")), target)
        decision.shouldBeInstanceOf<AgentWriteDecision.DirectCommit>()
        // The facade builds the WriteIntent from decision.targetPath, so === here means matched path === written path.
        (decision.targetPath === target) shouldBe true
    }
})
