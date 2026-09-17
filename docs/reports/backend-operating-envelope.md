# Backend operating envelope: corrected backend screening

Recorded 2026-09-16, with follow-up trials on 2026-09-17, on `codex/backend-05-measured-performance`. The corrected pair is the authoritative result for this bounded backend screen's retained measured snapshot. This is a bounded JVM screening result, not a production capacity guarantee. The characterization source passed the full build and native gates; later trial verification is distinguished below.

## Corrected workload

The opt-in `:server:performanceScreen` task runs one fresh worker per run at 250, 1,000, and 3,000 seeded pages. Each page is exactly 4,096 UTF-8 bytes and has two headings and eight deterministic links. The worker uses the real file-backed app database, `SearchDb`, FTS5 indexing, publication listener, guarded link validation, guarded proposal listing, and direct `WritePipeline` save/create calls. History and filesystem watching are disabled for this screen.

The schedule is five warmups plus 20 measurements for LINK, LIST, and SAVE, and two warmups plus five measurements for CREATE. Timers cover only the synchronous public operation. Fixture setup, correctness checks, persistence checks, publication checks, search checks, recording, and cleanup are outside the timed region. The Gradle test task timeout is 10 minutes and each size has a 2-minute deadline.

Before timing, each size asserts the absolute proposal total, status counts, operation counts, and distinct target counts. The corrected proposal totals are 100, 500, and 1,000 at 250, 1,000, and 3,000 pages. Each total has an 80/20 EDIT/CREATE split and status proportions Applied 20%, Rejected 20%, Failed 20%, Pending 30%, and Conflicted 10%. EDIT targets are exactly `pages/page-0000.md` through `pages/page-0009.md`; CREATE targets are distinct generated `proposal-target` pages. The LINK oracle is rooted at `docs/pages/page-0000.md`, and every expected broken-link observation must report that same source page.

## Corrected results

Medians are milliseconds. The ratio is the larger run median divided by the smaller run median. The 200 ms threshold applies to LINK, LIST, and SAVE; the 1,000 ms threshold applies to CREATE. The 250-page rows are the control workload.

| Size | Operation | screen-corrected-01 median | screen-corrected-02 median | Ratio | Screening result |
| ---: | :--- | ---: | ---: | ---: | :--- |
| 250 | LINK | 0.574188 | 0.628438 | 1.0945 | control retained |
| 250 | LIST | 2.175209 | 2.207979 | 1.0151 | control retained |
| 250 | SAVE | 5.424500 | 5.265292 | 1.0302 | control retained |
| 250 | CREATE | 402.426250 | 404.823084 | 1.0060 | control retained |
| 1,000 | LINK | 0.892438 | 0.918667 | 1.0294 | below threshold; retained |
| 1,000 | LIST | 8.791167 | 9.453062 | 1.0753 | below threshold; retained |
| 1,000 | SAVE | 5.095917 | 5.142792 | 1.0092 | below threshold; retained |
| 1,000 | CREATE | 1,438.825625 | 1,466.173041 | 1.0190 | qualifies provisionally |
| 3,000 | LINK | 2.165625 | 2.035459 | 1.0640 | below threshold; retained |
| 3,000 | LIST | 15.266417 | 19.100376 | 1.2511 | below threshold; repeatability inconclusive; retained |
| 3,000 | SAVE | 5.094875 | 5.233458 | 1.0272 | below threshold; retained |
| 3,000 | CREATE | 4,270.601167 | 4,176.507792 | 1.0225 | qualifies provisionally |

Each corrected worker completed all 246 scheduled observations successfully: 25 LINK, 25 LIST, 25 SAVE, and 7 CREATE observations per size. Each status stream has 500 records and ends with `run_complete`. The largest recorded pre-timing fixture was 86,696,232 bytes at 3,000 pages. Corrected raw evidence files total 171,654 and 171,664 bytes; filesystem allocation was approximately 176 KiB per directory.

CREATE is the sole provisional nomination. The predeclared ranking selects CREATE at 3,000 pages as the single representative because it has the highest qualifying minimum median; the 1,000-page result is supporting evidence. LINK and SAVE remain retained below threshold. LIST remains retained below threshold, but its 3,000-page repeatability is inconclusive/noisy because its ratio exceeds 1.20. A production change requires a separate focused review. This screening stage made no optimization; the bounded CREATE attribution that followed is recorded below.

