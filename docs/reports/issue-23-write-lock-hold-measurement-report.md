# Issue #23 — app-DB write-lock hold duration under BEGIN IMMEDIATE

**Date:** 2026-08-01
**Scope:** Issue #23, measuring how long the app DB write lock is held by corpus-scaled transactions
after PR #22 moved the driver to `BEGIN IMMEDIATE`, and whether that change made it worse.

Measurement report. NUMBERS AND AN HONEST READING ONLY. The remedy is deliberately NOT chosen here;
the issue forbids precommitting to one and the option space is mapped at the end for a separate
owner decision.

**Raw evidence:** `issue-23-write-lock-hold-measurement-data.csv` beside this file, the complete
540-line run output. Every number below is recomputable from it. The instrument that produced it is
`server/src/test/kotlin/com/plainbase/perf/WriteLockHoldProbeTest.kt`, retained and inert (it
registers every test disabled unless a `RUN` marker is present; see its file header).

## Provenance

- Run: `run-20260801-221834Z`, preserved as the CSV beside this report.
- Base commit AS MEASURED: `37b3d528ff98e1e6ba08f6969c0518759fcda631`. This report may land on a
  later commit; the branch was rebased onto `55e2077` after the run. The intervening change is
  docs-only (a release-notes fragment), so the measured code is byte-identical to what shipped
  alongside this report.
- Probe SHA-256: `ededf37e7d29631e1f4d80a055bbde1c091fa6c6490b3ee1feb8e0dfa1f4c029`, which is the
  EXACT hash of the reviewed source file. The measured bytes and the reviewed bytes are the same
  file, with no residual difference.
- Machine: `mini.local`, Apple M4 Pro, 24 GB, JVM 21.0.11, Gradle 9.6.1. A Mac mini on fast local
  SSD, not CI and not production hardware. Sustained thermals are better than a laptop's.
- Busy budget: 3000 ms (`busy_timeout`, pinned in `DatabaseFactory`).
- JOURNAL MODE: DELETE, SQLite's default. `DatabaseFactory` sets no `journal_mode` for the app DB
  (only `SearchDb` uses WAL, for the separate search database). This matters more than any other
  environmental fact here: under WAL, readers do not block on a writer and the entire contention
  reading below would change.
- Wall clock: the whole matrix ran in 1 minute 21 seconds.

## Integrity of the run

- 453 data rows, 368 measured, 1 calibration row.
- ZERO excluded trials. Every falsifier passed on every trial.
- `registration_invocations=1` asserted at close; `uniform_cut_boundary=not_reached`.
- CAL-LONG-HOLD, the busy-observability positive control: an 8003.6 ms synthetic hold produced
  `contender_outcome=busy` with `contender_statements=0` and `overlap_verified=true`.
  THIS IS THE LOAD-BEARING CONTROL. It is what makes the zero-busy result below a real measurement
  rather than a broken instrument reporting silence.

## Hold durations

Holder shapes: H1 = `PageCheckpointRepository.replace`; H2 = `applyProofs` with a surviving batch;
H3a = `applyProofs` all-refuted with freshness reads; H3b = all-refuted, small; H4 =
witness-refuted, zero statements.

