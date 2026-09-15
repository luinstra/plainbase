# Backend configuration compatibility: Stage 0

This report records the first characterization chunk for Plan04's configuration separation work. It is bounded to
the current loader and decoder implementation: no production code, dependency, loader order, DNS behavior, backup
deletion policy, or later-stage ownership has changed here. A source trace is not test execution; every status below
distinguishes the two.

Baseline: `75853ba208bb14516cf56f97339fa8985df52202` on `codex/backend-04-configuration-separation`.

`fromSources` currently parses `plainbase.conf` before loading managed roots, so malformed operator syntax raises
`ConfigException.Parse` before managed-root loading processes its input. Stage 0 preserves this order and makes no
production change to it.

## Source map

- [PlainbaseConfig.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/PlainbaseConfig.kt) is the current
  production loader, decoder, value policy, and compatibility seam.
- [ConfigLoadingCompatibilityNativeTest.kt](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ConfigLoadingCompatibilityNativeTest.kt)
  contains the four new cases. The native source set is folded into the JVM `test` task and is also compiled into the
  native test image.
- [HoconParseNativeTest.kt](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/HoconParseNativeTest.kt)
  and [ManagedRootsFileNativeTest.kt](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ManagedRootsFileNativeTest.kt)
  are the nearby real-loader/native conventions used for comparison.
- [PlainbaseConfigTest.kt](../../server/src/test/kotlin/com/plainbase/frameworks/config/PlainbaseConfigTest.kt),
  [RootsConfigTest.kt](../../server/src/test/kotlin/com/plainbase/frameworks/config/RootsConfigTest.kt),
  [ManagedRootsConfigTest.kt](../../server/src/test/kotlin/com/plainbase/frameworks/config/ManagedRootsConfigTest.kt),
  [RootRankStabilityTest.kt](../../server/src/test/kotlin/com/plainbase/frameworks/config/RootRankStabilityTest.kt),
  [RootsValidationTest.kt](../../server/src/test/kotlin/com/plainbase/frameworks/config/RootsValidationTest.kt), and
  [BootGateTest.kt](../../server/src/test/kotlin/com/plainbase/BootGateTest.kt) are the JVM characterization suites
  referenced below.

## Stage 0 cases

The four cases use real `PlainbaseConfig` entry points, deterministic `DATA_DIR` and `CONTENT_DIR` values, owned
temporary directories, and `finally` cleanup. The malformed-file and backup fixture is one aggregate env-only
characterization. Its byte-equality checks are narrow unchanged-content checks, not independent proof that the loader
never reads any individual file.