The corrected raw evidence is retained here:

- [screen-corrected-01 manifest](data/backend-performance/screen-corrected-01/manifest.json), [observations](data/backend-performance/screen-corrected-01/observations.csv), [status stream](data/backend-performance/screen-corrected-01/status.jsonl), [tracked patch](data/backend-performance/screen-corrected-01/tracked.patch), [probe source snapshot](data/backend-performance/screen-corrected-01/BackendPerformanceScreenTest.kt)
- [screen-corrected-02 manifest](data/backend-performance/screen-corrected-02/manifest.json), [observations](data/backend-performance/screen-corrected-02/observations.csv), [status stream](data/backend-performance/screen-corrected-02/status.jsonl), [tracked patch](data/backend-performance/screen-corrected-02/tracked.patch), [probe source snapshot](data/backend-performance/screen-corrected-02/BackendPerformanceScreenTest.kt)

Both manifests record the actual Gradle worker/runtime identity and launch parameters. They use `java_version=25.0.4.1`, `java_runtime=25.0.4.1+1-jvmci-25.3-b22`, `os=Mac OS X 27.0`, `cpu=aarch64;processors=12`, `heap=max=1073741824;configured=1g`, `gradle_start_parameters=gradle=9.7.1;tasks=:server:performanceScreen;project_properties=performanceRun`, `size_deadline_ms=120000`, and `task_timeout_ms=600000`. Local path values are normalized in future manifests, while version, vendor, runtime, heap, and instrumentation evidence remain recorded.

## CREATE connection attribution

The original `create-attribution-01` run remains preserved and labeled failed preflight. Its [manifest](data/backend-performance/create-attribution-01/manifest.json), [observations](data/backend-performance/create-attribution-01/observations.csv), [metrics](data/backend-performance/create-attribution-01/metrics.csv), and [status stream](data/backend-performance/create-attribution-01/status.jsonl) record 28 unstarted tuples and the retained fixture.

The single allowed freshness-order correction was captured under git HEAD `0ba6095b0d33aef3453d57a31504c4684c0dc55a`; its exact source identity is in the [replacement manifest](data/backend-performance/create-attribution-corrected-01/manifest.json). Focused compilation, repository lint, and 13 lifecycle tests passed before the replacement worker. The `create-on-off` worker launch was timestamped 2026-09-16T23:36:01Z; the [status stream](data/backend-performance/create-attribution-corrected-01/status.jsonl) recorded `run_start` at 2026-09-16T23:36:12Z. It exited 0 in 1m49s and completed 28/28 tuples, but its 3,000-page successful connect-inside-bind share was 2.764347%, below the fixed 35% gate. The investigation therefore stopped before `create-off-on`; no second worker, retry, optimization, or nomination was made.

| Size | OFF median ms | ON median ms | ON/OFF max/min | ON connect-inside-bind share median | OFF vs screen-corrected-01 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 1,460.742417 | 1,503.753833 | 1.029445 | 2.701241% | +1.523242% |
| 3,000 | 4,316.995792 | 4,325.942000 | 1.002072 | 2.764347% | +1.086372% |

The replacement worker recorded 29 observation lines including the header, 29 metrics lines including the header, and 95 status events ending in `run_complete`. All 28 rows were successful and every full `(run, arm, size, operation, phase, sample)` key was complete without duplicates. ON measured totals were bind `5,030` / connect `15,135` / inside-bind `5,030` at 1,000 pages and bind `15,030` / connect `45,135` / inside-bind `15,030` at 3,000 pages; all bind/open failure counts were zero and inside-bind success count equaled bind count on every ON row. OFF counters were zero by design. For ON, `bind_ns - connect_inside_bind_success_ns` had median/share `1,050.679803 ms / 70.464192%` at 1,000 pages and `3,105.504403 ms / 71.777037%` at 3,000 pages; `CREATE_ns - bind_ns` had median/share `407.604291 ms / 26.800205%` and `1,101.929693 ms / 25.458616%`, respectively. These are per-observation residuals summarized by median; neither is a fsync measure. Content/configuration fingerprints matched across arms, and both arms reported `busy_timeout=3000`, `journal_mode=delete`, `synchronous=2`, and `user_version=18`. The fixture and source staging were both deleted after the successful worker. Raw files are retained as the [manifest](data/backend-performance/create-attribution-corrected-01/manifest.json), [observations](data/backend-performance/create-attribution-corrected-01/observations.csv), [metrics](data/backend-performance/create-attribution-corrected-01/metrics.csv), [status stream](data/backend-performance/create-attribution-corrected-01/status.jsonl), [tracked patch](data/backend-performance/create-attribution-corrected-01/tracked.patch), [probe snapshot](data/backend-performance/create-attribution-corrected-01/BackendPerformanceScreenTest.kt), and [lifecycle snapshot](data/backend-performance/create-attribution-corrected-01/BackendPerformanceScreenLifecycleTest.kt). The measured connection-reuse hypothesis is unproven under the fixed criterion.

