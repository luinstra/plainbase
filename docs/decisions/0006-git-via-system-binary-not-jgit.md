# 6. Git history via the system `git` binary (not JGit), behind a hermetic executor

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** luinstra (after a four-model design debate: Opus, Codex/gpt-5.5, Gemini 2.5 Pro, Sonnet 4.6)
- **Context:** Phase 3 (Editing, save-to-file, Git history). Settles *how* the optional Git-history
  layer talks to Git, **before** the chunk loop. The two general-purpose spec fixes this debate
  surfaced (amend-vs-pushed-history, EOL byte-fidelity) are threaded into the master plan's Phase 3
  section independently of the A/B choice.

## Context

Phase 3 adds browser editing that saves back to the Markdown tree, with **optional** Git history
(Git mode OFF → no `.git`, editing still works). When ON, the history layer must init/detect a repo,
commit with principal attribution (human = author; later, AI agent = author + approving human =
committer), collapse rapid same-user same-file saves into one commit via a session-window amend, and
serve a per-page commit list + two-commit diff. All writes already pass through one serialized FIFO
writer, so there is no concurrent in-process Git access.

Two ways to drive Git:

- **Option A — JGit** (pure-JVM, in-process, no external `git`).
- **Option B — shell out to the system `git` binary** (subprocess per operation).

The tension is real: JGit is hermetic and needs no external git, but it is large and reflection-heavy
against the project's load-bearing **GraalVM native-image** bet (CI native gate is pass/fail). The
binary is trivially native-safe but inherits the host's git environment.

A four-model debate (blinded round 1, cross-critique round 2) opened split — 2 voted A on
hermeticity, 1 voted B, 1 (Opus) opened A-conditional. It converged **3-of-4 on B** after two
concessions that dissolved A's main advantage:

1. **JGit-in-native is unproven** — *every* participant, including the A holdout, agreed a native
   spike would be **mandatory** before A is viable. You do not gate a phase on an unproven spike when
   a native-safe option exists.
2. **The correctness delta collapsed.** A's headline argument was "hermetic JGit protects
   byte-fidelity for free." It does not: **JGit honors `.gitattributes`/`core.autocrlf` too**, so EOL
   must be pinned explicitly *either way*. Once the hardening work exists under both options, A's
   residual edge (no hooks fire, no `gpgsign` hang) is exactly what a hardened B executor closes on
   purpose — while B alone removes the native-image risk entirely.

The A holdout (Gemini) rested on "the executor will swell into an unbounded stream of correctness
bugs." The panel's rebuttal: the dangerous host-config surface is **enumerable** (`core.autocrlf`/
`core.eol`, `commit.gpgsign`, hooks, ambient `$HOME`/`GIT_CONFIG`/locale) — ~5 knobs pinned once and
frozen by a golden test, not an unbounded tail; and most of it is needed under A anyway.

## Decision

**Drive Git by shelling out to the system `git` binary, through a single hardened, hermetic
`GitExecutor`.** JGit is parked behind a (non-blocking) native-image spike.

The executor is the only place that spawns `git`, and it is hermetic by construction:

- Every invocation pins: `-c core.autocrlf=false -c core.eol=lf -c commit.gpgsign=false
  -c core.hooksPath=/dev/null`.
- Isolated environment: explicit `HOME`, `GIT_CONFIG_GLOBAL=/dev/null`,
  `GIT_CONFIG_SYSTEM=/dev/null`, fixed `LC_ALL=C`; attribution via `GIT_AUTHOR_*` / `GIT_COMMITTER_*`
  (the author/committer split, clean and explicit).
- Arguments are ALWAYS `List<String>`, NEVER shell-concatenated; `--` precedes any path (option-
  injection guard).
- Plainbase-initiated commits skip repo hooks (`--no-verify` / `hooksPath=/dev/null`) — running an
  arbitrary repo's hooks inside the server process is an RCE surface. Documented choice.
- Structured `GitResult` over exit codes + captured stderr. When Git mode is ON, a startup
  `git --version` gate-check fails fast with a clear, actionable message — including a **git >= 2.31**
  floor (the history reads pass `--diff-merges=first-parent`, valid only since git 2.31.0). An
  older-but-present git (e.g. Ubuntu 20.04 → 2.25, Debian 11 → 2.30) is rejected at the gate, not at the
  first read. The shipped Dockerfile carries a modern git, so containers are unaffected.

**Install-promise scoping:** Git OFF → the binary ships clean with zero git dependency surface. Git
ON → the user has `git` (it is their workflow); the precondition is gate-checked. "One binary, no
deps" stays true for everyone who has not opted into history.

## Consequences

**Positive**

- The native-image bet is protected: zero new reflection metadata, no large transitive graph, no
  Phase-3-gated-on-a-spike risk.
- Lean native binary and build; the git subprocess code is ~tens of lines fully owned and debuggable.
- Attribution, amend, log, and diff are native `git` behavior expressed via flags/env — exactly what
  `man git-commit` documents.

