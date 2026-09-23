# 13. Discussions are Markdown files in a per-root collection, anchored by quote

- **Status:** Accepted; the one item marked *proposed* is finalized during implementation
- **Date:** 2026-09-23
- **Deciders:** luinstra (rulings in the 2026-09-23 design session); the passage-anchor choice was
  debated by a seven-seat panel (six substantive takes, all for the quote anchor), record in
  `.crew/reviews/0cfed0b8-36ec-4dd7-a930-d126406040b5/run-94870ffe1a4b/`
- **Context:** The first slice of the portable-knowledge-workspace direction
  (`docs/design/portable-knowledge-workspace.md`): page and passage comments, modeled from day one as
  Discussions so the later proposal and decision linkage extends them instead of migrating them.

## Context

Comments cannot be reconstructed from Markdown, so they are durable user content and need a storage
and reference contract before any code exists. Four forces shaped it:

- Pages move and get renamed outside Plainbase (`git mv`, editors, agents), and Plainbase only observes
  the result.
- A page's uuid is durable only when the file carries `id:`. For pages whose id lives only in `id_map`,
  losing `DATA_DIR` mints a fresh id (ADR-0012, operating guide "Losing DATA_DIR").
- Agent writes and proposals are guarded by the page's exact content hash. Anything that writes the page
  to record a comment invalidates in-flight agent work on that page.
- Extra roots can be trees Plainbase does not own, including other people's git repositories.

## Decision

**Location.** A root's discussions live in one collection at `<root>/.plainbase/discussions/`, not beside
each folder. Discussions exist only on editable roots in this slice; the UI and API say so explicitly for
the others. `.plainbase/` is never content: an include glob that would expose it is a boot refusal.

**Layout.** One directory per discussion, `<discussion-id>/`, holding `discussion.md` (frontmatter: page
reference, anchor, status) and one append-only `<comment-uuidv7>.md` per comment (frontmatter: author,
time; body: the comment). Concurrent comments from different clones or branches merge without conflict,
and every file stays readable with ordinary tools.

**Page reference.** The page uuid plus the root-relative path at creation. The root is implicit from the
collection's location, so renaming a root in config breaks nothing. The uuid links; the path is the
fallback when the uuid no longer resolves; when both miss, the discussion is Orphaned and keeps showing
its original target. Commenting never writes `id:` (or anything else) into the page.

**Anchor.** The exact selected bytes of the raw source, prefix and suffix context, the page content hash
at creation, the commit id when the root has git history, the initial line, and the source range as byte
offsets over the raw UTF-8 file (frontmatter included). When the selection is not verbatim in its block's
raw source, the anchor covers the whole block rather than a guessed range. The form of the enclosing
heading anchor is *proposed* and is finalized once rendered selections are proven to map back to raw
source. The anchor is permanent. Re-anchoring is a pure, deterministic function
that reports `exact`, `moved`, `ambiguous` or `changed` as an inferred current match and is never written
back as the target. Ambiguity is reported, never resolved to the first match; the line never breaks a
tie. A person may Reattach a discussion to revised text; the original anchor is kept. No page snapshots
are stored: the quote and its context are the retained evidence.

**History.** On local roots with git history, discussion files are committed like page edits: one
attributed commit per discussion action, with a filterable `discussion: ` message prefix. In object mode,
discussions are stored in the bucket under `.plainbase/discussions/` with no commit history in this slice;
bucket versioning covers recovery. Adding history there later is additive: commits start from that point
and nothing migrates.

**Authority.** Files are the authority. The server keeps a derived, deletable discussion index, rebuilt
from the files and kept current by a watcher exception scoped to `.plainbase/discussions/` only.

## Consequences

- A reworded passage shows as changed until someone reattaches it. This is deliberate: silently moving a
  comment onto new text is the failure the design forbids, and every substantive panel seat named it as
  the quote anchor's cost.
- In-page markers were rejected for this slice: they change the page hash (conflicting in-flight agent
  proposals), commit into user repos per comment, would likely render as visible text under the escaping
  renderer (the escape setting is confirmed; rendered output is not yet probed), and fail silently when
  stripped or copied. The anchor record is typed so an opt-in block id can be added
  later without a migration.
- Inline comment blocks (`> [!COMMENT]`) were rejected: they make conversation part of the document's
  authority and of every raw agent read.
- Without page snapshots, a changed passage cannot show the full old page when git is off; placement falls
  back to the enclosing heading, then the initial line, always labeled as "was around here".
- Edits, retractions and admin purges of comments do not remove old text from git history. The product
  says so rather than implying deletion.
- Object-mode deployments treat `.plainbase/` as app-owned bucket space that the mirror skips (it skips
  every dot-prefixed key, so no other hidden name would change this). Discussions there need their own
  storage adapter, which can write those keys directly; they have no history until a later slice adds it.
