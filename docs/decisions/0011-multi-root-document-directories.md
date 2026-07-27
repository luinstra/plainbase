# 11. Multi-root document directories: composite (root, path) keys, reserved main, per-root editability/history

- **Status:** Accepted, SUPERSEDED IN PART twice - by
  [ADR-0012](0012-per-root-page-identity.md) on page identity, and by the URL-grammar change recorded
  in the second note below on D3 (see both notes directly below). D1-D18 were frozen at C4 merge; the
  later C5 chunk re-opened D2 and D17, and the URL-grammar work re-opened D3.
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

## Superseded in part: per-root page identity (2026-07-24)

**This ADR records what was decided at C4. It is not a description of current behavior.** The C5
chunk reversed two of its decisions. Everything below this note is left exactly as it was frozen, so
the original reasoning stays legible; where the frozen text and this note disagree, this note wins.

- **`UNIQUE(id)` became `UNIQUE(id, root)`.** Page identity is now the pair `(RootName, PageId)`. A
  cross-root duplicate id is therefore no longer an identity ISSUE to be contested (D2) and there is
  no cross-root winner to elect (D17): both roots simply keep their own page. Read every `UNIQUE(id)`
  and every cross-root contest rule below as the C4 design, not as today's.
- **Permalinks are rooted, `/p/{root}/{id}`.** Every statement below that a permalink is bare, or
  that `/p/{id}` is untouched, describes the C4 world. The bare form still resolves, as the pre-C5
  regression arm. The two ambiguity surfaces answer differently and are easy to conflate: when the id
  exists in more than one root, the bare PERMALINK answers **300 Multiple Choices** with a candidate
  list (`PermalinkRoute.kt:156`), while an id-addressed REST read answers **409 `ambiguous_page_id`**
  (`RouteSupport.kt:412`).

