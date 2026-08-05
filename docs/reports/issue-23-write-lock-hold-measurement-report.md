# Issue #23 — app-DB write-lock hold duration under BEGIN IMMEDIATE

**Date:** 2026-08-01, corrected 2026-08-04 (see the CORRECTION below)
**Scope:** Issue #23, measuring how long the app DB write lock is held by corpus-scaled transactions
after PR #22 moved the driver to `BEGIN IMMEDIATE`, and whether that change made it worse.

Measurement report. NUMBERS AND AN HONEST READING ONLY. The remedy is deliberately NOT chosen here;
the issue forbids precommitting to one and the option space is mapped at the end for a separate
owner decision.

> **CORRECTION, 2026-08-04. The original headline was wrong and is retracted here, not quietly
> edited.** The 2026-08-01 matrix fixed every proof batch at `k = n / 10` covers but published a
> crossover table indexed by PAGES. `applyProofs` takes a flat proof list, and
> `ObservationEpoch.proofFromScan` (`ObservationEpoch.kt:216`) computes `gone` from the root's ENTIRE
> durable binding set, so a whole-tree delete plus one scan legally mints a batch covering the whole
> corpus. Cost tracks COVERS, not pages, so the page-indexed crossover was about 10x too optimistic.
> A post-fix re-measurement of the `k = n` shape produced real `SQLITE_BUSY` from the ordinary
> `idMap.bind` writer. **"Zero busy outcomes at any corpus size" is FALSIFIED**, and the margin the
> DECISION rested on is gone at about 150,000 covers. See "Post-fix re-measurement" below; the
> corrected decision is at the end. Every pre-fix number in this report is left exactly as measured.

**Raw evidence:** `issue-23-write-lock-hold-measurement-data.csv` beside this file, the complete
540-line run output for the 2026-08-01 matrix. Every PRE-FIX number below is recomputable from it;
the post-fix numbers have their own provenance and their own limits, stated in their section. The
instrument that produced both is `server/src/test/kotlin/com/plainbase/perf/WriteLockHoldProbeTest.kt`,
retained and inert. `@Ignored` (`WriteLockHoldProbeTest.kt:118`) is the disable: Kotest never
instantiates the spec, so nothing registers, runs, or touches the filesystem during an ordinary
build. Reviving it takes BOTH acts, deliberately - delete the annotation AND arm the
`.crew/perf/issue-23/RUN` marker. The marker alone does nothing; see the file header at `:77-105`.

## Provenance of the 2026-08-01 matrix

- Run: `run-20260801-221834Z`, preserved as the CSV beside this report.
- Base commit AS MEASURED: `37b3d528ff98e1e6ba08f6969c0518759fcda631`. This report may land on a
  later commit; the branch was rebased onto `55e2077` after the run, and that intervening change is
  docs-only (a release-notes fragment), so the measured code was byte-identical to the tree this
  report shipped on. It has diverged since: the change that moved log emission out of these
  transactions edits the very repositories the H-shapes measured. See the DECISION section below.
- Probe SHA-256: `ededf37e7d29631e1f4d80a055bbde1c091fa6c6490b3ee1feb8e0dfa1f4c029`, which identifies the
  probe blob AS MEASURED: an uncommitted snapshot taken alongside base commit `37b3d52`, which carries no
  probe file. The probe was first committed, already disabled, at `73f2e87`, and the tree file has diverged
  from the measured bytes twice since - at `73f2e87`, and again in the tree as it stands. The hash records which bytes
  were measured; it is not a checksum of the current file.
- Machine: `mini.local`, Apple M4 Pro, 24 GB, JVM 21.0.11, Gradle 9.6.1. A Mac mini on fast local
  SSD, not CI and not production hardware. Sustained thermals are better than a laptop's.
- Busy budget: 3000 ms (`busy_timeout`, pinned in `DatabaseFactory`).
- JOURNAL MODE: DELETE, SQLite's default. `DatabaseFactory` sets no `journal_mode` for the app DB
  (only `SearchDb` uses WAL, for the separate search database). This matters more than any other
  environmental fact here: under WAL, readers do not block on a writer and the entire contention
  reading below would change.
- Wall clock: the whole matrix ran in 1 minute 21 seconds.

## Integrity of the 2026-08-01 run

