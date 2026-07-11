# 11. Multi-root document directories: composite (root, path) keys, reserved main, per-root editability/history

- **Status:** Accepted (frozen at C2 merge)
- **Date:** 2026-07-11
- **Deciders:** luinstra (after the 6-seat multi-root design debate of 2026-07-11: codex, agy,
  cursor-auto, opus, sonnet, fable; record in `.crew/debates/20260711-004023-multi-root-design/`,
  synthesis.md + per-seat outputs)
- **Context:** The multi-root feature (chunks C1-C5). One running server serves documents from N
  directories ("roots"), added cheaply (each local project's `.crew` dir, say) without one process
  per directory; in cloud deployments the same mechanism doubles as workspaces. A forward
  constraint: inline comments (a future per-root WRITE surface stored app-side) mean roots are
  never hard view-only, so policy must distinguish write classes. This ADR settles the contested
  decisions the debate resolved; C1 lands the domain types, config parsing, validation matrix, and
  the single-root-wired registry seam with behavior byte-identical for every config that boots today.

## Context

Plainbase assumed exactly one docs root per process: `CONTENT_DIR` (or the object-mode bucket) is
the authority, and every path-bearing surface (index, search, aliases, checkpoints) keys on a bare
root-relative path. Serving N directories forces answers to questions that would otherwise be
re-litigated per chunk: what namespace do URLs get, what happens when two roots hold the same
frontmatter id, when may Plainbase commit into a repo it does not own, and what does a root
"missing" mean at runtime. The debate settled six of these; the C1 build bound seven more
operationalizations to the code as built. Approach A carries the data side: ONE PageIndex /
search.db / id_map keyed by composite (root, path), per-root adapter stacks behind an immutable
`RootRegistry` (domain types in `server/src/main/kotlin/com/plainbase/domain/root/`).

The config grammar is a TOP-LEVEL `roots {}` block in `DATA_DIR/plainbase.conf` (HOCON, ADR-0009;
the design doc's `plainbase.roots` prose maps here - the real grammar has no `plainbase.` prefix,
matching `auth {}` / `storage {}` / `git {}`):

```hocon
roots {
  main    { path = "/home/me/docs" }                    # editable=true, history=auto by default
  memoria { path = "/home/me/dev/memoria/.crew" }       # editable=false, history=off by default
  notes   { path = "/home/me/notes", editable = true }
}
```

Roots are file-only in v1 (no env grammar); the C5 CLI edits a machine-managed include, and
restart applies topology changes.

## Decision

### D1 - `main` is a reserved, REQUIRED root name

Config validation requires a root named `main` (the primary); the C5 CLI refuses to remove or
rename it. Why: the C2 migration stamps every existing row `root = 'main'`; reserving the name
makes those stamps safe forever, with no "what if the operator renames main" reconciliation logic
anywhere. (Debate synthesis #2.)

### D2 - cross-root duplicate frontmatter ids are an identity issue, never a silent supersede

Two checkouts of one repo, or templated `.crew` pages, make cross-root duplicate ids ROUTINE input,
not corruption. The C2 rules, landing atomically in the id_map migration: `unbindStale` becomes
key-complete (`WHERE id = :id AND (root, path) != (:root, :path)`); `bind()`'s supersede scope is
WITHIN-ROOT only; a cross-root duplicate id mints a new identity_issue variant (its natural key
gains root) with a deterministic winner - the root earlier in declaration order (see D7 for what
"declaration order" precisely means) - and the loser stays reachable by path; never a silent
cross-root supersede or a UNIQUE(id) crash. Detached rows still hold ids under UNIQUE(id), and a
live bind supersedes a detached row, so "re-add restores permalinks" is conditional. (Synthesis #3.)

### D3 - URLs are `/docs/{root}/{path}` always, with a query-preserving 301 for legacy paths

No sigil grammars, no conditional shapes: an unknown first segment under `/docs/` 301s to
`/docs/main/{seg}/...`, preserving query strings; the legacy hop may chain into an alias hop (two
301s, accepted). `/p/{id}` permalinks are untouched. Two recorded consequences: (a) the
deterministic collision - a legacy corpus whose main root has a top-level directory literally
named `main` - is detected at migration/boot and fails loud with remediation (C3 implements with
the redirect grammar); (b) the residual SHADOW edge: `root add`'s refusal of a name shadowing an
existing top-level entry of main is best-effort at add time, and main's tree can mutate at runtime
afterward - a runtime shadow makes the legacy-redirect for that one segment resolve to the root,
not main's directory, until the operator renames one side. Accepted tradeoff for an unconditional
URL rule. (Synthesis, URL grammar resolution; C3/C5 implement.)

### D4 - strict git guard for explicit `history = native`; legacy main keeps Auto, grandfathered

`HistoryMode` is `off | auto | native`. Extra roots default `off`: Plainbase never commits into a
repo it does not own, and `git init` (ensureRepo) runs ONLY for main under Auto, exactly as today.
Explicit `native` gets a strict fail-closed C4 guard, all four checks via GitExecutor's cleared-env
chokepoint: `.git` is a real directory; `rev-parse --show-toplevel` equals the root path (both
sides toRealPath); `--show-superproject-working-tree` is empty; git-dir == git-common-dir.
Violations are loud boot errors naming the failed check. Legacy/synthesized main keeps `auto` -
today's laxer detection including the deliberate .git-as-file worktree acceptance
(`HistoryModule.kt`, gitEnabled) - so existing worktree deployments are grandfathered, never
broken by an upgrade. (Synthesis #4; C1 stores the mode, C4 enforces.)

### D5 - an unavailable root is 503, sticky, and runs NO deletion pipelines

A known root whose path is missing/unreadable serves 503 (+ Retry-After), never 404: agents treat
404 as page-gone and drop citations. A MID-RUN disappearance flips the root Unavailable and runs no
index purge, no search deletes, no alias/identity churn - "root gone" is never "all pages deleted";
durable state is untouched. Unavailable is sticky until restart in v1; the health endpoint lists
per-root status. `/p/{id}` for a page in an unavailable root falls back to id_map (which persists
and knows the root) to answer 503 not 404. (Synthesis #5; C4 implements.)

### D6 - policy checks `(principal, writeClass, RootedResource)`; `editable` gates page mutation only

`writeClass` is a sealed set seeded with today's surfaces (PageEdit, PageCreate, AssetWrite);
future comments add a class without reshaping the signature. A root's `editable` flag gates the
page-mutation classes only - every root stays browsable/searchable (and later commentable).
Proposal-vs-direct-commit stays out of writeClass (the HOW axis; agentDirectCommitGlobs owns it and
gains root scoping). Deny reasons distinguish root-not-editable from policy-denied. Per-root READ
ACLs are explicitly out of v1. (Synthesis #7; C4 implements.)

### v1 boundaries (C1 operationalizations, bound to the code as built)

- **D7 - "declaration order" is origin line number WITH NAME TIEBREAK.** Typesafe Config's
  `ConfigObject` is map-backed and does not preserve insertion order, but every value carries
  `origin().lineNumber()`; parsing sorts entries by `(line, name)`. This is NOT verbatim
  declaration order in two edge cases: two roots declared on one line order alphabetically, and
  line numbers reset per file across includes (relevant once C5's machine-managed include exists),
  where the tiebreak again decides. Both are deterministic, which is all D2's winner semantics
  need - but the contract is "origin line number with name tiebreak", never raw declaration order.
- **D8 - the registry consumes DECLARED paths; toRealPath is validation-only.** Validation
  canonicalizes with `toRealPath()` for its equality/nesting comparisons, then discards the result;
  `Root.backend.path` keeps the `Path.of(raw).toAbsolutePath().normalize()` form - exactly the
  transform `contentDir` gets today. Feeding consumers a symlink-resolved path would change
  behavior on symlinked deployments (macOS `/tmp`).
- **D9 - the strict matrix applies to EXPLICIT blocks only.** An explicit `roots {}` rejects
  root == DATA_DIR, roots nested in DATA_DIR, duplicate and nested roots (toRealPath comparisons);
  DATA_DIR strictly inside a root stays legal (it feeds that root's watcher exclusion in C4). A
  config WITHOUT a roots block synthesizes `roots.main` from contentDir/storage with today's
  defaults (editable, history auto) and runs today's exact startup guard verbatim - including its
  legal strict-nesting-either-way stance. Same grandfathering pattern as D4.
- **D10 - explicit `roots {}` + `storage.backend=object` is a boot error in v1.** Object mode's
  authority is the bucket and CONTENT_DIR is ignored; a roots block cannot describe that main yet.
  Object deployments keep the legacy config shape (their synthesized main carries an Object backend
  descriptor, shape-only in v1).
- **D11 - explicit `roots {}` + an explicitly set CONTENT_DIR: the roots block wins, a WARN names
  the ignored key** (the existing object-mode ignored-CONTENT_DIR precedent; env-always-wins is a
  same-key invariant and these are different keys).
- **D12 - extras parsed by a single-root build are validated but NOT served; a WARN says so** (no
  silent discrepancy between config and serving surface).
- **D13 - a missing/unreadable EXTRA root path is a WARN, not a boot error** (extras degrade to
  Unavailable; C1 logs, C4 adds the runtime status). A missing MAIN path stays fatal. An
  unavailable extra still PARTICIPATES in every duplicate/nesting/DATA_DIR comparison via its
  best-effort canonical form (the deepest EXISTING ancestor toRealPath'd, remaining components
  appended); only whole-path canonicalization is skipped. The same fallback covers a
  not-yet-created DATA_DIR on first boot, so an existing symlinked ancestor still resolves there
  too. Accepted residual blind spot: an alias the best-effort walk cannot see (inside the
  non-existing tail, or behind an I/O failure during ancestor resolution, which drops to the
  plain normalized form) still escapes the matrix - intentional, and C4's availability work
  inherits the limit.

### v1 boundaries (C2 operationalizations, bound to the code as built)

- **D14 - root columns are TEXT root names, never surrogate integers.** The value is the validated
  `RootName` slug, typed `TEXT AS RootName` in the id_map/dirty_page/page_checkpoint families
  (decode fails loudly on a corrupt row, the TreePath adapter posture) and plain TEXT where a
  sentinel or deferral demands it (`identity_issue.other_root`, `proposals.root`). Why: no join
  table for C4's per-root operations (`DELETE ... WHERE root = ?` works on all six tables),
  human-inspectable in the sqlite3 CLI alongside the hex(id) convention, and names are stable
  identifiers because `main` is reserved (D1) and rename is a recorded scope cut whose mechanism
  is the transactional UPDATE noted there. Availability seams shipped with the schema:
  `id_map.selectDistinctRoots` (the boot guard now, C4's health surface later) and
  `section_doc.root` on every search hit row.
- **D15 - the 100%-detached boot guard is FATAL; partial detachment WARNs.** A nonempty id_map
  whose roots are entirely disjoint from the configured names means every permalink in the
  DATA_DIR is orphaned - almost certainly a wrong DATA_DIR or a wholesale-rewritten roots block -
  so serve() refuses (fail-closed) with remediation that is config-first and then TARGETED and
  backup-first: per-root DELETEs on the five identity tables, NEVER "delete plainbase.db" (the
  app DB is also the security and review truth: users, sessions, API tokens, roles, proposals,
  the audit log). Partial detachment logs the dormant-permalink WARN and serves.
- **D16 - a partial-visibility pass never lets a LOSING page steal across roots.** Ownership
  classification (the shared `BindingVisibility` rule): a binding under a SCANNED root is live iff
  its path was scanned; a binding under an UNSCANNED-but-CONFIGURED root is ALWAYS a live owner,
  so the D17 rank contest still happens; a binding under a root absent from the registry is
  detached and supersedable (D2). Two outcomes exist (UNIQUE(id) admits no both-survive state):
  the pass's page LOSES to the unscanned owner (reassigned, issue recorded in-pass, foreign row
  untouched - the protection), or it OUTRANKS the owner and legitimately WINS - its key-complete
  bind necessarily deletes the foreign row, and the PASS records the loser-behalf issue AT
  SUPERSESSION TIME with the loser's exact natural key (the next full rebuild's own record dedups
  via the UNIQUE upsert), so the delete is never a silent, time-shifted supersede. NO rank-0-main
  assumption anywhere: registry order can seat an extra ahead of main, so both outcomes are
  reachable from the main-only CLIs.
- **D17 - cross-root winner mechanics: registry rank beats previously-bound; within-root §5.2 is
  untouched.** When two LIVE paths in different roots carry the same frontmatter id, the root
  earlier in D7 order wins regardless of which path held the id_map binding ("previously-bound
  keeps it" remains the rule only WITHIN a root). The loser keeps its own prior binding or mints
  fresh, with one GUARD: it reuses its mapped id ONLY when that differs from the contested id,
  else it MINTS FRESH - a loser that was itself the prior owner (two checkouts of one repo) would
  otherwise read its own stale binding back and either key-complete the winner's row away or
  crash the snapshot's byId uniqueness check. The mint is rescan-stable from the next pass on.
  Two execution invariants keep the scheme sound: binds land INLINE per draft during resolution
  (never batched afterward), and ALL sources are scanned before the FIRST resolve.
- **D18 - proposals root lands as schema + DEFAULT stamp only; domain threading is C4's.** The
  `proposals` table gains `root TEXT NOT NULL DEFAULT 'main'`; queries, port, and domain types
  stay root-blind (every C2/C3 proposal IS main-scoped - proposals ride the main-wired write
  surface). The DEFAULT is the semantic stamp; C4 threads real roots when write targets gain
  them. dirty_page and page_checkpoint are, by contrast, threaded now: the N-root IndexBuilder
  consumes the checkpoint directly and the write pipeline binds identity, so their ports cannot
  stay root-blind without hardcoding.

Per-root `editable` and `history` are parsed, validated, and recorded but deliberately DORMANT in
this release (intentional C1 state; a startup warning names any non-default value) - C4 wires
their enforcement together with multi-root serving.

Known and unchanged in C1 (pre-existing CLI behavior): `adopt`/`reindex` construct their
`LocalContentStore` without DATA_DIR exclusions, so with an explicit block that legally nests
DATA_DIR inside main they can walk app state as content - revisit with C4's per-root
watcher-exclusion work.

### Recorded scope cuts (v1)

- Object-backend EXTRA roots: schema/config shaped for it; validation rejects it (and D10 rejects
  whole-block object combos).
- No root RENAME verb (safe: main is reserved, extras are disposable; rename = remove+add). A
  later verb is ONE transactional UPDATE across the six root-stamped tables (id_map, url_alias,
  identity_issue - root AND other_root - page_checkpoint, dirty_page, proposals) plus a search.db
  schema-version bump; D14's TEXT-name columns are what keep it that simple.
- No detached-row GC; the boot WARN is the visibility.
- Per-root partition rebuild: follow-up gated on C5's N-root perf measurement (full-corpus rebuild
  has measured headroom to 30-40k pages against a 1k contract).
- No per-root read ACLs; the policy signature is not final for reads.
- No runtime root-mutation API: restart-to-apply is the contract.

## Consequences

**Positive**

- One data plane (single PageIndex/search.db/id_map, composite keys) instead of N adapter stacks
  with cross-cutting search/dedup seams; the registry is an immutable boot-time snapshot, so no
  topology concurrency exists at runtime.
- Legacy configs are untouched by construction: no roots block means synthesized main + today's
  guard verbatim, so v1 upgrades are risk-free for every existing deployment.
- The reserved `main` + always-rooted URL rule kill whole classes of future reconciliation logic
  (migration stamps, redirect grammar, winner determinism).

**Trade-offs**

- The URL shape changes for every existing link (`/docs/{path}` -> 301). Accepted for an
  unconditional grammar; permalinks (`/p/{id}`) never move.
- The D3 residual runtime-shadow edge exists by design (documented above).
- Cross-root duplicate-id winners depend on config order (D7); reordering roots in the file can
  flip a winner. Deterministic and operator-visible, but an operator-owned sharp edge.
- The C2 migration is one-way (old binary vs migrated app DB = restore from backup - the
  documented downgrade story).

**Reversibility - moderate.** C1 alone is fully reversible (a seam plus dormant types; behavior
identical). From C2 on, the composite-key migration is the point of no return for downgrades
(backup-restore only); the URL grammar (C3) is reversible in code but not in the wild once links
circulate.