| Case | Exact test | Characterized behavior | Stage 0 result |
| --- | --- | --- | --- |
| C04-S0-01 | [`env-only loading ignores malformed config files and backup evidence`](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ConfigLoadingCompatibilityNativeTest.kt#L20) | `fromEnv` ignores malformed `plainbase.conf`, malformed `roots.conf`, and regular backup evidence; it returns defaults, synthesized roots, and `ENV` content provenance. The same env map through `fromEnvAndFile` raises `ConfigException.Parse` from the malformed operator file. | `JVM-FULL` passed; `NATIVE-S0` passed in the final-source Linux image; macOS native image launch was blocked by the Xcode license. |
| C04-S0-02 | [`file-side data directory cannot redirect layered loading`](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ConfigLoadingCompatibilityNativeTest.kt#L57) | The file-side `dataDir` key is currently ignored: the env-selected data directory A and host `127.0.0.2` remain selected for both layered and candidate loads. The test asserts only those `dataDir` and host values; it does not claim whole-config equality. This is a regression characterization against future file-derived redirection. | `JVM-FULL` passed; `NATIVE-S0` passed in the final-source Linux image; macOS native image launch was blocked by the Xcode license. |
| C04-S0-03 | [`shadowed invalid fields preserve eager content and lazy host reads`](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ConfigLoadingCompatibilityNativeTest.kt#L83) | `host=[]` is shadowed by a valid `PLAINBASE_HOST`, while `contentDir=[]` still raises `ConfigException.WrongType` despite a valid `CONTENT_DIR`, and the exception names `contentDir`. | `JVM-FULL` passed; `NATIVE-S0` passed in the final-source Linux image; macOS native image launch was blocked by the Xcode license. |
| C04-S0-04 | [`nonpositive file sizes fall back while nonpositive environment sizes refuse`](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ConfigLoadingCompatibilityNativeTest.kt#L108) | File `maxWriteBodyBytes=0` and `maxAssetBytes=-1` fall back to defaults; positive env values override them; env `0` and `-1` fail with their actual env-key names. Existing `PlainbaseConfigTest` max-asset refusal coverage is retained and explicitly not treated as absent. | `JVM-FULL` passed; `NATIVE-S0` passed in the final-source Linux image; macOS native image launch was blocked by the Xcode license. |

## Declaration ownership inventory

This is a hand-enumerated baseline inventory relevant to the later configuration split. Stage 0 records it but does not
relocate any declaration. Line anchors are against the unchanged production baseline; this is a source-grounded ledger,
not an automated completeness proof.

| Current declarations | Intended owner after later chunks | Current callers / compatibility obligation |
| --- | --- | --- |
| `PlainbaseConfig` constructor: `contentDir`, `dataDir`, `host`, `port`, `maxWriteBodyBytes`, `maxAssetBytes`, `git`, `auth`, `storage`, `contentDirSource`, `roots` (38–76) | `PlainbaseConfig.kt` | Application, CLI, runtime root/history inputs, transport, gates, and direct constructor/copy tests; preserve defaults and copy semantics. |
| `appDatabasePath`, `managedRootsPath`, `searchDatabasePath`, `mainContentRoot` (79, 87, 94, 565) | `PlainbaseConfig.kt` | Runtime and CLI path consumers; pure value helpers remain value helpers. |
| `VERSION`, `DEFAULT_PORT`, `MANAGED_ROOTS_FILE`, `DEFAULT_HOST`, `DEFAULT_MAX_WRITE_BODY_BYTES`, `DEFAULT_MAX_ASSET_BYTES`, `DEFAULT_GIT_AUTHOR_NAME`, `DEFAULT_GIT_AUTHOR_EMAIL`, `DEFAULT_PROXY_IDENTITY_HEADER`, `DEFAULT_S3_REGION`, `DEFAULT_S3_POLL_SECONDS` (570–611) | `PlainbaseConfig` companion | Existing defaults and build-info consumers; preserve values and visibility. |
| `requireContentDir`, `bootRefusals`, `topologyRefusals`, `objectKeyRefusals`, `legacyRefusals`, `explicitRootRefusals` (115–327) | `ConfigBootInspector.kt` | Application, reindex, config tests, boot-gate tests, and native tests; preserve complete-vs-first behavior. |
| `DataDirFault`, `dataDirFault`, `dataDirComparable`, `dataDirDeclared`, `comparableRootPath` (328–354) | `ConfigBootInspector.kt` private | Topology and containment checks; retain declared/canonical distinctions and no cache. |
| `PrimaryFault`, `primaryFault`, `canonicalRootPathOrNull`, `bestEffortCanonical` (1169–1215) | `ConfigBootInspector.kt` private | Topology and warning callers; preserve ancestor fallback and permission handling. |
| `storageWarnings` (364–382) | `ConfigValuePolicy.kt` | Application and config tests; preserve warning timing and content. |
| `rootsWarnings`, `dataDirContainmentWarnings`, `managedRootsBackupWarning` (389–472) | `ConfigBootInspector.kt` | Application, CLI, boot and native tests; preserve containment → backup → explicit guard → ignored-content → unavailable-extra → glob-trap ordering. |
| `globbedRoots` (474–481), ignored-content warning body, editable-glob warning body | `ConfigValuePolicy.kt` private/internal | Inspector calls these at their existing positions; preserve direct-commit glob behavior. |
| `bindGuardRefusal`, `isNonLoopbackBind`, `secureCookie`, `mcpHostAllowlist`, `mcpOriginAllowlist`, `MCP_LOOPBACK_HOSTS` (490–549, 605) | `TransportSecurityPolicy.kt` / internal `TransportSecurityValues` | Transport, Ktor, REST, gate, bind, secure-context, and config tests; preserve exactly these five derived fields and no credential-bearing output. |
| `agentDirectCommitGlobs` (556–558) | `ConfigValuePolicy.kt` | REST module, direct-commit tests, and config consumers; retain rooted glob derivation. |
| `fromEnv`, `dataDirFrom`, `fromSources`, `fromEnvAndFile`, `fromEnvAndCandidateRoots`, `loadForCommand` (637, 646, 658, 767, 783, 796) | `ConfigLoader.kt` | Application, CLI, native spike, config/root/CLI tests; preserve names, defaults, and exception boundaries until the closeout chunk. |
| `loadManagedRoots`, `damagedRootsMessage`, `parseIfRegularFile`, `parseCandidate` (688, 723, 739, 754) | `ConfigLoader.kt` private | One real/candidate pipeline; null candidate remains empty config and does not observe the live backup. |
| `build`, `contentDirSource`, `positiveSize`, `buildGit`, `buildAuth`, `buildStorage` (817–957) | `ConfigDecoder.kt` | Loader-only decoder path; preserve evaluation order, env-only storage credentials, and proxy-secret file fallback. |
| `MISSING_S3_CREDENTIALS_MESSAGE`, `OBJECT_STORAGE_KEYS` (614, 623) | `ConfigDecoder.kt` private | Storage diagnostics; list only existing permitted key names. |
| `buildRoots`, `parseRootBlock`, `parseRoot`, `requireCoherentMainHistory`, `parseHistoryMode` (983–1159) | File-private `RootsConfigParser` in `ConfigDecoder.kt` | Root/config/native tests; preserve per-file ordering and history coherence. |
| `requireTreePathPrefix`, `requireParseableCidrs`, `requireParseableGlobs`, `mainDirectCommitGlobs`, `buildDirectCommitGlobsByRoot` (1245–1341) | `ConfigDecoder.kt` private | Decoder cross-field validation; preserve the bare-primary-name branch and avoid duplicate parsers. |
| `Map.longStrict`, `Map.positiveLongStrict`, `Long.toIntInRange`, `Map.boolStrict` (1344–1373); `String.toCommaList`; `Config.stringOrNull`, `intOrNull`, `longOrNull`, `stringListOrNull`, `boolStrict` (1378–1402) | `ConfigDecoder.kt` private | One typed decode pipeline; do not lazily skip currently eager getters. |
| `isAbsoluteHttpUrl`, `isHttpsUrl` (1220, 1233) | `ConfigValuePolicy.kt` internal | Decoder and S3 smoke command; URI parsing only, no DNS or network client. |
| `ConfigSource` with `DEFAULT`, `FILE`, `ENV` (1405) | `ConfigSource.kt` | Loader, decoder, value, inspection, and config-test callers; preserve provenance. |
| `StorageBackend` and parser; `StorageConfig` fields `backend`, `endpoint`, `bucket`, `region`, `prefix`, `pathStyle`, `pollSeconds`, `accessKeyId`, `secretAccessKey`, `ignoredObjectKeys` (1417–1465) | `StorageConfig.kt` | Runtime/storage and decoder callers; retain enum grammar and no secret debug output. |
| `RootsOrigin`; `RootsConfig.list`, `origin`, `primaryDeclared`, `managed`, `primary`, `extras`, `of`, `synthesized` (1472–1562) | `RootsConfig.kt` | Root registry, CLI, decoder, root/boot/native tests; primary access never reorders the list. |
| `GitConfig.enabled`, `authorName`, `authorEmail` (1566–1570) | `GitConfig.kt` | Prepared history selection and config tests; retain the tri-state. |
| `AuthMode` and parser; `AuthConfig.mode`, `trustedProxyCidrs`, `insecureHttp`, `agentDirectCommitGlobs`, `agentDirectCommitGlobsByRoot`, `proxySecret`, `proxyIdentityHeader`, `mcpAllowedHosts`, `mcpAllowedOrigins` (1580–1649) | `AuthConfig.kt` | Transport, decoder, direct-commit, and config callers; preserve enum grammar and defaults. |
| `RemoteAddress.isLoopbackAddress`, `isNonLoopbackBind`, `isInAnyCidr`, `forwardedProtoIsHttps`, `isParseableCidr`; private `isLoopbackBindLiteral`, `stripPort`, `parseNumericLiteral`, `isStrictIpv4`, `matchesCidr`, `parseCidr`, `sharesPrefix`; `ParsedCidr.network`, `ParsedCidr.prefix`; constants `BITS_PER_BYTE`, `IPV4_OCTET_COUNT`, `MAX_IPV4_OCTET_DIGITS`, `MAX_IPV4_OCTET` in `frameworks/ktor/RemoteAddress.kt` | `frameworks/net` in a later chunk | Config policy, principal, secure-context, CSRF, and address tests; Stage 0 does not add the no-DNS correction or move this file. |
| `ManagedRootsFile.BACKUP_SUFFIX`, `HEADER`, `serialize`, `writeAtomically`, `verifyLanded`, `copyPreservingPrevious`, `delete`, `hoconQuote`, and logger | Existing `ManagedRootsFile` adapter | Stage 0 does not add the no-follow backup deletion guard or alter I/O error channels. |

## Future documentation and source guards

All items in this section are later-stage obligations, not Stage 0 implementation claims. The current source and guard
tests establish these seams:

- Retarget the dangling `PlainbaseConfig.requireContentDir` KDoc reference `[validateExplicitRoots]` (111) to the
  actual `explicitRootRefusals` producer (234). Move `RootsConfig.primaryDeclared`'s
  `[PlainbaseConfig.rootsWarnings]` reference (1492) to the inspector owner, and move the `Application.kt` bind-warning
  KDoc reference (746) to the future policy owner of `bindGuardRefusal`. Every moved body must carry its KDoc,
  producer, caller, and owner links with it, including the loader, decoder, value-policy, inspector, transport-policy,
  roots, storage, auth, and `RemoteAddress` moves.
- [`RootWiringArchitectureTest.kt`](../../server/src/test/kotlin/com/plainbase/RootWiringArchitectureTest.kt) has six
  primary-comparison matches in `PlainbaseConfig`: parser lines 997/1001 move as two to `ConfigDecoder`, inspector
  line 249 moves as one to `ConfigBootInspector`, and values lines 1511/1514/1526 move as three to `RootsConfig`.
  Keep the per-file whitelist and exact counts, retain the Tier-3 `isPrimary` ledger (`TreeJsonCache` has one call),
  and allow no blank or zero-count exemption. The bare `RootName.PRIMARY` comparisons at 1097 and 1333 are decoder
  ledger entries, not additional regex matches.
- [`CliBootGateArchitectureTest.kt`](../../server/src/test/kotlin/com/plainbase/CliBootGateArchitectureTest.kt) must
  remove the dead `validateExplicitRoots` token when its producer checks become nonvacuous, prohibit actual refusal
  producers, and still permit the legitimate root-warning call after `bootGateFor`. [`BootRefusalLedgerTest.kt`](../../server/src/test/kotlin/com/plainbase/BootRefusalLedgerTest.kt)
  keeps the owned and loader ceilings; native `BootGateOrderingTest` provides verdict rank/kind-partition coverage,
  while emitted-warning order remains a later gap. No blanket
  `ConfigBootInspector` ban is intended.
- A future `ConfigSeparationArchitectureTest` is staged with incremental Stage 1 import/direction/effect guards,
  Stage 2 `ConfigSources` confinement and deliberate external-reference RED coverage, Stage 3 inspector
  boundary/effect guards, and Stage 4 unresolved-link and forwarding checks. No future-file assertions are added in
  Stage 0.
- The native relocation set remains [`HoconParseNativeTest.kt`](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/HoconParseNativeTest.kt),
  `RootCommandNativeTest`, `RootCommandNativeHistoryTest`, `RootsLockNativeTest`, and the existing config and boot
  native seams. Stage 0 does not move or duplicate these tests.
- Stage 0b later adds the JVM-only `RemoteAddressNoDnsTest` provider/launcher and the execution-time
  `plainbase.test.childRuntimeClasspath` Gradle property sourced from `test.runtimeClasspath`, with only a temporary
  service descriptor and a bounded 30-second process/drain/cleanup proof. It also adds the lean native literal test;
  Stage 1 relocates that test with the corrected parser. These are future guards, not current Stage 0 changes.

## C04 compatibility rows

These rows preserve the reviewed compatibility inventory. Status records observed execution only; the deferred ledger
names planned additions and their earliest owners. A passing surrounding suite cannot promote a source trace to a
compatibility result.

| Row | Current source trace | Exact reviewed tests / assertions | Status and earliest owner |
| --- | --- | --- | --- |
| C04-01 loading | `fromEnv` calls `build` with an empty config (637); `fromSources` selects env data dir and parses the operator file (658–662); `parseIfRegularFile` propagates `ConfigException` (739–744). | New `ConfigLoadingCompatibilityNativeTest` case `env-only loading ignores malformed config files and backup evidence` compares the env-selected content/data paths, `ConfigSource.ENV`, `DEFAULT_HOST`, `DEFAULT_PORT`, `DEFAULT_MAX_WRITE_BODY_BYTES`, `DEFAULT_MAX_ASSET_BYTES`, `RootsOrigin.SYNTHESIZED`, primary `docs`, and unchanged byte arrays for the three fixture files; the layered fixture throws `ConfigException.Parse`. Existing `PlainbaseConfigTest` case `a missing plainbase.conf is a clean no-op: fromEnvAndFile equals fromEnv field-for-field` (135) compares the whole values. No universal no-read oracle is asserted. | Observed: `JVM-FULL` passed the named JVM assertions; `NATIVE-S0` passed the named new case in the final-source image. |
| C04-02 precedence/strictness | `build` reads `contentDirFile` eagerly at 821; `host` is lazy behind `PLAINBASE_HOST` at 844; `positiveSize` is called at 846–853 and its strict env/positive-only file-fallback body is at 866–874. | New `shadowed invalid fields preserve eager content and lazy host reads` expects `host == 127.0.0.2` while `contentDir=[]` raises `ConfigException.WrongType` naming `contentDir`. New `nonpositive file sizes fall back while nonpositive environment sizes refuse` expects file `0/-1` to yield the two `DEFAULT_*` constants, env `2097152/3145728` to yield those `Long` values, and env `0/-1` for each size key to raise `IllegalArgumentException` naming that key. Existing per-field cases retain their actual names. | Observed: `JVM-FULL` passed the named JVM assertions; `NATIVE-S0` passed both named new cases. |
| C04-03 HOCON | `parseIfRegularFile` resolves operator files (739–744); `parseCandidate` resolves candidate text (754–756). | `HoconParseNativeTest.fromEnvAndFile parses a plainbase_conf inside the native image` expects `AuthMode.BUILTIN` and CIDRs `[10.0.0.0/8]`; `HoconParseNativeTest.fromEnvAndFile resolves within-file and optional substitutions inside the native image` expects host `10.10.10.10` and CIDRs `[192.168.0.0/24]`. `PlainbaseConfigTest` expects the unset optional substitution to fall to `DEFAULT_HOST` (211) and the within-file substitution to yield `127.0.0.1` (219); these do not prove process-env/map isolation. | Observed: `JVM-FULL` passed the named JVM assertions; `NATIVE-S0` passed both named `HoconParseNativeTest` methods. |
| C04-04 provenance | `fromSources` hands both parsed configs to `buildRoots` (661, 983); `RootsConfig.of` captures origin and managed names (1510 onward). | `ManagedRootsConfigTest` case `roots.conf alone yields a SYNTHESIZED main from CONTENT_DIR plus the managed extras, origin EXPLICIT` (32–43) expects `origin == EXPLICIT`, ordered names `[docs, notes]`, `primary.localPath == Path.of('/roots/docs')`, `primaryDeclared == false`, `managed == [notes]`, and `extras.single.editable == true`, all from one real load. | Observed: `JVM-FULL` passed these named assertions; no dedicated Stage 0 native method. |
| C04-05 root merge | `buildRoots` parses operator and managed blocks separately and concatenates them without hoisting primary (983–1034). | `RootsConfigTest` expects names `[zeta, docs, alpha]` (68). `RootRankStabilityTest` expects the same order with `docs` rank 1 and `zeta` rank 0, and its other case expects `[docs, zebra, notes]` with `zebra` ranked before `notes` (72–118). `ManagedRootsConfigTest` expects merged `[docs, hand, cli]`, `managed == [cli]`, and `primaryDeclared == true` (52–68). | Observed: `JVM-FULL` passed all named `RootsConfigTest`, `RootRankStabilityTest`, and `ManagedRootsConfigTest` assertions; no dedicated Stage 0 native method. |
| C04-06 candidate artifact | `fromEnvAndCandidateRoots` uses the shared source pipeline (783); `RootCommand` serializes the candidate, validates it first, then compares baseline keys (335–369). | `ManagedRootsFileNativeTest` case `the in-memory candidate and the on-disk file parse to the SAME roots - the gate's load-bearing assumption` (68) asserts `candidate.roots.list == landed.roots.list` and managed-set equality only; it does not assert whole-config or diagnostics equivalence. The direct source trace records candidate-before-baseline validation separately. | Observed: `JVM-FULL` and `NATIVE-S0` passed the named candidate parity test. |
| C04-07 interrupted promotion | `loadManagedRoots` checks backup evidence before synthesis, wraps malformed managed files, and refuses files without `roots {}` (688–711). | `ManagedRootsFileNativeTest` expects `ABSENT_CLEAN` to load managed-empty, `WHOLE_WITH_STALE_BACKUP` to load `{alpha}` with a warning containing `.bak`, and `ABSENT_WITH_BACKUP`, `ZERO_LENGTH`, `ZERO_LENGTH_WITH_BACKUP`, `HEADER_ONLY`, and `TRUNCATED_MID_BLOCK` to throw `IllegalArgumentException` with `roots.conf` and `delete` in the message, plus `mv` when backup is true. `ManagedRootsConfigTest` case `(d) an empty block in the MANAGED file IS absence - the asymmetry is the point` yields `SYNTHESIZED`. No byte-exact whole-message assertion is claimed. | Observed: `JVM-FULL` passed the named managed-root assertions; `NATIVE-S0` passed the named interrupted-promote method. |
| C04-08 topology | `explicitRootRefusals` uses `comparableRootPath` and `bestEffortCanonical` to retain unavailable entries (234, 354, 1205). | `RootsValidationTest` expects the duplicate-symlink failure to contain `resolve to the same directory` and `twin`; the missing `DATA_DIR` through an ancestor link to contain `declare the root` and `DATA_DIR` through consistent paths; and the duplicate-nonexistent-extra failure to contain `resolve to the same directory`. These are the tested message fragments, not invented `BootRefusal` keys. | Observed: `JVM-FULL` passed the named `RootsValidationTest` assertions; no dedicated Stage 0 native method. |
| C04-09 freshness | `rootsWarnings` probes on each call (389); `bootRefusals` recomputes topology (139); no cache field was found. | No dedicated freshness assertion exists in Stage 0. Existing warning/refusal methods are named source and suite context only; whole-suite success is not promoted to freshness proof. | Observed: `JVM-FULL` completed the relevant config/boot suites with no failures; no dedicated native or JVM freshness test was added. |
| C04-10 diagnostics | `BootGateTest` exercises complete refusal collection at 120; application consumption stages topology, storage/root warnings, bind refusal, and insecure-bind warning at 418–436. | `BootGateTest` retains ordered kinds `[PRIMARY_UNUSABLE, ROOT_PAIR]` and requires `requireContentDir` failure message to equal the first refusal message. Native `BootGateOrderingTest` provides verdict rank/kind-partition assertions only; it does not assert emitted warning sequence. | Observed: `JVM-FULL` passed the named `BootGateTest` assertions; `NATIVE-S0` passed the named `BootGateOrderingTest` verdict rank/kind-partition methods. |
| C04-11 security | `mcpHostAllowlist` derives bind host plus loopback at 536; literal parsing currently lives in `frameworks/ktor/RemoteAddress.kt`. | `PlainbaseConfigTest` expects the loopback default to be nonempty, include `127.0.0.1`, and exclude `*` and `0.0.0.0`; the non-loopback default to include `docs.example.com` and exclude those wildcards; and the override to equal `[docs.example.com, proxy.example.com]`, with the accessor including `docs.example.com`. The accessor's whole-list order is not asserted. | Observed: `JVM-FULL` passed the named `PlainbaseConfigTest` assertions; no dedicated Stage 0 native method. |
| C04-12 effects/error funnel | `loadForCommand` catches `IllegalArgumentException` and `ConfigException` around the real loader (796). | `PlainbaseConfigTest` expects a bad object-mode load to return `null` and emit exactly one error containing `serve:` and `storage.object.endpoint is required`, and a malformed operator load to return `null` and emit exactly one error containing `admin:`. Native `BootGatePurityTest` compares the owned tree before/after and checks no `.git` or git-home creation; this is not a general purity promise. | Observed: `JVM-FULL` passed the named `PlainbaseConfigTest` and `BootGatePurityTest` assertions; `NATIVE-S0` passed the named creates-nothing test. |

### Exact test-name lookup

The assertion rows above use this lookup where they abbreviate a test label. These are existing executed tests, not deferred additions.

| Row | Test class | Exact test name |
| --- | --- | --- |
| C04-03 | `PlainbaseConfigTest` | `an optional ${?…} substitution for an UNSET var parses without throwing and falls to the default` |
| C04-03 | `PlainbaseConfigTest` | `a WITHIN-FILE substitution resolves: the value flows through (proves .resolve() ran)` |
| C04-05 | `RootsConfigTest` | `entries parse in origin-line order, not HOCON iteration order (D7)` |
| C04-05 | `RootRankStabilityTest` | `(a) main keeps its DECLARED rank when roots.conf extras merge - it is NEVER hoisted to 0` |
| C04-05 | `RootRankStabilityTest` | `(b) a hand-declared root at a HIGH line number still outranks a CLI-added root at a LOW one` |
| C04-05 | `ManagedRootsConfigTest` | `the two files MERGE: hand-declared roots and CLI-managed roots serve side by side, file 1 then file 2` |
| C04-07 | `ManagedRootsFileNativeTest` | `an interrupted promote refuses the boot rather than silently reverting the install to single-root` |
| C04-08 | `RootsValidationTest` | `explicit: a duplicate path via a symlink is refused (toRealPath catches it)` |
| C04-08 | `RootsValidationTest` | `explicit: a MISSING DATA_DIR declared through a symlinked ancestor into a root is refused (first boot)` |
| C04-08 | `RootsValidationTest` | `explicit: two extras with the same nonexistent path still collide via the declared-form fallback (D13)` |
| C04-10 | `BootGateTest` | `T-GATE-3(b): a config with TWO topology faults reports TWO - this is the one a throw-first validator CANNOT do` |
| C04-10 | `BootGateOrderingTest` | `an UNAVAILABLE root at rank 1 precedes a REFUSED root at rank 2 - so its WARN still prints` |
| C04-10 | `BootGateOrderingTest` | `a GIT_GATE refusal is NOT consumed by the topology or bind stages, so it cannot swallow the config warnings` |
| C04-11 | `PlainbaseConfigTest` | `no MCP keys → mcpHostAllowlist defaults to the bind host (not empty, not a wildcard)` |
| C04-11 | `PlainbaseConfigTest` | `a non-loopback bind defaults the MCP host allowlist to that bind host (+ loopback), still no wildcard` |
| C04-11 | `PlainbaseConfigTest` | `an explicit PLAINBASE_MCP_ALLOWED_HOSTS overrides the default` |
| C04-12 | `PlainbaseConfigTest` | `loadForCommand funnels a bad object-mode config (IAE) into a clean <cmd>: message + null, never a stack trace (R2-2)` |
| C04-12 | `PlainbaseConfigTest` | `loadForCommand also funnels a malformed plainbase.conf (HOCON ConfigException), not just IAE (R2-2)` |
| C04-12 | `BootGatePurityTest` | `the boot gate creates nothing - not the git-home, not a repo, not a single byte` |

### Deferred characterization ledger

These stable case IDs are planned additions, not implemented Stage 0 tests; each row names the earliest owning stage.

| Deferred case ID | Earliest owner | Planned assertion or guard |
| --- | --- | --- |
| C04-02-decode-order | Stage 2 decoder | Characterize affected invalid-shadowed typed fields and cross-field error precedence before moving them; extend `PlainbaseConfigTest` only where assertions are missing. |
| C04-03-process-env | Stage 2 loader | Add a deterministic actual-process-environment versus injected-map substitution fixture without process-global mutation. |
| C04-03-parse-errors | Stage 2 loader | Record exact exception classes for malformed real operator and candidate inputs. |
| C04-04-source-snapshot | Stage 2 loader | In `ManagedRootsConfigTest`, change `roots.conf` after load, require old-config provenance unchanged, and require a new load to reflect the change. |
| C04-05-root-refusals | Stage 2 roots parser | Trace and execute duplicate cross-file, machine-primary, operator-empty/machine-empty, history-coherence, and rooted-glob assertions in the named root suites; add only missing affected cases and preserve existing per-file line/name order and primary-rank cases. |
| C04-06-candidate-parity | Stage 2 loader/decoder | Add normalized-config and provenance equality for valid candidate versus written bytes, plus exact exception-class/message parity for invalid candidates. |
| C04-06-last-root-delete | Stage 0c | Exercise real removal of the last managed root with backup (RED) and without backup (GREEN), then reload and verify the remaining-root promotion control. |
| C04-07-entry-matrix | Stage 2 loader | Characterize nonregular operator/managed entries and readable/unreadable preconditions; record unsupported permission fixtures. |
| C04-07-backup-delete | Stage 0c | Require no-follow refusal for regular, directory, and dangling-symlink backups; fail closed on inspection errors; permit delete only after confirmed absence, keeping loader and warning semantics distinct. |
| C04-08-topology-matrix | Stage 3 inspection | Trace and execute declared, canonical, aliased, nonexistent, and permission cases in `RootsValidationTest`, `BootGateTest`, and `RootsParseNativeTest`; close live-channel distinctions without collapsing existing differences. |
| C04-09-freshness | Stage 3 inspection | Add `ConfigBootInspectorFreshnessTest` coverage for same-config absent/create/remove extra, backup add/remove, candidate/baseline independence, canonical-topology mutation, and memoized-same-config/global RED controls. |
| C04-10-warning-order | Stage 3 warnings | Assert ordered legacy/explicit multi-signal containment, backup, ignored-content, unavailable-extra, and glob warnings; verify actual server channel/order and `RootCommand` introduced/preexisting keyed faults and candidate-first checks. Existing `BootGateTest` remains partial proof. |
| C04-11-no-dns | Stage 0b | Add `RemoteAddressNoDnsTest` resolver-attempt sentinel and strict ASCII, embedded-IPv4, and port RED/GREEN/native cases. |
| C04-11-policy-matrix | Stage 1 | Assert exact MCP host/origin list equality and order, proxy CIDR plus secret completeness before loopback, and insecure-cookie/secure-context behavior in the existing bind/security/config suites. |
| C04-12-error-funnel | Stage 2 loader | Assert exact single command prefix/message and an unhandled unrelated-exception case. |
| C04-12-effects | Stages 1–3, with Stage 4 closeout | Incrementally guard pure policy/value/decoder/no-FS behavior, net/config direction, `ConfigSources` confinement, and inspector read-only/no-DB/no-DI/no-history/availability mutation traces; Stage 4 closes removed forwarders, callers, and KDoc obligations. These are operation guards and call traces, not whole-program purity proof. |

## Verification evidence
Gate keys used below are `JVM-FULL` for the Linux full-build JVM XML set and `NATIVE-S0` for the Linux native image
run. All commands are run from the repository root with an official Java 25.0.4.1/GraalVM CE 25.3.4.1+1.1
installation, `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`, `--max-workers=2`, and `--console=plain` for Gradle. The Linux source
manifest checked 1,177 files, tracked files plus the new test.

| Gate | Command | Observed result |
| --- | --- | --- |
| Focused JVM suite (macOS) | `./gradlew :server:test --tests '*ConfigLoadingCompatibilityNativeTest' --tests '*PlainbaseConfigTest' --tests '*ManagedRootsConfigTest' --tests '*RootRankStabilityTest' --tests '*BootGateTest' --tests '*RootWiringArchitectureTest' --tests '*CliBootGateArchitectureTest' --tests '*BootRefusalLedgerTest' --rerun-tasks --max-workers=2 --console=plain` | 105 tests passed, 0 failed or skipped; the four new cases passed. |
| Revision focused four-case test (macOS) | `./gradlew :server:test --tests '*ConfigLoadingCompatibilityNativeTest' --max-workers=2 --console=plain` | `BUILD SUCCESSFUL` in 5s; exactly four tests passed after the `ConfigUtil.quoteString` fixture correction. |
| Focused Git/config reproduction (Linux) | `./gradlew :server:test --tests '*GitCliHistoryProviderTest' --tests '*ConfigLoadingCompatibilityNativeTest' --max-workers=2 --console=plain` | `BUILD SUCCESSFUL`; both affected Git cases and all four new configuration cases passed, resolving the macOS Xcode-host reproduction. |
| Full JAR floor (macOS) | `./gradlew build --max-workers=2 --console=plain` | Frontend 777 passed. macOS XML accounting: 412 suites, 3,061 tests, 2 skipped, 2 failed, 0 errors; the Gradle console counted 3,060 completed. The counters are different reporting sources, not a platform test delta. |
| Full JAR floor (Linux) | `./gradlew build --max-workers=2 --console=plain` | `BUILD SUCCESSFUL` in 9m15s; 30 tasks, 9 executed, 21 up-to-date. Linux XML accounting: 412 suites, 3,061 tests, 1 skipped, 0 failures, 0 errors; the new class passed all four tests; frontend 777 passed. |
| Native test execution (macOS) | `./gradlew :server:nativeTest --max-workers=2 --console=plain` | `nativeTestCompile` stopped before image launch because `/usr/bin/cc` required the Xcode license; no macOS native test case executed. |
| Native test execution (Linux) | `./gradlew :server:nativeTest --max-workers=2 --console=plain` | Fresh native test-image compile and run completed in 1m33s: 232 found and started, 231 successful, 1 aborted, 0 failed. The four named Stage 0 methods were `SUCCESSFUL` in-image. The sole abort was `GitExecutorZombieNativeTest.reparentedZombieCompletesInvocation()`, an expected non-PID1 topology abort covered by the JVM topology assumption. |
| Native image compilation (macOS) | `./gradlew :server:nativeCompile --max-workers=2 --console=plain` | `BUILD SUCCESSFUL`; application `nativeCompile` was `UP-TO-DATE`, not a fresh compile. |
| Native image compilation (Linux) | `./gradlew :server:nativeCompile --max-workers=2 --console=plain` | `BUILD SUCCESSFUL` in 4s; application `nativeCompile` was `UP-TO-DATE`, not a fresh compile. Production source was unchanged. |
| Standalone native spike (macOS) | `server/build/native/nativeCompile/plainbase spike` | `SPIKE OK — 9/9 checks passed` in the existing application image. |
| Standalone native spike (Linux) | `server/build/native/nativeCompile/plainbase spike` | `SPIKE OK — 9/9 checks passed`. |

The first Linux attempt failed on archive-created AppleDouble sidecars. Only disposable-container `._*` metadata was
removed; the final Linux run passed without source fixes. The final-source Linux gate outcomes above are the completed
characterization evidence. No additional checks were run for these report-only corrections.

### Preserved macOS environment failures

- Both affected `GitCliHistoryProviderTest` cases were blocked by the same Git fixture failure: `git init` returned
  exit 69 because the Xcode license was not accepted. The normal-batch case
  (`lastCommits issues ONE git log process for a normal batch of paths (not one-per-path)`) failed at
  `GitCliHistoryProviderTest.kt:577`; the large-path case (`lastCommits batches a large path set under the argv budget
  — correct attributions, O(N/batch) walks`) then failed `expected true but was false` at line 613.
- The macOS native run did not reach image launch. The Linux native abort is the expected non-PID1 topology case above,
  not a new failure. No PID1/container campaign was run.

This chunk does not claim that Plan04 is complete. Later chunks still own literal-only no-DNS parsing, conservative
backup deletion, policy/net separation, values/loader/decoder relocation, inspection/freshness and warning order, and
caller/documentation closeout.