- 453 data rows, 368 measured, 1 calibration row.
- ZERO excluded trials. Every falsifier passed on every trial.
- `registration_invocations=1` asserted at close; `uniform_cut_boundary=not_reached`.
- CAL-LONG-HOLD, the busy-observability positive control: an 8003.6 ms synthetic hold produced
  `contender_outcome=busy` with `contender_statements=0` and `overlap_verified=true`.
  THIS IS THE LOAD-BEARING CONTROL. It is what makes this matrix's zero-busy result a real
  measurement rather than a broken instrument reporting silence.

## Hold durations (2026-08-01, every batch at `k = n / 10` covers)

Holder shapes: H1 = `PageCheckpointRepository.replace`; H2 = `applyProofs` with a surviving batch;
H3a = `applyProofs` all-refuted with freshness reads; H3b = all-refuted, small; H4 =
witness-refuted, zero statements.

THE `N` COLUMN IS CORPUS SIZE, NOT BATCH SIZE. Every proof batch in this table covers `n / 10`
bindings, and the `applyProofs` shapes cost per COVER, not per page. A table row therefore says what
a tenth-of-the-corpus batch costs, and nothing about a larger one.

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

## The headline, as originally written and now SCOPED

**No holder came close to the 3000 ms budget in the shapes this matrix measured.** The largest hold
anywhere in it is H1 at 100,000 pages: a 406.90 ms median, 414.57 ms worst case, 13.8% of the budget.

RETRACTED SCOPE: the original sentence said "at any measured corpus size", and the table below read
as a page-indexed bound. Both are wrong for H2/H3a, which are covers-indexed. Every proof batch in
this matrix carried `k = n / 10` covers, so no row here says anything about a batch that covers a
whole root. The k = n shape does reach the budget, at about 150,000 covers. See "Post-fix
re-measurement".

Extrapolating from the per-page cost AT THE LARGEST MEASURED N (100,000), not a fit across all four
points. The per-page cost is stable enough across the range for this to hold to an order of
magnitude (H1 costs 4.29 microseconds per page at 1,000 and 4.07 at 100,000), but these are
projections, not measurements. AS PUBLISHED 2026-08-01, unchanged:

| Holder | cost per 1,000 pages | reaches 3000 ms at |
|---|---:|---:|
| H1 `replace` | 4.069 ms | about 737,000 pages |
| H2 `applyProofs` surviving | 1.981 ms | about 1,514,000 pages |
| H3a `applyProofs` all-refuted | 0.822 ms | about 3,649,000 pages |

**THE UNITS ON THE LAST TWO ROWS ARE WRONG, and that is the whole error.** H1 does scale with pages
in the checkpoint. The two `applyProofs` rows scale with COVERS IN THE BATCH, and every batch in this
matrix carried `n / 10` covers, so "per 1,000 pages" is really "per 100 covers". Restating the same
two cells in the unit that governs them, arithmetic only, no new measurement:

| Holder | as published | restated in COVERS |
|---|---|---|
| H2 `applyProofs` surviving | 1.981 ms per 1,000 pages, 3000 ms at 1,514,000 pages | 19.81 ms per 1,000 covers, 3000 ms at 151,400 covers |
| H3a `applyProofs` all-refuted | 0.822 ms per 1,000 pages, 3000 ms at 3,649,000 pages | 8.22 ms per 1,000 covers, 3000 ms at 364,900 covers |

The restated H2 figure, 151,400 covers, is the one to compare against reality: the post-fix ladder
brackets the measured over-budget crossover to (140,000, 150,000] covers. The projection was accurate
in magnitude all along. Only its units were wrong, and the units are what made 1.5 million look
unreachable.

## Contention: NO VERIFIED CONTENTION at every cell OF THIS MATRIX

Zero busy outcomes across all 368 measured trials OF THE `k = n / 10` MATRIX. Every cell emitted
`no_verified_contention`. **This is not a corpus-size result and the original section title implied
it was.** At `k = n` the same contender does take `SQLITE_BUSY`: 24 measured busy rows at k=180,000
and k=200,000, in the post-fix section below.