**Trade-offs**

- Git mode requires `git` **>= 2.31** present on the host (for `--diff-merges=first-parent` on the read
  path). Accepted: it is an opt-in feature whose audience runs git already; an absent or too-old git is
  gate-checked at startup, not silently broken.
- Reading `git log`/`git diff` means a stable machine format (`--format`, `-z`, plumbing) rather than
  JGit's typed objects. Bounded parsing, covered by tests.

**Reversibility — high (swappable adapter).** History lives behind a `HistoryProvider` port
(`<Tech>HistoryProvider`), so the implementation is an adapter swap with no domain or contract impact.
If hermeticity-purity or a no-git-on-host deployment ever justifies the metadata cost, a JGit native
spike can revisit Option A without a rewrite. Phase 3 is not blocked on that spike.

## Cross-cutting spec fixes (apply regardless of A/B — folded into the Phase 3 plan)

1. **Amend collapses only KNOWN-UNPUSHED commits.** The session-window amend rewrites the prior
   commit; if it was already pushed, that rewrites *published* history. The amend path must verify the
   target commit is not on an upstream before collapsing, else start a new commit. Covered by a test.
   **[SUPERSEDED for Phase 3 — see Addendum (2026-06-15): the amend is abandoned entirely; the
   unpushed check has an irreducible TOCTOU.]**
2. **Explicit EOL pinning + a byte-fidelity golden test** — commit a CRLF-containing file through the
   pipeline and assert the on-disk bytes are unchanged, guarding the hard byte-fidelity promise against
   EOL normalization under either engine. **[EXTENDED by Addendum (2026-06-15): EOL pinning is not
   sufficient — `.gitattributes` clean filters and Git LFS also rewrite blobs.]**

Debate synthesis + all rounds archived at
`~/.claude-octopus/debates/<session>/jgit-vs-git-binary/synthesis.md`.

## Addendum (2026-06-15) — Phase 3 debate hardening

A second four-model debate (Opus, Codex/gpt-5.5, Gemini 2.5 Pro, Sonnet 4.6), run while stress-testing
the Phase 3 phase plan before its build loops, amended the two cross-cutting fixes above. Both
amendments were folded into `.crew/plans/phase-3-editing-save-git-history-implementation-plan.md`
(Iteration 3); this addendum keeps the committed ADR consistent with them. The core decision —
**system `git` binary via a hermetic `GitExecutor`, not JGit** — is unchanged and reaffirmed.

### Amendment 1 — the automatic session-window amend is ABANDONED in Phase 3 (supersedes Cross-cutting fix #1)

Fix #1 above tried to make the session-window amend safe by collapsing **only known-unpushed**
commits. The debate showed that guard has an **irreducible TOCTOU**: git offers no atomic
check-and-amend, so an external `git push` (the user's own tooling, a CI runner — Plainbase is
filesystem-native and *coexists* with external git) can land between the unpushed check and
`git commit --amend`, rewriting **published** history — catastrophic and recoverable only by
force-push. The `@{upstream}` reachability check also breaks on detached HEAD, shallow clones, and
linked worktrees, all realistic states.

**Decision:** Phase 3 commits **one new commit per save**, always (no automatic amend). This also
*simplifies* the build — the amend logic, the unpushed-reachability query, and the bare-remote test
leave the Git chunk's scope. If commit-collapsing is ever wanted, it returns as a **separate, explicit
squash/cleanup command** (never automatic save behavior), and even then only on a branch with **no
configured upstream** plus fail-loud post-rewrite divergence detection. A verbose-but-truthful,
append-only history beats a "clean" but corruptible one.

### Amendment 2 — byte-fidelity must survive `.gitattributes` clean filters and Git LFS (extends Cross-cutting fix #2)

Fix #2 pinned `core.autocrlf=false`/`core.eol=lf` to stop EOL mangling. That is **necessary but not
sufficient**: a repo's `.gitattributes` **clean filters** or **Git LFS** also transform blobs, so
porcelain `git add` can commit bytes that differ from the file on disk (`git show HEAD:path` ≠ the
saved bytes) — the hard byte-fidelity promise broken even with EOL pinned.

**Decision:** the byte-fidelity guarantee is asserted on the **committed blob**, not just the working
tree — a golden test commits a CRLF file **and** a file under a hostile `.gitattributes`/LFS rule and
asserts `git show HEAD:path` equals the on-disk bytes. If porcelain `git add` cannot guarantee this,
the `GitExecutor` commits via **plumbing** (`git hash-object --no-filters -w` + `git update-index
--cacheinfo`), bypassing filters entirely. The hermetic-executor principle is unchanged; its
byte-fidelity scope widens from "EOL" to "any filter/attribute/LFS transform."

Phase 3 debate synthesis + all rounds archived at
`~/.claude-octopus/debates/<session>/phase-3-plan/synthesis.md`.
