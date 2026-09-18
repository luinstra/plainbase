# Historical server-shutdown and Git measurements

Recorded 2026-09-12. These values describe the accepted `05bLOCAL` fixture and `05d` Git comparison source
before the 06a/06b corrections. They are historical observations, not measurements of the corrected 06a/06b
tree, production defaults, capacity guarantees, or universal latency bounds.

## LOCAL shutdown observation

Run `d3685b6d-a6f8-411b-9844-3d11bcf11d06` used one LOCAL root with 32 deterministic valid-ID Markdown pages of
4,096 bytes each (131,072 initial bytes), Git disabled, and no OBJECT backend. One authenticated same-size PUT
completed before SIGTERM and left 4,096 durable bytes. The child ran on macOS AArch64 with Eclipse Adoptium
Java 21.0.11+10 on the JVM. The source was the accepted 05b return at parent HEAD
`dcadcdf2d68d8d0333156d5c0315ece03e8fc208`; the then-modified hook fixture SHA-256 was
`7e45bbd63a9150567663ba667739f516904ff185e8ec6aa67b51b271879b3428` and launcher SHA-256 was
`027237b16158721b66c59b4a7125ee4d7e6f6f2902eace20fb7ef2d4cd822d97`.

| Observation | ms |
| --- | ---: |
| Initial rebuild, 32 indexed pages | 139 |
| Authenticated PUT round trip | 18 |
| Completed owner shutdown, actual child log | 1,026 |
| Parent signal-through-post-exit/helper-fact observation | 1,046 |
| Inner wrapper signal-to-observed-exit timer | 1,045 |

The 1,026 ms owner value is the measured `H + W + R + D` aggregate and is included in the 1,046 ms outer
interval, not added to it. The 20 ms difference is derived, not separately sampled. For this workload,
`M/C/S/Q/B/U = 0` (Git disabled, LOCAL, OBJECT absent). A chosen 500 ms margin gives `1,046 + 500 = 1,546 ms`,
rounded once to a 2-second example supervisor setting. This is not a default or maximum.

## Git invocation comparison

Run `run-20260912T120732Z` compared baseline commit `ec02ec0625634623555bf0c79d04f7814fb4f444` with the
accepted 05d source manifest SHA-256 `835d5ae6551ced17926a6ff2b24888c98f8ffe25866594f7e00a78c218b9b0a1`,
later committed at `4c8269b2024d959d0f5950f18d9f126a2423d933`. The measurement overlay SHA-256 was
`319a3593e1f11768e0cfff6a07efa2c7ee1d1a51bc134731ac7e1a83481c3621` in both revisions.

Both ran on the same Ubuntu/Linux 6.12.76-linuxkit AArch64 builder with OpenJDK 21.0.12 for JVM execution and
GraalVM/SubstrateVM 25.0.2 for native execution. The Git binary was `/usr/bin/git` 2.43.0, SHA-256
`aa6540695d076182256dd6e96c8b302e4d56381e3000bbfd5c71bbdfe94a4942`. Builder image SHA-256 was
`78e0ea4b8251afbbeeda20963dd8641b4d098e34c50d33961c909932e12aff56`; native probe image SHA-256 values were
baseline `bb286b82b10310435bfa1fa423a186d9f64001fb4a0786eaafdaf12dc1669399` and 05d
`252c7d57679697aed3e60819e83de9b990219d80d0fda56e1425c0941ec5c100`.

Each fresh session prepared a small repository with two fixed commits and 4,096-byte page/blob inputs. The
timed public calls were `rev-parse --verify HEAD`, `hash-object --no-filters -w --stdin`, and
`diff --no-ext-diff --no-textconv <older> <newer> -- docs/page.md`. Each fresh session ran 20 warmups and
100 measured cycles of all three operations. There were two sessions per runtime/revision, for 480 warmup and
2,400 measured invocations overall. Within each runtime, session order was baseline, 05d, 05d, baseline. Each
table cell pools 200 measured samples per revision, runtime, and operation. Median is the average of the two
middle values; p95 uses nearest rank. Launcher startup,
compilation, setup, assertions, recording, and cleanup are excluded; failed or partial sessions were not pooled.

| Runtime / operation | Baseline median (ms) | 05d median (ms) | Baseline p95 (ms) | 05d p95 (ms) |
| --- | ---: | ---: | ---: | ---: |
| JVM HEAD | 0.662959 | 0.688147 | 0.782375 | 0.794000 |
| JVM stdin blob | 0.769792 | 0.817084 | 0.926250 | 0.985125 |
| JVM diff | 0.822104 | 0.856229 | 0.927750 | 0.978958 |
| Native HEAD | 0.736104 | 0.759563 | 1.906291 | 2.356792 |
| Native stdin blob | 0.749146 | 0.779209 | 2.298125 | 2.492209 |
| Native diff | 0.880584 | 0.916876 | 2.263541 | 2.683750 |

The observed median increases were 23.5–47.3 microseconds (about 3.2–6.1%). Native p95 increased by
0.194–0.451 ms. The largest measured invocation was 5.090958 ms. These results do not establish causality,
a universal latency bound, a statistical performance guarantee, large-repository behavior, remote OBJECT
capacity, bundle throughput, shutdown duration, or concurrent production contention.

## Receipt provenance and custody

The shutdown hook fixture now names its parent-side observation key
`signal_to_shutdown_entry_observed_ms`. It is not the child owner-entry timestamp. The historical raw receipt
key `signal_to_shutdown_entry_ms=109` remains byte-identical and must not be added to the 1,026 ms owner value.
Raw CSV, XML, logs, source snapshots, and receipts remain in the local ignored review archive and were not
rewritten:

- LOCAL: `.crew/reports/backend-analysis-2026-09-04/plan-03-prelock/stage-0c-checkpoint-05b/executor-evidence/final-run-d3685b6d-a6f8-411b-9844-3d11bcf11d06/`
- Git: `.crew/reports/backend-analysis-2026-09-04/plan-03-prelock/stage-0c-hot-path-r3-measured-05d/evidence/run-20260912T120732Z/`

These local-only paths preserve provenance but are not a public archive. Future measurements must name their
own source boundary and run identities rather than relabel these 05b/05d observations as 06a or 06b results.