This is the EXPECTED result for these cells, not a defect, and it is interpretable ONLY because the
calibration control did produce a busy at an 8-second hold. The instrument can see contention; in
these cells there was none to see. With a worst case of 415 ms against a 3000 ms budget, a contender
parked at the start of a hold does not exhaust its budget, so `SQLITE_BUSY` does not fire HERE. It
fires once the hold itself passes the budget, which needs a batch this matrix never issued.

Stated honestly and with the limits the plan requires: busy frequency here is a SYNTHETIC
LATCH-AT-START statistic. The contender is released at the instant the holder acquires the lock.

DO NOT READ THIS AS AN UPPER BOUND ON PRODUCTION CONTENTION. An earlier draft of this report called
it one, and that was wrong in both directions. The synthetic figure can UNDERSTATE production,
because the contender pays a cold `getConnection()` before its BEGIN, which can consume part or all
of a short hold; and it can OVERSTATE production, because every synthetic arrival is deliberately
aligned to the start of a hold whereas real arrivals are not. Its relationship to production
contention is UNMODELED IN EITHER DIRECTION.

What zero busy outcomes does establish: no synthetic contender, released at the worst possible
instant, exhausted the 3000 ms budget AGAINST A `k = n / 10` HOLD. That is a statement about these
holds against this budget, not a claim that production sees no contention, and NOT a statement about
the largest batch the code can legally produce. A successful contender BEGIN proves nothing about
whether it waited; only a busy outcome proves contention, and in THIS matrix none occurred outside
calibration.

The read-only side of the contender coverage is PARTIAL, and that is a limit of both matrices.
`SqlDelightIdMapRepository.bind` has two refusal paths: the tombstone refusal
(`SqlDelightIdMapRepository.kt:100-103`) and the live-incumbent refusal (`:104-107`). The C2
spot-check exercises the tombstone one only. The live-incumbent refusal was never measured, in
either run.

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

In absolute terms 82 ms is still 36x under the 3000 ms budget. That margin belongs to THIS CELL, an
H3a batch of 10,000 covers, and it does not generalize to the batch size the code permits: H3a is
covers-linear too, so the same margin shrinks as the batch grows. The exposure is real; the "not
currently harmful" reading holds only for batches of this size.

## An unexpected finding: logging dominates the all-refuted hold

PRE-FIX MEASUREMENT. No AFTER measurement is taken: the emission move has landed, and the H3a WARN/QUIET
side pair that produced this differential measured in-lock emission, so it was retired rather than re-armed
against a window that no longer holds any.

The H3a WARN/QUIET side pair isolated the cost of log emission INSIDE the write-lock window:

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

## Post-fix re-measurement, 2026-08-04: the ladder in COVERS

**DIFFERENT CODE. These numbers are not comparable cell-for-cell with anything above.** Everything
above ran at `37b3d52`, before per-proof log emission moved out of the transaction. Everything in
this section ran at `d11307c`, after it. Nothing here is edited into a pre-fix table and no pre-fix
row was recomputed. Where the two are put side by side, the sentence says so.

The one honest overlap, PER CONTENDER ARM so the two sides are the same statistic: the pre-fix H2
cell at n=100,000 / k=10,000 measured 197.48 ms (C1) and 200.91 ms (C2); the post-fix cell at the
same n and k measured 200.45 ms (C1) and 200.27 ms (C2). ACROSS DIFFERENT CODE, so this is a sanity
check that the shape did not move, not a before/after delta.

### Provenance and its limit

- Four run directories under `.crew/perf/issue-23/`, all at `base_commit=d11307c`, each carrying its
  own `results.csv` and a `probe-snapshot.kt` of the as-run bytes. SHA-256 of that snapshot, verified
  from the run directories:

  | run | probe snapshot SHA-256 | cells |
  |---|---|---|
  | `run-20260804-184228Z` | `59edc250f317b7ef3903dc483e5f66aa23e16a884d117afaa95a2337a2396396` | k=n at 1,000 and 200,000 |
  | `run-20260804-184531Z` | `33c4cb535e6d8b98561550119b1fecc8db56ebd6e2440579f0d82da1f313e410` | the k=n/10 ladder, k=n to 200,000, C2 spot-check |
  | `run-20260804-184840Z` | `8f6cc7103119077f907f57026057f40ccb704ffbfececb2e4d6975809d6faae5` | k=n at 140,000 and 160,000 |
  | `run-20260804-185117Z` | `10385a6017ff74416e19284b6113ce1775007d4b8a9d0356b5b8ef0250ffd76c` | k=n at 150,000 and 180,000 |