## Follow-up optimization trials

Neither of these first two trials met its predeclared threshold, and neither production change remains. The later atomic-confirmation candidate below passed its material-improvement gate.

A disposable real-binding experiment compared fresh and retained file-backed connections, in F1/R1/R2/F2 order, with 3,000 distinct same-ID rebinds per fixture. Loop times were 3358.605750 / 2584.712833 / 2517.987875 / 3056.089292ms. Savings of 773.892917 and 538.101417ms both missed the 1067.65029175ms opportunity threshold. All identity, materialization, epoch, physical-close and reopened-durability checks passed. This ordinary JVM test included Kover instrumentation; it is an opportunity screen, not an end-to-end CREATE comparison. An initial temporary-harness compilation failure produced zero observations; the corrected attempt and exact source are retained in local Crew receipts. The original test source was restored exactly, and no connection cache was implemented.

The second candidate added a difference predicate to the identity upsert, preserving every bind, transaction and binding-epoch increment while avoiding an identical row update. Its [single early-stop worker](data/backend-performance/create-guard-b0/manifest.json) completed 246/246 successful observations, with [raw observations](data/backend-performance/create-guard-b0/observations.csv), [status](data/backend-performance/create-guard-b0/status.jsonl), and [exact measured patch](data/backend-performance/create-guard-b0/tracked.patch) retained. CREATE medians were 387.904625ms at 250 pages, 1329.390791ms at 1,000, and 3991.408833ms at 3,000. The last is only 6.537542% / 4.431907% below the two historical corrected baselines, missing the predeclared 20% early-stop threshold against both (maximum 3341.2062336ms). No paired A/B campaign followed; this is not a paired speedup claim.

The worker launched after 2026-09-17T03:06:30Z and had exited 0 by 03:07:43Z; Gradle reported 1m1s. It used the same Java25.0.4.1 runtime, 1GiB heap, uninstrumented performance task, and `journal_mode=delete`, `synchronous=2`, `busy_timeout=3000`, schema18 at every size. Its manifest records the changed SQL and test hashes. The SQL guard and its guard-specific affected-row-count test were then reverted. The file-backed bind/epoch/materialization preservation test remains and passed on the JVM with the original SQL, alongside repository and stale-proof tests. The candidate's focused suite had 78 passing tests plus lint/detekt; no new full build or native-image verification is claimed for this later test addition.

## CREATE atomic confirmation

The owner approved one transaction for unchanged identity bindings. An eligible CREATE still scans, reconciles, renders, repairs links, and publishes the full index. After resolving identities, it checks the complete materialized binding set for the single local root in one transaction. If it matches exactly and has no matching tombstone, confirmation advances the root binding epoch by the number of bindings without rewriting each binding separately. This invalidates older deletion proofs while leaving the observation and recovery state unchanged. Any ordinary mismatch uses the original ordered individual-bind path. Invalid or exhausted epochs and database failures throw; they do not silently fall back.

The first candidate used the unchanged uninstrumented `screen` workload in four fresh workers, in A1/B1/B2/A2 order, with the immutable pre-optimization baseline and candidate on the same disk. There was no competing build or measurement work. All 984 scheduled observations passed (246 per worker). Medians are milliseconds:

| Pages | A1 | B1 | B2 | A2 |
| ---: | ---: | ---: | ---: | ---: |
| 250 | 447.743 | 143.279 | 168.239 | 494.433 |
| 1,000 | 1,566.378 | 424.776 | 420.779 | 1,654.900 |
| 3,000 | 4,596.990 | 1,241.823 | 1,211.111 | 4,784.091 |

