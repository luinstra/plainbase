# 7. Tree-node metadata: editorial dates now, provisional wire fields, Git last-modified stays distinct

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** luinstra (after a two-round four-model design debate: Opus, Codex, Gemini 2.5 Pro, Sonnet 4.6)
- **Context:** Chunk 3 of the UI Identity Revamp (folder-landing cards) needs per-node metadata the
  tree response did not carry — a folder description, a page count, and a per-page date. That requires
  extending the `§A4` tree DTOs (`TreeFolder` / `TreePage` in `RestDtos.kt`, mirrored verbatim in
  `frontend/src/api/types.ts`). Builds on ADR-0003 (folder landing). Settled **before** the build loop.

## Context

The handoff's page rows want a date, and `IndexedPage` has **no** mtime/date/Git field — `commit` is
hard-coded null until the Phase-3 Git layer exists, and the codebase deliberately avoids Git/mtime
guesses (determinism stance). The first instinct was to treat `§A4` as a frozen one-way door and
minimize the change (derive everything client-side, defer the date). A two-round design debate
reframed it: this is **greenfield** — the wire contract is still ours to shape and will be frozen only
once real needs are known. Round 1 untangled "should we touch the contract"; round 2 designed the
right node shape on its merits.

## Decision

**Add three fields to the tree nodes, treat them as provisional, and keep the editorial date distinct
from the eventual Git date.**

1. **`TreeFolder.description`** — from `_folder.yaml` (a new 4th key on the tiny `FolderMeta` parser);
   plaintext, trimmed, blank → null. Server-authority (no client derivation path exists).
2. **`TreeFolder.page_count`** — count of **direct** child pages only (not recursive). Server-authority:
   it survives future draft/visibility filtering, which the SPA must not re-implement. The label is
   `path/ · N pages`; a recursive count would mislead on a card whose landing shows one level.
3. **`TreePage.updated`** — the **editorial, author-declared** frontmatter `updated` value,
   **server-validated** to a fixed-width ISO `YYYY-MM-DD` (a shape gate in front of
   `kotlinx.datetime.LocalDate.parse`), normalized, with malformed/absent → null. It is **not**
   filesystem or Git last-modified.
4. **Phase-3 Git last-modified is a DISTINCT additive field** (`last_modified`), **never** a repoint of
   `updated`. Editorial intent ("the author says this was updated on X") and filesystem reality ("the
   file last changed in commit Y") are different facts; collapsing them onto one field would silently
   repaint meaning for already-shipped content (a README typo-fix must not move the author's date).
5. **The three new fields are PROVISIONAL pending Phase 3.** The "frozen `§A4`" label is
   *greenfield-provisional*, not yet a one-way door — the fields carry a provisional annotation in
   `RestDtos.kt`, and the contract is frozen only once the Git layer's needs are known.
6. **Subfolder count is NOT wired** — it is SPA-derivable from the `children` array already on the
   wire (empty subfolders are pruned server-side, so the client list is authoritative), so it costs
   nothing to compute client-side if a card ever needs it.

## Consequences

**Positive**

- Cards get real metadata now; the date has clean, deterministic semantics ("a valid ISO date or
  null"), so a malformed author value never reaches the UI as `Invalid Date`.
- No future semantic repaint: `updated` stays editorial forever; Git arrives additively as
  `last_modified`. Nothing shipped has to change meaning.
- The server stays the single authority over tree-composition facts (counts), so future filtering
  rules live in one place.

**Trade-offs**

- Editorial dates are author-maintained — often absent (→ null) and never authoritative until Git
  lands. Accepted: an explicit editorial date now beats no date, and beats a fabricated one.
- The contract is not frozen yet, so it must be revised carefully, in `§A4` lockstep (FolderMeta →
  TreeBuilder → RestDtos → `types.ts` → goldens → the typed-literal test sites).
- A new server dependency, `kotlinx-datetime`, was added (owner-approved; recorded in
  `server/dependency-allowlist.txt`). The native gate exercises `LocalDate.parse` at runtime
  (`RestApiNativeTest` round-trips a valid date and rejects an impossible one inside the image).

**Reversibility — high (greenfield).** The fields are additive and explicitly provisional. Removing a
shipped wire field later is the expensive direction, so each earns its place by being genuinely needed
by the cards rather than added speculatively (the rejected `{pages, subfolders}` counts object and a
folder-level `updated` were cut as YAGNI).

Debate outcome: round 1 collapsed the frozen-contract over-reaction; round 2 designed the field set,
types, authority line, and the editorial-vs-Git date naming. A `/codex:review` after the build added
the fixed-width shape gate (`LocalDate.parse` alone accepts expanded/signed ISO years like
`+12020-08-30`).