- The four runs are preserved beside this report as `issue-23-postfix-covers-184228Z.csv`,
  `-184531Z.csv`, `-184840Z.csv` and `-185117Z.csv`, one per run directory above (the timestamp is
  the run id's), copied out of gitignored `.crew/`, so every post-fix number here is recomputable the
  same way the 2026-08-01 matrix is. The hashes identify the probe blob AS RUN; they are not
  checksums of any committed file, and the as-run probe bytes themselves do NOT ship (the tree's
  probe hashes to none of the four, since `ACTIVE_N_SET` and `ACTIVE_HOLDERS` were edited between
  runs).
- Machine: `mini.local`, Apple M4 Pro, 24 GB, macOS 26.5.2, JVM 21.0.11, Gradle 9.6.1. Same host,
  same DELETE journal mode, same 3000 ms `busy_timeout` as the pre-fix matrix.
- AGGREGATION: measured-phase rows only, warmup excluded. VERIFIED to be the same convention the
  2026-08-01 tables use: re-aggregating the CSV beside this report over measured rows alone
  reproduces its published cells exactly (H1 1,000 C1 = 4.29, H2 100,000 C1 = 197.48, H3a 100,000
  C2 = 82.57, H3a-QUIET 100,000 = 27.31). Trial counts are printed per row so the pooling is visible.

### Instrument soundness, so the zeros still mean something

- CAL-LONG-HOLD fired in ALL FOUR runs: holds of 8020.6, 8022.6, 8026.3 and 8028.5 ms, every one
  producing `contender_outcome=busy` with `contender_statements=0` and `overlap_verified=true`.
- 250 non-calibration rows (230 on the H2/H2-full core, 20 on the C2 refused spot-check). ZERO with
  `completeness_verified=false`, ZERO with `precondition_verified=false`, ZERO with any
  `excluded_reason`.

### Cost tracks COVERS, and this is measured rather than modelled

| shape | n | k | trials | median (ms) |
|---|---:|---:|---:|---:|
| H2-full | 20,000 | 20,000 | 8 | 398.00 |
| H2 | 200,000 | 20,000 | 16 | 398.74 |

Same k, TEN TIMES the corpus, 0.19% apart. Corpus size is not the variable; batch covers is.

Per-1,000-covers cost, flat across a 200x range in k, from k=1,000 up (the table below carries the
k=100 and k=500 rows too, and the paragraph after it says what each of those spans):

| shape | n | k | trials | median (ms) | ms per 1,000 covers |
|---|---:|---:|---:|---:|---:|
| H2 | 1,000 | 100 | 16 | 2.77 | 27.72 |
| H2 | 5,000 | 500 | 16 | 10.34 | 20.68 |
| H2 | 20,000 | 2,000 | 16 | 40.22 | 20.11 |
| H2 | 100,000 | 10,000 | 16 | 200.27 | 20.03 |
| H2 | 200,000 | 20,000 | 16 | 398.74 | 19.94 |
| H2-full | 200,000 | 200,000 | 16 | 4022.76 | 20.11 |

Across every cell from k=500 to k=200,000, a 400x range, the figure sits between 19.4 and 20.7 ms per
1,000 covers (from k=1,000 upward, 19.4 to 20.3; the 19.4 is the k=5,000 rung of the ladder below).
The k=100 cell reads 27.7 because a fixed per-transaction cost of roughly a millisecond is still a
third of a 2.8 ms hold; it is not degradation. No slope-and-intercept fit is published here, because
none was reproduced from these rows.

### The k = n ladder, the shape a whole-tree delete legally mints

C1 is the write contender, `SqlDelightIdMapRepository.bind`. Measured rows only.

| k = n | trials | min | median (ms) | IQR | max | over 3000 ms | busy |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1,000 | 32 | 19.49 | 20.12 | 0.65 | 23.01 | 0/32 | 0/32 |
| 5,000 | 8 | 96.33 | 97.28 | 1.40 | 98.45 | 0/8 | 0/8 |
| 20,000 | 8 | 389.39 | 398.00 | 4.06 | 400.50 | 0/8 | 0/8 |
| 100,000 | 8 | 1965.10 | 1983.96 | 12.48 | 2017.68 | 0/8 | 0/8 |
| 140,000 | 8 | 2784.90 | 2807.83 | 12.55 | 2871.03 | 0/8 | 0/8 |
| 150,000 | 8 | 3009.08 | 3038.23 | 21.78 | 3065.35 | **8/8** | 0/8 |
| 160,000 | 8 | 3191.33 | 3199.20 | 6.00 | 3222.44 | **8/8** | 0/8 |
| 180,000 | 8 | 3594.78 | 3621.32 | 26.80 | 3680.29 | **8/8** | **8/8** |
| 200,000 | 16 | 3983.12 | 4022.76 | 52.36 | 4090.10 | **16/16** | **16/16** |

The k=1,000 and k=200,000 rows pool two or four runs; their IQRs carry a run-to-run offset the
single-run rows do not (within either k=200,000 run the IQR is 6.62 and 9.02 ms).

Three findings:

1. **The over-budget crossover brackets to (140,000, 150,000] covers.** Measured, not projected. The
   report's original 1,514,000 figure was right in magnitude and wrong in units.
2. **Real `SQLITE_BUSY` from the ordinary `idMap.bind` writer.** 24 measured busy rows (30 counting
   warmup) across three run directories: k=180,000 in `run-...185117Z`, and k=200,000 REPRODUCED
   INDEPENDENTLY in `run-...184228Z` and `run-...184531Z`. Every one is a legal workload, not a
   synthetic stall. **The pre-fix "zero busy outcomes at any corpus size" is falsified for this
   shape.**
3. **The busy handler gives up at 3202 to 3296 ms (3307 counting warmup), not at the nominal 3000.**
   So the busy threshold sits about 10% above the over-budget one, and k=160,000 is a RACE rather
   than a safe cell: its own contender WAITS (3195.8 to 3265.9 ms, all returning `bound`) sit inside
   the give-up band, which is the like-for-like comparison since the band is a wait statistic. One
   trial waited 3263.5 ms and got `bound`; another waited 3202.2 ms and got `busy`. That band is a property
   of this host and this SQLite build, so the exact 160,000 verdict is MACHINE-DEPENDENT and should
   not be read as a threshold.

### A second output change in the emission move, declared

The change that moved log emission out of these transactions was described as carrying ONE deliberate
text change. It carries TWO. `DeferredProofLog.stale` (`SqlDelightRetirementRepository.kt:357-360`)
DROPS a staleness record whose current tokens are both null, so a message that used to be emitted is
no longer emitted at all. It is an intended ruling, not a regression: a root with no observation row
renders `null/null`, which tells an operator nothing actionable, and dropping it at accumulation
stops it taking a budget slot from a record that would have printed. `DeferredProofLogEmissionTest:135`
pins the behaviour. The sibling `advanceStale` (`:385-392`) deliberately does NOT drop it, because for
a git advance the missing-row case is the only record that a range was discarded.

## KNOWN BOUND: `applyProofs` batch size is unbounded, and hold time is linear in it

`applyProofs` places NO limit on how many covers one batch may carry, and hold time is linear in
covers at about 20 ms per 1,000. A sufficiently large single batch therefore exceeds the busy budget
and makes other app-DB writers fail with `SQLITE_BUSY`. Measured: over budget at 150,000 covers and
above, observed busy at 180,000. `IndexBuilder.kt:428` hands the whole proof list over in one call,
and `ObservationEpoch.proofFromScan` (`ObservationEpoch.kt:216`) computes `gone` over the root's
entire durable binding set, so the batch is bounded only by the corpus.

**NOT being fixed now, deliberately.** Two grounds carry the decision, in this order:

- **Partial application.** Chunking would give callers a half-applied pass where today they get
  all-or-nothing, and nothing in the caller or the schema is written to survive that.
- **The corpus floor.** A batch covers only durable bindings, so 150,000 covers needs a root holding
  at least 150,000 bindings, and this project does not expect corpora at that scale. This is the
  ground that makes the bound unreachable in practice, and it is a statement about corpus SIZE, not
  about how fast a delete happens: covers accumulate between reconciliations (the git path computes
  them over `deletedIn(oldHead, postHead)`, the whole range since the checkpoint last advanced), so a
  qualifying batch does not need the deletions to arrive together.

And the LETTER of two invariants the code documents at the site would have to be renegotiated. Both
are preservable in spirit, so neither is treated as a blocker here:

- `SqlDelightRetirementRepository.kt:52-58`: ONE `unavailableNow()` read serves every proof and
  advance in the pass, so they all judge standing against the same instant. Per-chunk reads would be
  strictly FRESHER, which is the fail-closed direction `IndexBuilder.kt:423-427` already documents
  for standing.
- `SqlDelightRetirementRepository.kt:64-67`: the git checkpoint advance lands in the SAME transaction
  as the reaps it rides with, with "no window between the reap and the move". Advancing in the last
  chunk leaves only a reap-without-move crash window, which the next pass re-examines and
  `retireBinding`'s binding-gone no-op (`SqlDelightRetirementRepository.kt:136-137`) absorbs.

## REMEDY-CONSTRAINTS

Written 2026-08-01 against the `k = n / 10` matrix. Kept because the three options and their costs
are still the right map, with the claims the post-fix ladder falsified marked inline.

The issue names three options. What the data says about each, WITHOUT choosing:

**Raise the busy timeout.** The `k = n / 10` data does not motivate this: no cell in it approaches
the existing 3000 ms budget and the worst observed hold uses 13.8% of it. CORRECTED SCOPE: the
original sentence went on to say this would only become relevant "at corpus sizes around 700,000
pages". At `k = n` it is relevant from about 150,000 covers, and raising the timeout does not stop
the hold, it only lengthens how long every other writer waits behind it. What that trade buys is not
quantified here: the harm it would avert is a THROWN `SQLITE_BUSY` out of `idMap.bind` (an expected
path per `BeginImmediateSqliteDriver.kt:135`), whose only production caller is the rebuild's
identity-assignment loop at `IndexBuilder.kt:1455`, and what that throw costs the pass and its
operator was NOT characterized by this measurement.

**Chunk the transaction.** Would reduce peak hold for H1, at the cost of losing single-transaction
atomicity for checkpoint replacement. CORRECTED SCOPE: the original "reconsider if a deployment
approaches several hundred thousand pages" was a PAGE threshold; for `applyProofs` the threshold is
150,000 COVERS IN ONE BATCH. Chunking `applyProofs` specifically carries the further costs the KNOWN
BOUND section above names - partial application, plus the letter of two documented invariants - and
it is not taken.

**Restructure the work.** The measurement surfaced a variant the issue did not anticipate: move the
per-proof log emission out of the write-lock window. It is about two thirds of the H3a hold and,
unlike chunking, costs no atomicity. THIS ONE LANDED; see the DECISION below.

**Do nothing** was recorded here as a legitimate fourth reading on the PERFORMANCE question, on the
grounds that "the margin is 7x at 100,000 pages and the extrapolated crossover sits far beyond any
realistic corpus". FALSIFIED. The 7x margin is a property of the `k = n / 10` shape only, and the
crossover is at 150,000 covers, which the code can legally produce. Doing nothing is still the
decision, but for a different reason, stated below.

## DECISION, 2026-08-01, CORRECTED 2026-08-04

The original text read: "No remedy is taken for hold duration. Nothing approaches the 3000 ms budget,
no busy outcome was observed at any corpus size, and the projected crossovers sit between 737,000 and
3.6 million pages." **Both premises in that sentence are false.** Busy outcomes DO occur, from the
ordinary `idMap.bind` writer, at 180,000 covers and above (k=160,000 is a RACE on this host, not a
safe cell; see the ladder's third finding); and the `applyProofs` crossover is at about 150,000
covers, not 1.5 million pages.

**The decision is unchanged and the reason is replaced.** No remedy is taken for hold duration, NOT
because there is margin - at 150,000 covers and above there is none - but because reaching that point
takes a single batch covering 150,000 durable BINDINGS, so a root would have to hold at least that
many and lose them all between two of its scans (or across one git checkpoint range). Corpora at that
scale are beyond any deployment this project expects, and that corpus floor, not any margin in the
hold, is what makes the bound unreachable. Note what the floor does NOT rest on: covers accumulate
across reconciliations, so the deletions need not arrive together. The remaining ground is the cost
of the remedies, unchanged: raising the timeout only lengthens the wait, it does not shorten the
hold, and chunking `applyProofs` would introduce partial application where callers get all-or-nothing
today, and would renegotiate the letter of the same-instant standing read and the same-transaction
git advance. Recorded as a KNOWN BOUND above rather than as an absence of one.

**What would reopen it:** a single delete-then-scan pass covering on the order of 100,000 bindings (a
COVERS threshold, not a corpus one - a large corpus with ordinary churn does not approach it), a move
of the app DB to WAL (which changes the entire contention picture these numbers describe), or
materially slower storage than the local SSD used here. Read the covers trigger as a scale question
rather than a catastrophe one: once a corpus IS that large, a qualifying batch is a fraction of it,
not a wipe. In a two-million-page tree 100,000 covers is 5%, which a directory restructure or a
branch switch can produce.

**One change LANDED WITH THIS REPORT, and NOT on performance grounds.** The per-proof log emission
was moved out of these transactions because `BeginImmediateSqliteDriver.kt:53-55` already forbids
blocking IO inside an app-DB transaction, and the KDoc at `:48-51` names `applyProofs` as the site
that "can genuinely worsen" under IMMEDIATE. The logging violates an invariant the codebase had
already written down. The operative hazard is that `write(2)` to stderr is UNBOUNDED and both
shipped logback profiles are synchronous, so a stalled log consumer can hold the writer reservation
indefinitely; the 82 ms measured here is the best case of an unbounded distribution, and a 36x
margin does not protect against an unbounded syscall. That work was scoped to three transaction
blocks (`applyProofs`, `revoke`, `RootTopologyRepository.observeBinding`).

**Consequence for this report:** the H3a figures above, and the 81.27 vs 27.31 ms logging split, are
PRE-FIX measurements. They remain a true record of the code at `37b3d52`.

## Limits of this measurement, stated plainly

- Mac mini, M4 Pro, fast local SSD. Slower storage would scale all holds up; the shape and the
  IMMEDIATE-versus-DEFERRED conclusion should survive, the absolute crossover would move.
- JOURNAL MODE IS DELETE (SQLite's default; the app DB sets none). Under WAL, readers do not block
  on the writer and this entire contention picture changes. Every number here is a DELETE-mode
  number.
- The PRE-FIX crossover figures are PROJECTIONS from the per-unit cost at the LARGEST measured N
  only, not a fit across the four points and not measurements. They are the right order of magnitude,
  not precise thresholds - and for the two `applyProofs` rows their UNITS were published wrong, as
  pages rather than covers. The post-fix ladder replaces the H2 projection with a measured bracket.
- Every pre-fix cell fixed the proof batch at `k = n / 10` covers. No pre-fix row constrains the
  behaviour of a batch that covers a whole root, which is exactly what the corrected sections address.
- The post-fix runs ship beside this report as `issue-23-postfix-covers-184228Z.csv`,
  `-184531Z.csv`, `-184840Z.csv` and `-185117Z.csv`, so both matrices are recomputable from this
  tree. The as-run probe bytes do not ship: the instrument was edited between runs, so the tree's
  copy hashes to none of the four recorded values.
- Contender coverage is PARTIAL on the read-only side: the C2 spot-check exercises only the tombstone
  refusal in `SqlDelightIdMapRepository.bind` (`:100-103`). The live-incumbent refusal (`:104-107`)
  was never measured, in either matrix.
- The 3202 to 3296 ms busy give-up band is a property of this host and this SQLite build. The verdict
  that k=160,000 is a race rather than a safe cell is machine-dependent and does not transfer.
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

## Appendix: per-cell detail (PRE-FIX matrix, `k = n / 10`)

The summary table above pools the two contender arms. That pooling hides real structure, so the
full per-cell breakdown follows. Note H2 at N=5,000 with the C1 contender: median 10.24 ms and IQR
0.31 ms but a 23.92 ms max, a single outlier attributable to that arm which the pooled view erased.

Every cell OF THIS MATRIX: zero trials over the 3000 ms budget, zero busy outcomes, zero exclusions.
Every proof batch here carries `k = n / 10` covers; the k = n ladder that does exceed the budget is
in the post-fix section, not here.

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
