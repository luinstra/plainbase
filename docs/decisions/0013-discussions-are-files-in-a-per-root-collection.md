# 13. Discussions are Markdown files in a per-root collection, anchored by quote

- **Status:** Accepted
- **Date:** 2026-09-23
- **Deciders:** luinstra (rulings in the 2026-09-23 design session)
- **Context:** This first slice of the portable knowledge workspace direction models page and passage
  comments as Discussions, so later proposal and decision linkage extends them instead of migrating them.

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
each folder. Enabled editable local roots support discussion access; the UI and API report access
unavailable for the others. Disabling a root preserves its existing collection files. Top-level `.plainbase/`
is never page content, with NFC-normalized, case-insensitive reservation. Includes with that first literal-prefix
segment are refused at boot; runtime hiding also prevents wildcard includes from exposing it.
On git-history roots, earlier commits keep the purged text; purge does not rewrite history.

**Layout.** One directory per discussion, `<discussion-id>/`, holding `discussion.md` (frontmatter: page
reference, anchor, status) and one `<comment-uuidv7>.md` per comment (frontmatter: author, time; body: the
comment). Comment files are created create-only, edited and retracted by compare-and-swap, and an admin purge
deletes the file. Readers ignore frontmatter keys and directory entries they do not know, and a rewrite keeps
unknown key lines verbatim. Separate comment files reduce cross-comment merge conflicts without
guaranteeing conflict-free merges. Files remain readable with ordinary tools, but known records parse
strictly; malformed or partial records can need manual intervention.

**Page reference.** The page uuid plus the root-relative path at creation. The root is implicit from the
collection's location, so renaming a root does not require rewriting these stored references;
rooted URLs still change with the name. The uuid links; the path is the
fallback when the uuid no longer resolves; when both miss, the discussion is Orphaned and keeps showing
its original target. Commenting never writes `id:` (or anything else) into the page.

**Anchor.** The exact selected bytes of the raw source, prefix and suffix context, the page content hash
at creation, a commit id when the source hash can be tied to a revision, the initial line, and the source range as byte
offsets over the raw UTF-8 file (frontmatter included). Only selected text occurring exactly once within
the block span narrows the capture; empty selected text, text absent from the span, or repeated text
snaps to the whole block span. Quotes without block offsets instead require a nonempty unique verbatim
match in the Markdown body. The stored heading identity is the full
enclosing heading path, with each level and text, not a generated slug. The original anchor is permanent.
Re-anchoring is a pure, deterministic function
that reports `exact`, `moved`, `ambiguous` or `changed` as an inferred current match and is never written
back as the target. Ambiguity is reported, never resolved to the first match; the line never breaks a
tie. A person may Reattach an open quote discussion after fresh preview and explicit confirmation;
the original anchor is kept alongside the latest reattachment. Current matching and clipped list
quotes use that effective anchor; detail returns both full anchors separately. No page snapshots
are stored: the quote and its context are the retained evidence.

**History.** On local roots with git history, discussion files are committed like page edits: one
attributed commit per discussion action, with a filterable `discussion: ` message prefix. Object-mode Discussions
ship in a later slice; until then object-mode roots report Discussions unavailable.

**Authority.** Files are the authority. The server keeps a derived, deletable discussion index, rebuilt
from the files and kept current by a separate watcher over `.plainbase` and `.plainbase/discussions/`
on discussion-enabled editable local roots available at boot. Configured-disabled roots are excluded from
rebuilds and discussion watchers; their authoritative files remain preserved and must still be backed up.
Re-enable plus restart reparses those files. Added roots require restart for watcher coverage; roots
missing at boot require restoration and restart.
Full reparses scheduled every 60 seconds detect edits inside existing discussion folders; this is
not a freshness deadline. Unsynced reads consult files, while new creates fail closed until indexed
page-count admission recovers. See [operating and recovery guidance](../operating-plainbase.md#discussions).

**Recovery.** With history enabled, boot can best-effort reconcile readable, within-cap entries to Git
before strict record reparse; this does not certify their syntax. In that mode it can restore recognized
purge tombstones when HEAD proves the target, retaining unproven ones with warnings. History-off leaves
purge tombstones untouched. Independently, only recognized regular aged temporary
files are swept, not every crash residual. Uncertain write outcomes require inspection before another
action. Authorized comment purge can remove a safe readable target even with malformed present marker
syntax; a missing marker still refuses. Purge never repairs a marker or erases Git/backups.

## Consequences

- A reworded passage shows as changed until someone reattaches it. This is deliberate: silently moving a
  comment onto new text is the failure the design forbids.
- In-page markers were rejected for this slice: they change the page hash (conflicting in-flight agent
  proposals), commit into user repos per comment, couple conversation storage to renderer behavior,
  and fail silently when stripped or copied. The anchor record is typed so an opt-in block id can be added
  later without a migration.
- Inline comment blocks (`> [!COMMENT]`) were rejected: they make conversation part of the document's
  authority and of every raw agent read.
- Without page snapshots, a changed passage cannot show the full old page when git is off; placement falls
  back to the enclosing heading, then the initial line, always labeled as "was around here".
- Edits, retractions and admin purges of comments do not remove old text from git history. The product
  says so rather than implying deletion.
- Object-mode deployments treat `.plainbase/` as app-owned bucket space that the mirror skips (it skips
  every dot-prefixed key, so no other hidden name would change this). Object-mode Discussions need their own
  storage adapter and ship in a later slice; until then those roots report Discussions unavailable.