| Holder | N | trials | median (ms) | max (ms) | max as % of 3000 ms |
|---|---:|---:|---:|---:|---:|
| H1 | 1,000 | 16 | 4.29 | 4.38 | 0.15% |
| H1 | 5,000 | 16 | 19.93 | 20.32 | 0.68% |
| H1 | 20,000 | 16 | 80.86 | 82.39 | 2.75% |
| H1 | 100,000 | 16 | 406.90 | 414.57 | 13.82% |
| H2 | 1,000 | 16 | 2.69 | 2.87 | 0.10% |
| H2 | 5,000 | 16 | 10.23 | 23.92 | 0.80% |
| H2 | 20,000 | 16 | 39.68 | 40.68 | 1.36% |
| H2 | 100,000 | 16 | 198.13 | 204.93 | 6.83% |
| H3a | 1,000 | 16 | 0.97 | 1.14 | 0.04% |
| H3a | 5,000 | 16 | 4.52 | 4.90 | 0.16% |
| H3a | 20,000 | 16 | 17.09 | 19.04 | 0.63% |
| H3a | 100,000 | 16 | 82.21 | 86.35 | 2.88% |
| H3b | 1,000 | 16 | 0.12 | 0.15 | 0.00% |
| H3b | 100,000 | 16 | 0.14 | 0.15 | 0.01% |
| H4 | 1,000 | 8 | 0.07 | 1.59 | 0.05% |
| H4 | 5,000 | 8 | 0.06 | 0.08 | 0.00% |
| H4 | 20,000 | 8 | 0.09 | 1.60 | 0.05% |
| H4 | 100,000 | 8 | 0.19 | 0.32 | 0.01% |

Side table, the C2 tombstone spot-check. It runs against TWO DIFFERENT HOLDER CORES and they must be
reported separately: pooling them produces a mixture-median of a bimodal sample with no physical
meaning. The `p` column is the discriminator (`p=1` is the H2 core, `p=k` is the H3a core).

| Spot-check core | N | trials | median (ms) | max (ms) |
|---|---:|---:|---:|---:|
| H2 core | 1,000 | 4 | 2.92 | 2.95 |
| H2 core | 100,000 | 4 | 201.38 | 202.66 |
| H3a core | 1,000 | 4 | 0.86 | 0.92 |
| H3a core | 100,000 | 4 | 77.50 | 81.30 |

## The headline

**No holder came close to the 3000 ms budget at any measured corpus size.** The largest hold
anywhere is H1 at 100,000 pages: a 406.90 ms median, 414.57 ms worst case, 13.8% of the budget.
100,000 pages is already beyond a realistic deployment.

Extrapolating from the per-page cost AT THE LARGEST MEASURED N (100,000), not a fit across all four
points. The per-page cost is stable enough across the range for this to hold to an order of
magnitude (H1 costs 4.29 microseconds per page at 1,000 and 4.07 at 100,000), but these are
projections, not measurements:

| Holder | cost per 1,000 pages | reaches 3000 ms at |
|---|---:|---:|
| H1 `replace` | 4.069 ms | about 737,000 pages |
| H2 `applyProofs` surviving | 1.981 ms | about 1,514,000 pages |
| H3a `applyProofs` all-refuted | 0.822 ms | about 3,649,000 pages |

## Contention: NO VERIFIED CONTENTION at every measured cell

Zero busy outcomes across all 368 measured trials. Every cell emitted `no_verified_contention`.

This is the EXPECTED result, not a defect, and it is interpretable ONLY because the calibration
control did produce a busy at an 8-second hold. The instrument can see contention; there was none to
see. With a worst case of 415 ms against a 3000 ms budget, a contender parked at the start of a hold
never exhausts its budget, so `SQLITE_BUSY` never fires.

Stated honestly and with the limits the plan requires: busy frequency here is a SYNTHETIC
LATCH-AT-START statistic. The contender is released at the instant the holder acquires the lock.

DO NOT READ THIS AS AN UPPER BOUND ON PRODUCTION CONTENTION. An earlier draft of this report called
it one, and that was wrong in both directions. The synthetic figure can UNDERSTATE production,
because the contender pays a cold `getConnection()` before its BEGIN, which can consume part or all
of a short hold; and it can OVERSTATE production, because every synthetic arrival is deliberately
aligned to the start of a hold whereas real arrivals are not. Its relationship to production
contention is UNMODELED IN EITHER DIRECTION.

What zero busy outcomes does establish: no synthetic contender, released at the worst possible
instant, ever exhausted the 3000 ms budget. That is a statement about these holds against this
budget, not a claim that production sees no contention. A successful contender BEGIN proves nothing
about whether it waited; only a busy outcome proves contention, and none occurred outside
calibration.

