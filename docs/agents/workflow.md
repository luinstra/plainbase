# Workflow and evidence

Read this for every task. These rules apply regardless of the coding harness. Explicit owner
instructions for the current task take precedence over defaults here.

## Start from the actual checkout

- Inspect the branch, worktree and existing changes before editing. Preserve other sessions' work;
  account for unexpected paths before staging. Clarify ownership when it affects what should ship.
- Read the relevant source, tests and committed docs. Local plans and memories help locate decisions,
  but old handoffs, PR status, test counts and tool paths are not current facts.
- Stay in the owner's chosen harness. Discover installed tools and their current instructions before
  using them; do not copy obsolete plugin paths or model rosters from memory.
- Do not change global settings, hooks or personal memory stores as a side effect of repository work.
  Promote durable project lessons into these docs; keep secrets, personal data and session history out.

## Default implementation chunk loop

1. **Tighten the plan** with Crew measure-twice. An advisor grounds an executor-ready addendum in
   current code; a second advisor reviews it until approved. Fix blocking findings and accept minor
   planning clarifications. Resolve a genuine owner choice with one concise question; do not reopen
   settled decisions. Phase plans get this review before their chunk loops.
2. **Build** with Crew build. Give the executor the plan, relevant code, bounded scope and acceptance
   checks. Tell it to stop and report when the plan conflicts with actual code. An advisor verifies the
   result. Fix validated minor findings by default; re-delegate blocking work.
3. **Verify** with the [required gates](verification.md). Full code-change verification includes the
   JVM floor; server changes also require native execution. Documentation-only changes need a
   content/link/scope review, not a code build unless they alter executable behavior.
4. **Commit only when authorized**, on the agreed branch, with conventional messages and DCO sign-off
   (`git commit -s`). Include the sign-off requirement in executor briefs. Gitignored Crew artifacts
   stay local. Push when authorized; inspect actual PR checks and post-merge `main` CI when delivery
   includes merging. Release success does not establish post-merge CI success.

For release work, follow [release preparation](../DEVELOPMENT.md#release-preparation), including
versioned notes and owner-authorized tagging.

Crew commands may appear as slash commands, skills, tools or CLI workflows depending on the host.
Use the installed integration's instructions. If a required capability is unavailable, report the
specific gap before skipping a required review or changing the execution workflow. A timeout, empty
review or idle delegate is missing evidence, never approval. Check actual process/terminal state;
elapsed time and file timestamps alone do not establish failure or completion. Do not launch a
second writer or build against the same worktree while the first still owns it.

## Escalated review

For frozen contracts, concurrency-heavy work or phase-closing chunks, recommend a pre-build Crew
design debate and post-build Crew review-until-clean. Run these on the owner's go-ahead, including
standing authorization when it applies. `/octo` is retired.

For the first review, use diverse models and at least three distinct mandates where needed, such as
security, correctness/concurrency and deployment/operations. Check that each reviewer received its
actual brief and the intended diff. Validate every finding against real code, then fix the class of
defect rather than enumerating a few instances. Re-review fixes until the remaining findings are
empty or cosmetic; do not count absent reviewers as clean votes. Include changed documentation and
reported performance/security conclusions in the review scope. A hole in the specification requires
updating the specification and its tests too.

## Make claims falsifiable

- Before saying a test protects an invariant, follow the input through the real resolver to the
  assertion, or run a focused experiment. A line citation is not a trace.
- For a regression, obtain a meaningful failing test first: the fault must change this fixture's
  input, reach the production path, and be observed by the assertion. A normal rerun passing does
  not reproduce an intermittent failure. Prefer deterministic controls and bounded observation.
- Measurements need a declared workload, complete timed work, units that match the scaling variable,
  retained evidence and a control known to fail. Report uncertainty when noise exceeds the claimed
  improvement. Keep measurement plans proportionate; do not predict an exact failure set without data.
  Keep strict local performance acceptance targets separate from looser shared-CI regression tripwires;
  see [performance and the startup gate](../operating-plainbase.md#performance--the-startup-gate).
- Review prose after refactors. Remove obsolete mechanisms, stale comments and unsupported promises;
  do not repair an unnecessary abstraction solely because it already exists.
- Report what changed, what actually ran, what passed, and what remains unverified separately.

## Protect the running installation

Use disposable state and content for tests and manual launches; see [development setup](../DEVELOPMENT.md).
Before an authorized live change, capture process/listener, version, configuration and log evidence.
Obtain explicit authorization before stopping/replacing a live server or changing its data. A healthy
endpoint alone cannot prove an intermittent indexing problem is fixed. Never terminate a process merely
because it shares a name or port; establish ownership first.
