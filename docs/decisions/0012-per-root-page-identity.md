# 12. Per-root page identity: `(RootName, PageId)`, rooted permalinks, no cross-root contest

- **Status:** Accepted
- **Date:** 2026-07-25
- **Deciders:** luinstra (owner ruling of 2026-07-17, after the C5 identity debate; record in
  `.crew/debates/run-c5-flip-debate/`, and the ruling in `.crew/plans/DECISION-per-root-identity.md`)
- **Supersedes:** ADR-0011 D2 and D17
- **Context:** The multi-root feature. C5 (the identity flip) and C6 shipped in PR #14; C7, the cleanup
  that removes what still described the old model, is the follow-up that carries this record. ADR-0011
  settled multi-root under the assumption that page ids stay a single global namespace; this replaces
  the two decisions that assumption produced.

## Context

ADR-0011 D2 and D17 assumed one flat id space across every root. Under that assumption two roots
holding the same frontmatter `id:` is a genuine CONTEST, so the design needed a winner: D2 classified
it as an identity issue to absorb and arbitrate, D17 elected the winner by registry rank.

Two things broke.

**Rank-transfer moved a durable permalink across roots.** Electing a winner by rank means the id can
travel from the root that held it to a higher-ranked one, purely because an operator reordered the
registry or added a root. A permalink is the one thing readers and agents are promised is stable, and
nothing about a config edit should re-point it at a different document.

**The winner's key-complete bind could delete a binding under an unscanned root.** The stale sweep was
keyed by id alone, so binding the winner deleted the other root's live `id_map` row on its way through
even when the pass had never scanned that root, and minted no tombstone for it. For a page whose identity
lived in `id_map` ONLY, with no `id:` in the file, that row WAS the identity: the untouched file then
looked like a page nobody had seen before on the next pass, minted a fresh id, and its permalink broke
silently. That is a durable write authorized by evidence the pass did not have, which is exactly what
the C1 absence philosophy forbids: a scan proves the pages it READ, never that unread pages are gone.

A cross-root duplicate is also not an anomaly in practice. Two checkouts of one repo, a templated page
copied between projects, or one project vendored into another all produce it as routine input, which
made "record an issue and elect a winner" the wrong shape for a normal occurrence.

## Decision

**Page identity is the pair `(RootName, PageId)`.** A `PageId` alone does not identify a page.

- The schema constraint is `UNIQUE(id, root)`; `retired_binding` tombstones are re-keyed the same way.
- `RootedPageId` is the type used wherever the pair travels as a VALUE: snapshot keys
  (`PageIndex.byRootedId`), retirement sets, permalink targets. It is deliberately NOT universal at the
  seams. Several signatures still take `root` and `id` separately, and the bare-ID read entrypoints
  (`ReadFacade.pageById`, `pageHtml`, `validateLinks`, `pageMetadata`) take a NULLABLE root on purpose,
  because their `?root=` is optional and a `RootedPageId` could not express its absence. (The bare
  permalink `/p/{id}` is a different seam again: it calls the rootless `ReadFacade.permalink`.) Do not
  read this record as forbidding a `(root, id)` parameter pair.
- Every permalink is `/p/{root}/{id}`, and that is the canonical emitted form.
- **A cross-root duplicate id is LEGAL and raises no identity issue.** Both roots keep their own page
  and each answers its own rooted permalink.
- **Registry rank decides SOURCE precedence only.** It never transfers an id between roots.
- Binds are root-scoped: a pass never supersedes a binding under a root it did not scan.
- **The within-root duplicate policy of §5.2 is UNCHANGED.** Two paths under ONE root carrying the same
  id is still a real contest, still resolved previously-bound-path-keeps-it, and still records an
  `IdentityIssue.DuplicateId`.

## Consequences

**The bare `/p/{id}` still resolves, and the two ambiguity surfaces answer differently.** This is the
detail most likely to be conflated, so it is stated once here as the record. When an id exists in more
than one root:

| Surface | Answer |
|---|---|
| bare permalink `/p/{id}` | **300 Multiple Choices**, one `Link: rel="alternate"` per candidate root plus a body of the same disambiguation URLs |
| id-addressed REST read | **409 `ambiguous_page_id`** |

The bare permalink is the pre-C5 compatibility arm, not the canonical form. It is id_map-FIRST: it
resolves the owning root from the durable index and redirects (302, never 301, because the target moves
with the page) when exactly one root holds the id.

**The `CROSS_ROOT_DUPLICATE_ID` issue kind was REMOVED, not retired.** The `IdentityIssue.Kind` enum is
otherwise append-only. Removing a member is safe here for one specific reason: per-root identity made
the variant unconstructible before any RELEASED build could write a row of that kind, so no released
data references it. Mid-branch development data is the one exception, and it is handled below. The
append-only rule stands unchanged for every kind that ever shipped.

**`identity_issue.other_root` is retained but permanently sentinel.** It is part of the table's UNIQUE
key, so dropping it means a table-rebuild migration with a schema version bump and no behavioral gain.
It stays as dead-but-cheap schema, and a test pins that no surviving issue kind ever writes a
non-sentinel value into it.

**Operators upgrading from a mid-branch development build** may hold `identity_issue` rows of the
removed kind, which the decode path would reject. There is no migration, because no released build
could produce one. The remedy, if it is ever hit, is
`DELETE FROM identity_issue WHERE kind = 'CROSS_ROOT_DUPLICATE_ID';`.

**ADR-0011's body is left intact** as the C4 historical record, with an in-place superseding note for
anyone reading D2 or D17 cold.
