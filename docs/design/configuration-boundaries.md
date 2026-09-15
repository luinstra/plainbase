# Configuration boundaries

Plainbase keeps configuration loading, values, policy, and filesystem inspection in separate owners:

| Owner | Responsibility |
| --- | --- |
| `ConfigLoader` | Select and resolve operator/managed files or candidate text, then handle the command-load error funnel. |
| `ConfigDecoder` | Decode typed values, preserve precedence and provenance, and parse roots through its private parser. |
| `PlainbaseConfig` and adjacent value types | Hold one loaded configuration/provenance snapshot, expose declared paths and constants, and perform no filesystem inspection during construction or `copy`. |
| `ConfigValuePolicy` | Derive pure values and warning text from the snapshot. |
| `TransportSecurityPolicy` | Derive bind, cookie, and MCP transport values without network lookup; literal address parsing remains in `frameworks/net`. |
| `ConfigBootInspector` | Freshly observe current filesystem topology and return ordered refusals/warnings without caching, writes, logging, database, or runtime-resource work. |

Loading resolves the selected sources once into typed values and provenance. Inspection is deliberately on demand, so a
directory or symlink change can be observed without silently reloading configuration. Callers own when inspection occurs
and how diagnostics are emitted.

The serving path consumes topology refusals, storage warnings, roots warnings, and bind refusals in its existing order.
The complete boot gate combines configuration refusals with runtime root/history checks. Root commands validate candidates
through the same loader and decoder, then compare whole-gate refusal keys with the baseline. Deletion remains a writer
concern: an empty candidate does not prove that a live managed file can be removed safely, and the writer separately
protects existing backup entries.

See the [backend configuration compatibility report](../reports/backend-config-compatibility.md) for preserved behavior,
behavior assertions, and verification limits.