Authority: the C5 commits on `multi-root` (`d934220` plus its four follow-ups, PR #14). The
replacement decision record is [ADR-0012](0012-per-root-page-identity.md); read it for current
behavior, and this note only as the in-place warning on D2 and D17.

## Superseded in part: the URL grammar has no rootless fallback (2026-07-26)

**D3's legacy 301 is gone, and so is every piece of machinery that existed to make it safe.** A URL whose
first segment does not name a REGISTERED root addresses nothing and is answered as missing: 404 with the
SPA shell body on `/docs`, 404 on `/assets` and `/browse`, `page_not_found` on `by-path`. The one
exception is the SPA's own embedded bundle, which `/assets` serves from the root-BLIND bundle-wins check
that precedes the root parse (C1a item 1) - `RootUrlGrammarTest` pins that half alongside the 404s. It is
never reinterpreted as a path under the primary. Read every "legacy tail", "two-hop chain" and
"`/docs/{path}` -> 301" statement below as the C3 design, not as today's. Alias redirects are untouched:
a moved page, or one carrying `redirect_from`, still 301s WITHIN its root, one hop.

Three of D3's recorded consequences go with it, because each existed only to contain the fallback:

- **D3(a) and the reserved-`main` boot refusal are deleted.** A top-level `main` entry in the primary
  root was ambiguous only against a rootless `/docs/main/...` link. With no rootless URLs it serves at
  `/docs/main/main/...`, and nothing refuses, warns, or needs renaming.
- **D3(b)'s residual runtime shadow no longer exists, and neither does D-C5-6's split of the shadow check
  between `root add` and boot** (the `--force` override, the boot WARN, and the shared `topLevelIndex`
  both were computed from). Registering a root cannot change what a ROOT-QUALIFIED URL resolves to, so
  there is nothing left to detect: each root's URL space is its own.
- **What replaces them is a reservation on root NAMES, checked at REGISTRATION** - the product's own
  top-level segments, the `pb-`/`plainbase-` prefixes and the `v[0-9]+` shape, over a tightened name
  grammar (`[a-z][a-z0-9]*(-[a-z0-9]+)*`, 2-32 chars). It refuses operator CONFIG, never author content,
  which is why it can fail closed without bricking a restart.

D3 existed to keep circulating links working across the upgrade. Plainbase is pre-1.0 with no install
base, so there is nothing to keep working and the trade costs nothing.

Authority: the URL-grammar commits on `url-grammar-top-level-roots` (`ab7b025` through `992b2de`). The
routing half is pinned by `RootUrlGrammarTest`, `RestRedirectTest` and
`frontend/src/__tests__/folder-landing.test.tsx`; the name-reservation half by `RootNameTest`,
`ReservedSegmentsTest`, `RootCommandTest` and `FrontendBundleTest`.

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
key-complete (`WHERE id = :id AND (root, path) != (:root, :path)`), so the SQL itself is root-agnostic
by design - it must be, or UNIQUE(id) would crash on the very duplicates this decision exists to
absorb. The supersede SCOPE is therefore a CALLER policy, not a SQL one: a stale same-root row or a
DETACHED row is simply swept, while a LIVE cross-root owner is superseded only as the deterministic
D17 rank-contest outcome, and only when the pass actually scanned that owner's root (D16). A cross-root
duplicate id mints a new identity_issue variant (its natural key gains root) with a deterministic
winner - the root earlier in declaration order (see D7 for what "declaration order" precisely means) -
and the loser stays reachable by path; never a silent cross-root supersede (the supersession records
the loser-behalf issue in-pass, D16) or a UNIQUE(id) crash. Detached rows still hold ids under
UNIQUE(id), and a live bind supersedes a detached row, so "re-add restores permalinks" is conditional.
(Synthesis #3.)

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

**The DETECTION bound, stated exactly** (it is what makes "never serve stale" an invariant rather than a
hope). Nothing serves stale bytes once a root is MARKED, so the whole guarantee reduces to how long a lost
root can go unmarked. Every *operation-driven* detector - a write's probe, a rebuild's probe, the read
facade's exit classifier - is triggered by traffic somebody generated, and therefore says NOTHING about an
idle root: with no writes and no rescan, none of them ever runs. A root loss also does not reliably raise a
watch event (a rename or an unmount touches no child; on Linux the JDK does not even invalidate the key),
so "the watcher will see the deletes" is not a bound either. Both together would leave an idle root serving
carried-forward content as `available: true` **indefinitely**, which is not a lag - it is the invariant
broken.

So each root's watcher POLLS its own root on a fixed interval (`FileWatcher.LIVENESS_INTERVAL`, 5 s) and
treats loss of the root's own watch key as the same condition. On loss it marks (VANISHED - the operator's
remedy follows the *cause*, and the cause is a gone disk, not a dead thread) and schedules the converging
pass. Every AVAILABLE root has a watcher (the boot loop skips only roots that are already marked), so the
bound holds corpus-wide: **an available root's loss is detected within the liveness interval, with or
without traffic.** The operation-driven detectors remain as the faster path for a root that IS being used.

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
- **D12 (HISTORICAL, C1-C3) - extras parsed by a single-root build are validated but NOT served; a
  WARN says so** (no silent discrepancy between config and serving surface). **Superseded by C4**,
  which wires every registered root as an index source and serves it; the WARN is gone with it.
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
  backup-first: per-root DELETEs on the six root-bearing tables (D14), NEVER "delete plainbase.db" (the
  app DB is also the security and review truth: users, sessions, API tokens, roles, proposals,
  the audit log). Partial detachment logs the dormant-permalink WARN and serves.
- **D16 - a partial-visibility pass never takes an id from a root it could not look at.** Ownership
  classification (the shared `BindingVisibility` rule) answers two SEPARATE questions. *Is the binding
  a live owner* (does it enter the duplicate contest at all)? A binding under a SCANNED root is live
  iff its path was scanned; a binding under an UNSCANNED-but-CONFIGURED root is ALWAYS live (the pass
  cannot see that root's disk, so treating its rows as detached is exactly the silent cross-root steal
  this rule closes); a binding under a root absent from the registry is detached and not an owner at
  all (D2). *May the pass SUPERSEDE it* - knowing the winner's key-complete bind DELETES the owner's
  row? **ONLY when the pass actually SCANNED the owner's root.** Rank decides a contest between two
  roots that both showed up; it cannot decide one for a root that is not there. So against an
  unscanned owner there is exactly ONE outcome, and rank does not enter into it: the SCANNED claimant
  REASSIGNS (issue recorded in-pass, foreign row untouched), and the D17 rank contest waits for a pass
  that can see both roots. A pass has no authority to destroy durable identity state for a root it
  could not look at - it cannot know the root still holds the page, the root's section is carried
  forward verbatim (so winning would ALSO put a duplicate id in the snapshot, i.e. a rebuild crash),
  and an outage must never silently cost a page its permalink (D-C4-10). NO rank-0-main assumption
  anywhere: this holds however main and an extra rank. The rule's subject is the REBUILD, which skips
  an unavailable root and carries its section: partial visibility is the OUTAGE shape. `adopt` is NOT
  a partial-visibility pass - it plans over every configured root in one go and refuses to run if it
  cannot see one (see D19), so the unscanned-but-configured arm is structurally empty there.
- **D17 - cross-root winner mechanics: registry rank beats previously-bound; within-root §5.2 is
  untouched.** When two LIVE paths in different roots carry the same frontmatter id, the root
  earlier in D7 order wins regardless of which path held the id_map binding ("previously-bound
  keeps it" remains the rule only WITHIN a root). The loser keeps its own prior binding or mints
  fresh, with one GUARD: it reuses its mapped id ONLY when that differs from the contested id,
  else it MINTS FRESH - a loser that was itself the prior owner (two checkouts of one repo) would
  otherwise read its own stale binding back and either key-complete the winner's row away or
  crash the snapshot's byId uniqueness check. The mint is rescan-stable from the next pass on.
  A loser with NO frontmatter id loses the contest just as hard: its `id_map` row is contested by
  the same `ownerOf` seam, so a taken id is reassigned there too, and identity resolution never
  depends on a side effect of the previous page's bind. One execution invariant keeps the scheme
  sound: ALL sources are scanned before the FIRST resolve. (`IndexBuilder` additionally binds
  INLINE per draft, which is why a superseded row is already gone by the time its owner re-resolves;
  `adopt` binds NOTHING until its whole plan is resolved, which is what makes the plan abortable -
  and is sound for exactly the reason above.)
- **D18 - proposals root lands as schema + DEFAULT stamp only; domain threading is C4's.** The
  `proposals` table gains `root TEXT NOT NULL DEFAULT 'main'`; queries, port, and domain types
  stay root-blind (every C2/C3 proposal IS main-scoped - proposals ride the main-wired write
  surface). The DEFAULT is the semantic stamp; C4 threads real roots when write targets gain
  them. dirty_page and page_checkpoint are, by contrast, threaded now: the N-root IndexBuilder
  consumes the checkpoint directly and the write pipeline binds identity, so their ports cannot
  stay root-blind without hardcoding.
- **D19 - `adopt` resolves the WHOLE corpus in one read-only plan, then writes it.** Adopting root by
  root, sequentially, is not sound, and in two ways that are really one. Every root the loop had not
  REACHED yet looked *unscanned* to D16 - hence untouchable - so a rank winner could not take the id
  it outranks: it reassigned, and `--write-ids` then materialized ids into files that rank says belong
  to another page, DURABLY. And a root that vanished mid-loop escaped after earlier roots had already
  been mutated, leaving a half-adopted corpus - the state an operator would never think to re-check.
  So: phase 1 scans every configured root and resolves one global duplicate contest, writing NOTHING
  (rank decides, because both sides turned up); phase 2 re-probes every root and materializes exactly
  that plan, aborting rather than half-applying if one has gone. The dry run RENDERS the same plan
  object - the same patched bytes - that the write phase executes, so a preview cannot disagree with
  the write it previews. `adopt` still refuses outright if a configured root is missing before it
  starts (the `reindex` rule): a root it skipped is a root whose ids stay in `DATA_DIR` alone.

Per-root `editable` and `history` were parsed, validated, and recorded but DORMANT through C1-C3.
**C4 enforces both**: `editable = false` denies every page-write class at the `PolicyService` gate
(403 `root_not_editable`, on REST and MCP alike), and `history` selects each root's provider -
`off` records nothing, `native` claims an existing repository under a strict boot guard (it never
`git init`s, and refuses to start on a linked worktree, a submodule, or somebody else's checkout),
`auto` (main only) keeps the legacy detect-or-override behavior.

Known and unchanged through C1-C3 (pre-existing CLI behavior), **closed in C4**: `adopt`/`reindex`
used to construct their `LocalContentStore` without DATA_DIR exclusions, so with an explicit block
that legally nests DATA_DIR inside main they could walk app state as content. Both CLIs now pass the
same `exclusions = listOf(config.dataDir)` the server's store has always carried. `adopt` was also
MAIN-ONLY, which made its `--write-ids` DR promise ("every page's identity now lives in the tree, so
a lost DATA_DIR cannot cost it") false for every extra root - their ids stayed in `DATA_DIR` alone,
and losing it would have cost them every permalink and citation. It now covers every configured root
in ONE plan (D19) and refuses to run at all if it cannot see one of them, the `reindex` rule.

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

## C5 implementation notes

D1-D18 above are frozen. C5 built the `plainbase root add/remove/list` CLI against them; the notes
below record how three of those decisions were operationalized, and one place where the ADR's own
prose has to be corrected against what was actually built. None of this reopens a D1-D18 decision.

**D-C5-1 - the two-file mechanism, not the "machine-managed include" this ADR's context section
describes.** The context section above (and `multi-root-design.md`) call the CLI's write path "a
machine-managed include." That was never built, and C5 deliberately rejected it: the CLI instead
writes its own second file, `DATA_DIR/roots.conf`, which the loader parses alongside
`plainbase.conf` and merges. `plainbase.conf` is never opened for writing by any CLI verb - not a
best-effort round-trip, an absence of code, so every hand-written comment, key order and value
survives by construction. An `include` line would have required editing the operator's file (the
exact thing this ADR says never to do), and in HOCON the include's **position** decides who wins a
key conflict - a line the operator could move at any time. A second file with one fixed merge rule
has no such dependency.

**D-C5-4 - the cross-file `(line, name)` tiebreak this ADR's own D7 aside sketched (above,
"line numbers reset per file across includes ... where the tiebreak again decides") is REJECTED.**
Taken literally, it merges both files' entries and sorts by `(line, name)` globally - which lets a
CLI-added root at `roots.conf` line 4 outrank a hand-declared incumbent at `plainbase.conf` line 8,
taking over its permalinks in a cross-root duplicate-id contest (D2/D17). It is also unstable: every
`root add` rewrites `roots.conf` and shifts the line numbers of the roots already in it, re-ranking
roots the operator never touched. Both violate **Invariant R**: adding or removing a root in
`roots.conf` never changes the relative rank of any other root, a newly added root always ranks
last (so it always loses a duplicate-id contest against an incumbent), and a hand-declared root
always outranks every CLI-added one. The rule that replaces the sketch: sort each file's `roots {}`
block independently by `(line, name)` - D7 unchanged, applied per file, never compared across
files - then concatenate whole blocks in fixed file order (`plainbase.conf`'s block, then
`roots.conf`'s extras). **`main` is never hoisted to rank 0** - it keeps whatever rank its own
declaration gave it, exactly like every other root in `plainbase.conf`'s block.

**D-C5-6 - the shadow check's split between CLI and boot, and what each half can and cannot see.**
`root add` REFUSES a name that shadows an existing top-level entry of main - a page, folder, asset,
or a URL a `slug:`/`_folder.yaml slug:` mints - computed from a plain filesystem scan of main
(`--force` overrides). Boot **never refuses** on a shadow, only **WARNs**, computed from the built
index snapshot plus the main-root alias registry. The split exists because the two sides can see
different things: the CLI opens no database, so it structurally cannot see a `redirect_from` alias
row (those live in `url_alias`, not on disk, and outlive the frontmatter that minted them) - that is
the one case the boot WARN backstops. Neither side can see a folder created through Plainbase's own
UI after the `add` ran; that residual edge is this ADR's D3 accepted tradeoff, not a gap C5 left
open.

**D3(a) correction: the reserved-`main` collision check now reads the raw content-path space too,
not only the URL space.** D3 above describes the reserved-`main` collision as a URL-grammar
question (a legacy corpus whose main root has a top-level directory literally named `main`). The
shared `topLevelIndex` helper C5 introduced for the shadow check (D-C5-6) is keyed on **two**
grammars at once - the slugified `/docs` + alias URL space, and the raw (NFC, never slugified)
`/browse` + `/assets` content-path space - because a root name can shadow a segment in either. Both
the reserved-`main` collision detector and the new `root add`/boot shadow checks share this one
index, so the reserved-`main` refusal is precise against content paths as well as URLs, not just the
URL grammar D3's prose describes.