## The IMMEDIATE-versus-DEFERRED question

This is what the issue actually asked: did `BEGIN IMMEDIATE` make these holds worse? The answer
DIFFERS BY HOLDER, and reading only the control pair would get it wrong.

### H1 `replace`: measured, and immaterial

| N | DEFERRED median | IMMEDIATE median | delta | within-arm spread | verdict |
|---:|---:|---:|---:|---:|---|
| 1,000 | 4.30 ms | 4.27 ms | -0.71% | 27.6% of median | UNDECIDED (spread exceeds the delta) |
| 100,000 | 409.74 ms | 406.84 ms | -0.71% | 5.8% of median | CONFIRMED IMMATERIAL |

At 100,000 pages, where the signal is large enough to resolve, the two driver arms are
indistinguishable. At 1,000 pages the holds are so short (about 4 ms) that measurement noise exceeds
any real difference, so that row is honestly UNDECIDED rather than forced to a conclusion.

CAVEAT ON THAT VERDICT, because it has no falsifier for the thing it names: nothing in the CSV
witnesses that the DEFERRED arm actually issued a DEFERRED begin. `precondition_verified` checks
only `busy_timeout`, and the probe's statement counter never sees BEGIN because it is issued through
raw JDBC beneath the counting seam. Since H1 is write-first, "the two arms are equivalent" and "both
arms effectively ran IMMEDIATE" are observationally identical here. So this result is CONSISTENT WITH
the mechanism rather than an independent confirmation of it.

This matches the mechanism: `replace` is write-first, so it took the write lock at its first
statement under DEFERRED anyway. IMMEDIATE moved the acquisition by microseconds.

### H2 `applyProofs` with a surviving batch: also write-first, also immaterial

H2 is NOT new exposure, and an earlier draft of this report wrongly said it was. Traced:
`retireBinding` (`SqlDelightRetirementRepository.kt:135-155`) performs one `selectBinding` read and
then FOUR DML statements (`retire`, `deleteBinding`, `deleteRow`, `deleteByRootId`) per cover, which
is where the `5k + 1` statement pin comes from. With one freshness read first, H2's first write is
statement 3 of 50,001 at 100,000 pages. Under DEFERRED the write lock was therefore taken almost
immediately and every subsequent read already ran inside the exclusive window. Mechanically H2 is
H1: write-first, so IMMEDIATE moves its acquisition by microseconds.

There is no pre-write read loop in H2. The reads are interleaved with the writes.

### The ZERO-DML `applyProofs` shapes: NOT measured against DEFERRED, and NOT immaterial

The control pair is H1-ONLY BY DESIGN, and H1 is a holder where IMMEDIATE provably changes nothing.
Do not generalize its verdict. The shapes where the change is real are the ones that execute NO DML
AT ALL:

- **H3a, a stale-freshness batch.** `applySurvivingProof` hits `!proofIsFresh(proof) -> emptySet()`
  (`SqlDelightRetirementRepository.kt:107`). Each proof pays one `selectObservationAndEpoch` read and
  one WARN, and NOTHING is written. This is the shape that produces the k reads and k WARNs, and it
  is the 82.21 ms holder at 100,000 pages.
- **H4, witness-refuted**, and **H3b**: also zero DML, via the `survives(witnessed) == null` and
  unavailable-root branches. Both measure well under a millisecond.

Under DEFERRED, a transaction that executes no DML NEVER escalates past a shared lock, so its
exclusive write-lock hold was **0 ms**. Under IMMEDIATE it takes the write lock at BEGIN and holds
it for the whole batch while writing nothing.

**This is exclusive-lock exposure created from nothing.** For H3a it is 0 ms to 82.21 ms at 100,000
pages. That is the finding the issue was actually worried about, and describing it as a -0.71% delta
would be wrong.