The two CREATE3000 pairs saved 3,355.166 ms (72.986%) and 3,572.980 ms (74.685%). Each exceeded both the predeclared 25%/250 ms improvement requirement and twice the 187.101 ms A/A difference. Baseline and candidate repeatability ratios were below 1.20; each baseline was above 1,000 ms and within 20% of its historical corrected baseline. CREATE1000 and LINK/LIST/SAVE1000/3000 passed their predeclared regression checks. These results pass the adoption gate, although CREATE3000 remains above the original 1,000 ms screening threshold.

Raw evidence includes the exact measured patches and probe snapshots:

- [A1 manifest](data/backend-performance/create-confirm-a1/manifest.json), [observations](data/backend-performance/create-confirm-a1/observations.csv), [status](data/backend-performance/create-confirm-a1/status.jsonl), [source patch](data/backend-performance/create-confirm-a1/tracked.patch)
- [B1 manifest](data/backend-performance/create-confirm-b1/manifest.json), [observations](data/backend-performance/create-confirm-b1/observations.csv), [status](data/backend-performance/create-confirm-b1/status.jsonl), [source patch](data/backend-performance/create-confirm-b1/tracked.patch)
- [B2 manifest](data/backend-performance/create-confirm-b2/manifest.json), [observations](data/backend-performance/create-confirm-b2/observations.csv), [status](data/backend-performance/create-confirm-b2/status.jsonl), [source patch](data/backend-performance/create-confirm-b2/tracked.patch)
- [A2 manifest](data/backend-performance/create-confirm-a2/manifest.json), [observations](data/backend-performance/create-confirm-a2/observations.csv), [status](data/backend-performance/create-confirm-a2/status.jsonl), [source patch](data/backend-performance/create-confirm-a2/tracked.patch)

All four workers used Java 25.0.4.1, a 1 GiB heap, and the same probe and fixtures. The screen has no confirmation counter; its timings measure the public CREATE operation, while separate correctness tests establish confirmation eligibility and execution. No additional instrumentation was introduced to the measured path. Per-run source snapshots are intentionally retained independently, including duplicate bytes, so each historical result remains self-contained.

The measured corpus is the eligible best case: a single local root, materialized IDs in every page, a complete issue-free scan, and no extra durable or limbo bindings. Ineligible corpora retain the original per-page binding cost. Confirmation holds SQLite's write lock for a bounded root query and O(N) comparison. Confirmation lock hold and competing-writer latency at the 150,000-binding/cover scale remain unmeasured. The [earlier write-lock study](issue-23-write-lock-hold-measurement-report.md) found contention at that scale for different proof/checkpoint operations; it does not establish confirmation performance there. The configured busy timeout remains 3,000 ms, with unchanged durability settings. The earlier binding attribution did not isolate fsync cost, so these savings are attributed to avoiding redundant binding operations and transaction boundaries as a whole.

This first candidate passed focused tests, lint, and detekt. The subsequent review requested a stricter registered-root identity guard and additional regression tests. Its measurements remain tied to the retained first-candidate snapshots.

### Final candidate after review

The revised candidate requires the registered-root set to equal the CREATE target's singleton root. It adds exclusion, ordered fallback, transaction rollback/reopen, recovery, and materialized link/content/ID-change tests. All 117 focused tests passed; subsequent assertion improvements also passed focused tests, lint, and detekt. Deliberately omitting the epoch update caused a regression test to fail with loss of both live bindings and dirty recovery; restoring the production file byte-for-byte made the 35-test interleaving suite pass.

A separate fixed A1/B1/B2/A2 campaign measured this revised version from 05:19:30 to 05:23:33 UTC on 2026-09-17. All four workers exited successfully and all 984 observations passed. The original campaign was preserved and was not pooled with these results. CREATE medians, in milliseconds:

| Pages | Final A1 | Final B1 | Final B2 | Final A2 |
| ---: | ---: | ---: | ---: | ---: |
| 250 | 436.523 | 142.793 | 142.965 | 452.044 |
| 1,000 | 1,586.237 | 418.808 | 426.366 | 1,641.936 |
| 3,000 | 4,740.574 | 1,220.980 | 1,411.374 | 4,820.716 |

