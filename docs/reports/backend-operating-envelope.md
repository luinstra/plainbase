# Backend performance

Measured September 16–17, 2026. CREATE was the only operation that justified optimization;
SAVE, page-link lookup, and proposal listing stayed below the screening thresholds.

CREATE previously repeated a database transaction for each unchanged identity binding during
its full index rebuild. Eligible single-local-root rebuilds now confirm the complete materialized
binding set in one transaction and advance its freshness epoch by the binding count. Full scanning,
reconciliation, rendering, and link repair remain. Mismatches use the original ordered binding path.

## Results

Two paired comparisons used identical synthetic corpora, file-backed SQLite and real search/index
wiring. Each worker ran in a fresh JVM, with Java 25.0.4.1, a 1 GiB heap, no coverage instrumentation,
and no competing workload on a macOS AArch64 host. Pages were 4 KiB with eight links each;
history and filesystem watching were disabled. Timings include the backend CREATE operation.

| Pages | Before, median range | After, median range |
| ---: | ---: | ---: |
| 250 | 437–452 ms | 143 ms |
| 1,000 | 1,586–1,642 ms | 419–426 ms |
| 3,000 | 4,741–4,821 ms | 1,221–1,411 ms |

At 3,000 pages, CREATE improved **71–74%**, saving **3.4–3.5 seconds**. Both pairs exceeded
the required 25% and 250 ms improvement and twice the baseline variation. All scheduled result
checks passed, and companion operations stayed within their regression limits.

## Limits and verification

These measurements cover a single local root with materialized IDs and a complete, issue-free scan.
Ineligible corpora retain the original binding cost. Concurrent-write latency, larger corpora,
HTTP/MCP overhead and native-image performance remain unmeasured. In particular, the
[earlier 150,000-binding write-lock study](issue-23-write-lock-hold-measurement-report.md) measures
different operations and does not establish this transaction's behavior at that scale.

Regression tests cover eligibility, ordered fallback, rollback, link repair and stale deletion-proof
invalidation. The full build and native gates passed; the native spike passed 9/9. A PID1-dependent
test requires its dedicated namespace and was skipped/aborted in the ordinary JVM/native runs.