The 0 ms figure is TRACED FROM THE CODE, NOT MEASURED, and deliberately so. An H3a-DEFERRED control
arm would be actively misleading: the probe stamps `hold_ms` at `newTransaction()` return, so under
DEFERRED with no DML that stamp would measure transaction duration while NO LOCK IS HELD. The column
would read about 82 ms and mean something entirely different from the same column on every other
row. The H1-DEFERRED arm only works because H1 acquires the write lock immediately under DEFERRED
too.

In absolute terms 82 ms is still 36x under the 3000 ms budget, so this exposure is real but not
currently harmful.

## An unexpected finding: logging dominates the all-refuted hold

PRE-FIX MEASUREMENT. The emission move described in the Decision section above is expected to
collapse this differential to about zero; see that section for why it is proceeding.

The H3a WARN/QUIET side pair isolates the cost of log emission INSIDE the write-lock window:

| N | WARN enabled | WARN suppressed | logging cost | share of hold |
|---:|---:|---:|---:|---:|
| 1,000 | 0.98 ms | 0.35 ms | 0.63 ms | 64% |
| 100,000 | 81.27 ms | 27.31 ms | 53.96 ms | 66% |

Roughly two thirds of the all-refuted `applyProofs` hold is spent emitting per-proof WARN lines
while holding the exclusive write lock. This was not what the issue set out to measure and it is the
cheapest lever on the board if hold duration ever becomes a problem.

IMPORTANT QUALIFIER, since a remedy is suggested on the strength of this number: it is specific to
the current appender. `logback.xml` uses a SYNCHRONOUS `ConsoleAppender` to System.err, and this run
captured that through Gradle's stdout plumbing. An async or file appender would move this figure
materially, possibly most of the way to zero. The finding is "synchronous console logging inside the
lock window is expensive here", not "logging costs two thirds everywhere".

## REMEDY-CONSTRAINTS

The issue names three options. What the data says about each, WITHOUT choosing:

**Raise the busy timeout.** The data does not motivate this. Nothing approaches the existing 3000 ms
budget; the worst observed hold uses 13.8% of it. Raising the timeout would address a problem that
was not observed. It would only become relevant at corpus sizes around 700,000 pages.

**Chunk the transaction.** Would reduce peak hold for H1, the largest holder, at the cost of losing
single-transaction atomicity for checkpoint replacement. The data does not currently justify paying
that price, and the correctness consequences of a partially-replaced checkpoint would need their own
analysis. Reconsider if a deployment approaches several hundred thousand pages.

**Restructure the work.** The measurement surfaced a variant the issue did not anticipate: move the
per-proof log emission out of the write-lock window. It is about two thirds of the H3a hold and,
unlike chunking, costs no atomicity.

**Do nothing** is a legitimate fourth reading on the PERFORMANCE question, and the data supports it:
the margin is 7x at 100,000 pages and the extrapolated crossover sits far beyond any realistic
corpus.

## DECISION, 2026-08-01

**No remedy is taken for hold duration.** Nothing approaches the 3000 ms budget, no busy outcome was
observed at any corpus size, and the projected crossovers sit between 737,000 and 3.6 million pages.
Raising the timeout would address a problem that was not observed; chunking would trade atomicity
for margin that is not needed.

**What would reopen it:** a deployment approaching several hundred thousand pages, a move of the app
DB to WAL (which changes the entire contention picture these numbers describe), or materially slower
storage than the local SSD used here.

**One change IS proceeding, and NOT on performance grounds.** The per-proof log emission is being
moved out of these transactions because `BeginImmediateSqliteDriver.kt:53-55` already forbids
blocking IO inside an app-DB transaction, and the KDoc at `:48-51` names `applyProofs` as the site
that "can genuinely worsen" under IMMEDIATE. The logging violates an invariant the codebase had
already written down. The operative hazard is that `write(2)` to stderr is UNBOUNDED and both
shipped logback profiles are synchronous, so a stalled log consumer can hold the writer reservation
indefinitely; the 82 ms measured here is the best case of an unbounded distribution, and a 36x
margin does not protect against an unbounded syscall. That work is scoped to three transaction
blocks (`applyProofs`, `revoke`, `RootTopologyRepository.observeBinding`) and is tracked separately.