The final pairs saved 3,519.595 ms (74.244%) and 3,409.342 ms (70.723%). Both passed the unchanged 25%/250 ms and twice-baseline-noise requirements; A/A noise was 80.141 ms. Baseline and candidate repeatability ratios remained below 1.20, both baselines met the historical-condition checks, and every companion regression check passed. The final measured range is therefore **71–74% faster**, with CREATE3000 still above 1,000 ms. Probe, fixture, runtime, heap, and SQLite settings matched across arms; the per-run manifests and patches identify the measured source.

For reproducibility, the adoption rules applied separately to A1/B1 and A2/B2: CREATE3000 must save at least 25% and 250 ms, and more than twice the absolute A1/A2 difference. A1/A2 and B1/B2 max/min ratios must each be at most 1.20. Each baseline must be at least 1,000 ms and within 20% of its corresponding historical median (4,270.601167 and 4,176.507792 ms). Companion checks reject a CREATE1000 slowdown of both at least 100 ms and 10%, or a LINK/LIST/SAVE slowdown at either 1,000 or 3,000 pages of both at least 25 ms and 10%.

- [Final A1 manifest](data/backend-performance/create-confirm-r2-a1/manifest.json), [observations](data/backend-performance/create-confirm-r2-a1/observations.csv), [status](data/backend-performance/create-confirm-r2-a1/status.jsonl), [source patch](data/backend-performance/create-confirm-r2-a1/tracked.patch)
- [Final B1 manifest](data/backend-performance/create-confirm-r2-b1/manifest.json), [observations](data/backend-performance/create-confirm-r2-b1/observations.csv), [status](data/backend-performance/create-confirm-r2-b1/status.jsonl), [source patch](data/backend-performance/create-confirm-r2-b1/tracked.patch)
- [Final B2 manifest](data/backend-performance/create-confirm-r2-b2/manifest.json), [observations](data/backend-performance/create-confirm-r2-b2/observations.csv), [status](data/backend-performance/create-confirm-r2-b2/status.jsonl), [source patch](data/backend-performance/create-confirm-r2-b2/tracked.patch)
- [Final A2 manifest](data/backend-performance/create-confirm-r2-a2/manifest.json), [observations](data/backend-performance/create-confirm-r2-a2/observations.csv), [status](data/backend-performance/create-confirm-r2-a2/status.jsonl), [source patch](data/backend-performance/create-confirm-r2-a2/tracked.patch)

After this campaign, removal of a duplicate test import left production and benchmark inputs unchanged. That snapshot passed `./gradlew build` in 9m35s: 3,211 server tests passed, one was skipped, all 13 performance-worker lifecycle tests passed, and all 777 frontend tests passed. Lint, detekt, coverage, dependency allowlist, and migration checks passed. Native tests started 259 tests: 258 succeeded, one was aborted by an assumption, and none failed. The new `BindRevokesProofNativeTest` confirmation method explicitly reported SUCCESSFUL inside the native image. Both the JVM skip and native abort were `GitExecutorZombieNativeTest.reparentedZombieCompletesInvocation`, which requires the dedicated PID1 namespace. Native compilation passed and the compiled binary's spike passed 9/9.

These gates used the isolated Linux container with GraalVM CE25.3.4.1 / JDK25.0.4.1. Subsequent review corrected a SQL comment without changing executable SQL, and repaired the JVM measurement worker's post-observation deadline check. The worker now rejects an observation that finishes at or after the deadline, including the last observation, while preserving its already-emitted terminal row. Its operation timer, workload and application code are unchanged. All 15 focused lifecycle tests, lint and detekt passed. Removing the completion check caused the new boundary regression to fail; restoring it made the suite pass.

The retained performance snapshots predate that deadline repair. External whole-worker elapsed times for final A1/B1/B2/A2 were 70.760/35.829/37.131/72.729 seconds, respectively. Even the entire workers were below the 120-second per-size limit, so the repair does not disqualify those observations. This distinction preserves the actual measured source instead of claiming it is byte-identical to the current worker. The earlier characterization results below remain separate historical evidence.

## Source changes after measurement

The corrected pair remains tied to its retained source snapshots and recorded hashes: both workers exited 0, completed 246/246 observations, and removed their disposable fixture and staging directories. For that earlier screening checkpoint only, the timed region differs from the corrected-01 snapshot in using imported names for the same two test-grant calls; the invoked functions and workload are unchanged. That import-only statement does not describe the later profile/instrumented attribution implementation, which has its own exact source identity and snapshots in the replacement manifest above. The failed preflight and the below-threshold replacement worker are documented above. The final-build repair is later than the replacement snapshot: it changes test-only cleanup structure and lifecycle assertions, so the current source is not claimed byte-identical to the measured worker. Fresh focused compile, lint, lifecycle, and task-graph evidence applies to the current source, not retroactively to the preserved measurements.

## Superseded exploratory screen-01/screen-02 pair

The original `screen-01` and `screen-02` raw manifests, observations, and status streams are unchanged and remain available: [screen-01 manifest](data/backend-performance/screen-01/manifest.json), [observations](data/backend-performance/screen-01/observations.csv), [status stream](data/backend-performance/screen-01/status.jsonl); [screen-02 manifest](data/backend-performance/screen-02/manifest.json), [observations](data/backend-performance/screen-02/observations.csv), [status stream](data/backend-performance/screen-02/status.jsonl).

That pair is superseded for the screening decision because its proposal workload deviated from the approved schedule: it seeded proposal totals equal to page count (250, 1,000, and 3,000) instead of 100, 500, and 1,000, and screen-01 also predates the corrected source/launcher metadata capture. Those files are preserved as historical exploratory evidence and are not pooled with the corrected measurements.

The retained original and corrected screen manifests contain unnormalized actual local paths. Later output uses normalized path values while retaining the non-path runtime and provenance fields; the historical manifests and filenames are not rewritten.

## Earlier characterization verification

The following gates apply to the earlier characterization checkpoint, before the atomic-confirmation implementation. They do not certify the final optimization candidate.

The corrected source passed focused test compilation and `lintKotlin` before the replacement worker. After the correction, the current source passed focused test compilation, `lintKotlin`, `:server:detekt`, and all thirteen focused lifecycle tests; the fixed-profile task wiring check passed. The replacement output and fixture paths were fresh, and the exact relevant tracked patch plus probe/lifecycle source snapshots were captured beside the manifest before measurement. The completed replacement worker removed its fixture and source staging directories; no replacement run directory remains under `server/build/performance-screen` or `server/build/performance-screen-source`. Final verification used the isolated Linux container with GraalVM CE 25.3.4.1 / JDK 25.0.4.1. The full `./gradlew build` exited 0 in 9m 6s: 3,189 server tests passed, one was skipped, all 13 focused lifecycle tests passed, and all 777 frontend tests passed. Lint, detekt, dependency allowlist, and coverage checks passed. Native verification completed with 257 tests started, 256 successful, one aborted by an assumption, and zero failures; native compilation passed and the spike passed 9/9. Both the JVM skip and native abort are `GitExecutorZombieNativeTest.reparentedZombieCompletesInvocation`, which requires the dedicated PID1 namespace. The final probe repair changed only JVM test cleanup and assertions; main/nativeTest/Gradle inputs matched the native-verified snapshot. A 1,229-file hash comparison confirmed the full-build inputs remained unchanged except for this report.

The ordinary `:server:test --tests com.plainbase.performance.BackendPerformanceScreenTest` discovery control and the Kover `-javaagent` refusal control were run during the original implementation and remain applicable; neither control was rerun for the replacement worker. The ordinary test invocation exited 1 because the ordinary task excludes `com/plainbase/performance/**`. The refusal invocation exited 1 before fixture setup and created no refusal evidence or fixture directory. After that attribution result, the parent ran the full build and native gates described above. The subsequent optimization trials have their separate verification boundaries above.

These measurements are single-process, sequential JVM observations on one macOS AArch64 host. They do not establish concurrency behavior, native-image behavior, p95/tail guarantees, throughput, or a universal operating limit. Passing the correctness/build gates does not extend these performance observations to other environments.

A failure while copying source snapshots during preflight can leave a partial evidence directory without a terminal record or manifest. Such a run fails the task and is not usable evidence; acceptance requires successful worker exit, complete metadata and every scheduled observation. Preserve partial files and use a fresh run ID for any separately authorized replacement.

The worker's `completed_epoch_ms` is captured before final cleanup and writer closure. It is not an end-to-end duration; the recorded external launch/exit timing supplies the execution-budget check. Operation durations use the separate monotonic timer.