**Consequence for this report:** the H3a figures below, and the 81.27 vs 27.31 ms logging split, are
PRE-FIX measurements. They remain a true record of the code at `37b3d52`. An AFTER measurement will
be appended once the emission move lands, by re-arming the probe once and recording the collapsed
differential.

## Limits of this measurement, stated plainly

- Mac mini, M4 Pro, fast local SSD. Slower storage would scale all holds up; the shape and the
  IMMEDIATE-versus-DEFERRED conclusion should survive, the absolute crossover would move.
- JOURNAL MODE IS DELETE (SQLite's default; the app DB sets none). Under WAL, readers do not block
  on the writer and this entire contention picture changes. Every number here is a DELETE-mode
  number.
- Crossover figures are PROJECTIONS from the per-page cost at the LARGEST measured N only, not a fit
  across the four points and not measurements. They are the right order of magnitude, not precise
  thresholds.
- The logging share depends on the synchronous console appender in `logback.xml`; a different
  appender would change it substantially.
- The H1 materiality verdict is consistent-with-mechanism, not independently falsified: nothing
  observes that the DEFERRED arm actually began deferred.
- The 1,000-page materiality row is UNDECIDED and is reported as such rather than rounded to a
  conclusion.
- KNOWN INSTRUMENT LIMITATION, recorded rather than fixed: `overlap_verified` is not gated on a
  valid latch handoff, so a trial excluded as `contender:pre-hold-issue` could in principle carry
  `overlap_verified=true`. It did not affect this run: there were ZERO pre-hold-issue exclusions and
  the ONLY `overlap_verified=true` row in the entire file is the calibration row. Verified directly
  against the CSV.

## Appendix: per-cell detail

The summary table above pools the two contender arms. That pooling hides real structure, so the
full per-cell breakdown follows. Note H2 at N=5,000 with the C1 contender: median 10.24 ms and IQR
0.31 ms but a 23.92 ms max, a single outlier attributable to that arm which the pooled view erased.

Every cell: zero trials over the 3000 ms budget, zero busy outcomes, zero exclusions.

| holder | N | contender | trials | min | median | IQR | max | over budget | busy |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| H1 | 1,000 | C1 | 8 | 4.19 | 4.29 | 0.05 | 4.38 | 0 | 0 |
| H1 | 1,000 | C2 | 8 | 4.23 | 4.29 | 0.06 | 4.38 | 0 | 0 |
| H1 | 5,000 | C1 | 8 | 19.69 | 19.93 | 0.21 | 20.27 | 0 | 0 |
| H1 | 5,000 | C2 | 8 | 19.72 | 19.93 | 0.22 | 20.32 | 0 | 0 |
| H1 | 20,000 | C1 | 8 | 80.39 | 81.59 | 0.69 | 82.39 | 0 | 0 |
| H1 | 20,000 | C2 | 8 | 78.72 | 80.12 | 1.20 | 80.92 | 0 | 0 |
| H1 | 100,000 | C1 | 8 | 398.82 | 407.69 | 3.54 | 414.57 | 0 | 0 |
| H1 | 100,000 | C2 | 8 | 401.58 | 405.97 | 4.42 | 412.06 | 0 | 0 |
| H2 | 1,000 | C1 | 8 | 2.64 | 2.71 | 0.05 | 2.75 | 0 | 0 |
| H2 | 1,000 | C2 | 8 | 2.65 | 2.67 | 0.06 | 2.87 | 0 | 0 |
| H2 | 5,000 | C1 | 8 | 10.14 | 10.24 | 0.31 | 23.92 | 0 | 0 |
| H2 | 5,000 | C2 | 8 | 10.01 | 10.23 | 0.17 | 10.58 | 0 | 0 |
| H2 | 20,000 | C1 | 8 | 39.16 | 39.97 | 0.70 | 40.68 | 0 | 0 |
| H2 | 20,000 | C2 | 8 | 39.11 | 39.39 | 0.34 | 40.03 | 0 | 0 |
| H2 | 100,000 | C1 | 8 | 195.02 | 197.48 | 1.27 | 200.05 | 0 | 0 |
| H2 | 100,000 | C2 | 8 | 196.11 | 200.91 | 3.56 | 204.93 | 0 | 0 |
| H3a | 1,000 | C1 | 8 | 0.88 | 1.02 | 0.05 | 1.06 | 0 | 0 |
| H3a | 1,000 | C2 | 8 | 0.87 | 0.91 | 0.06 | 1.14 | 0 | 0 |
| H3a | 5,000 | C1 | 8 | 4.49 | 4.57 | 0.09 | 4.89 | 0 | 0 |
| H3a | 5,000 | C2 | 8 | 3.95 | 4.36 | 0.37 | 4.90 | 0 | 0 |
| H3a | 20,000 | C1 | 8 | 15.71 | 16.59 | 1.29 | 19.04 | 0 | 0 |
| H3a | 20,000 | C2 | 8 | 15.96 | 17.17 | 0.58 | 18.18 | 0 | 0 |
| H3a | 100,000 | C1 | 8 | 77.74 | 81.61 | 2.34 | 86.35 | 0 | 0 |
| H3a | 100,000 | C2 | 8 | 79.45 | 82.57 | 2.43 | 85.18 | 0 | 0 |
| H3b | 1,000 | C1 | 8 | 0.11 | 0.12 | 0.01 | 0.14 | 0 | 0 |
| H3b | 1,000 | C2 | 8 | 0.11 | 0.12 | 0.02 | 0.15 | 0 | 0 |
| H3b | 100,000 | C1 | 8 | 0.13 | 0.13 | 0.00 | 0.14 | 0 | 0 |
| H3b | 100,000 | C2 | 8 | 0.13 | 0.14 | 0.01 | 0.15 | 0 | 0 |
| H4 | 1,000 | C1 | 8 | 0.06 | 0.07 | 0.03 | 1.59 | 0 | 0 |
| H4 | 5,000 | C1 | 8 | 0.05 | 0.06 | 0.02 | 0.08 | 0 | 0 |
| H4 | 20,000 | C1 | 8 | 0.08 | 0.09 | 0.03 | 1.60 | 0 | 0 |
| H4 | 100,000 | C1 | 8 | 0.18 | 0.19 | 0.01 | 0.32 | 0 | 0 |
| H1-DEFERRED | 1,000 | none | 16 | 4.25 | 4.30 | 0.07 | 5.44 | 0 | 0 |
| H1-DEFERRED | 100,000 | none | 16 | 402.00 | 409.74 | 7.15 | 425.58 | 0 | 0 |
| H1-IMMEDIATE-CTL | 1,000 | none | 16 | 4.22 | 4.27 | 0.08 | 4.64 | 0 | 0 |
| H1-IMMEDIATE-CTL | 100,000 | none | 16 | 401.10 | 406.84 | 4.81 | 416.94 | 0 | 0 |
| H3a-WARN-CTL | 1,000 | none | 8 | 0.88 | 0.98 | 0.08 | 1.08 | 0 | 0 |
| H3a-WARN-CTL | 100,000 | none | 8 | 77.48 | 81.27 | 1.53 | 84.55 | 0 | 0 |
| H3a-QUIET | 1,000 | none | 8 | 0.34 | 0.35 | 0.01 | 0.37 | 0 | 0 |
| H3a-QUIET | 100,000 | none | 8 | 26.96 | 27.31 | 0.34 | 29.10 | 0 | 0 |
