# Backend configuration compatibility: Stage 0, Stage 2, and Stage 3

This report records Plan04's Stage 0 configuration compatibility work. The Stage 0 and Stage 0b sections retain their
historical characterization scope; the Stage 0c section below records the narrow backup-entry deletion guard and its
real command/writer measurements. A source trace is not test execution; every status below distinguishes the two.

Stage 0/0b baseline: `75853ba208bb14516cf56f97339fa8985df52202`. Stage 0c implementation base:
`7d0a76b84b1f18db88139ed57c8dc545215fad3e`, on `codex/backend-04-configuration-separation`.

`fromSources` currently parses `plainbase.conf` before loading managed roots, so malformed operator syntax raises
`ConfigException.Parse` before managed-root loading processes its input. Stage 0 preserves this order and makes no
production change to it.

## Plan04 Stage 2 landed ledger

Stage 0/0b/0c tables below retain their accepted-baseline anchors and historical results. The combined Stage 2
extraction was made from accepted source `101a64ed933258ec4ae410cc0972ac5249650ad8`; current ownership and receipts
are recorded here and in the local Stage 2 report directory. Stage 3 continues from accepted Stage 2 commit
`f97a1576fefc6f93ea20d89638b7ad1cb2a1b496`; its focused implementation evidence is summarized in the Stage 3 ledger
below, with detailed raw logs retained as local records.

| Landed owner | Declarations moved or retained | Production callers / compatibility boundary |
| --- | --- | --- |
| `ConfigSource.kt`, `StorageConfig.kt`, `RootsConfig.kt`, `GitConfig.kt`, `AuthConfig.kt` | Normalized source, storage, roots, Git, and auth values; enum parsers and `RootsConfig` snapshot/default/copy behavior. | `PlainbaseConfig` constructor keeps the same parameter order/defaults; existing value, root, transport, and CLI tests continue to construct the same types. |
| `ConfigValuePolicy.kt` | Pure `dataDirFrom`, storage diagnostics, ignored-content warning, editable-glob warning, rooted direct-commit glob projection, and absolute/HTTPS URL predicates. | `Application` consumes storage warnings; `ConfigBootInspector` consumes pure warning text; `RootCommand`, `RestModule`, and `S3SmokeCommand` use the owner. `PlainbaseConfig` keeps temporary compatibility delegates. |
| `ConfigLoader.kt` | `ConfigSources`, file/candidate parsing, managed-roots damage handling, and the exact `loadForCommand` IAE/HOCON funnel. | `Application`, `AdminCommand`, `AdoptCommand`, `ReindexCommand`, `RootCommand`, `ConfigModule`, and `NativeSpike` call `ConfigLoader`; candidate text is parsed before the live managed-file lane. |
| `ConfigDecoder.kt` | Typed decode/build helpers and the file-private `RootsConfigParser`; one `decode` call path from `ConfigLoader`. | HOCON resolution, eager typed getters, source provenance, per-file origin ordering, merge order, primary/history rules, and managed-file distinctions are retained. |
| `PlainbaseConfig.kt` | Constructor, derived paths, and thin compatibility forwarders only; no topology or filesystem warning bodies remain. | Constructor/default/copy behavior is unchanged. `RootWiringArchitectureTest` records no primary comparison here; Stage 4 still owns removal of temporary forwarders. |
| `ConfigBootInspector.kt` | Stateless config/filesystem `requireContentDir`, complete `bootRefusals`, and ordered `rootsWarnings`; canonical/declared probes and the shared storage credential diagnostic remain in their existing owners. | `Application` consumes fresh boot/refusal/warning projections; `ReindexCommand` consumes first-only content refusal; `RootCommand` consumes warning-only candidate inspection after candidate-before-baseline gating. |

Moved KDoc now points at `ConfigBootInspector.requireContentDir`, `ConfigBootInspector.bootRefusals`,
`ConfigBootInspector.rootsWarnings`, `ConfigLoader.loadManagedRoots`, and the current policy owner. The source guards
are intentionally non-vacuous: value/loader/decoder/inspector files must exist and be nonempty, pure owners reject
filesystem effects, the inspector permits only read probes, `ConfigSources` is confined to loader/decoder, decoder calls
are confined to loader, and the single parser owner is required.

## Source map

- [PlainbaseConfig.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/PlainbaseConfig.kt) retains the
  constructor, derived paths, and temporary compatibility delegates; it no longer owns inspection or warning bodies.
- [ConfigBootInspector.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/ConfigBootInspector.kt) owns the
  stateless first-only topology path, complete refusal projection, and ordered filesystem warning projection.
- [ConfigSource.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/ConfigSource.kt),
  [StorageConfig.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/StorageConfig.kt),
  [RootsConfig.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/RootsConfig.kt),
  [GitConfig.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/GitConfig.kt), and
  [AuthConfig.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/AuthConfig.kt) own the normalized values.
- [ConfigValuePolicy.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/ConfigValuePolicy.kt) owns pure
  value derivations; [ConfigLoader.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/ConfigLoader.kt)
  owns file/candidate selection; [ConfigDecoder.kt](../../server/src/main/kotlin/com/plainbase/frameworks/config/ConfigDecoder.kt)
  owns typed decoding and the private roots parser.
- [ConfigLoadingCompatibilityNativeTest.kt](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ConfigLoadingCompatibilityNativeTest.kt)
  contains the compatibility cases. The native source set is folded into the JVM `test` task and is also compiled into the
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
- [ConfigBootInspectorFreshnessTest.kt](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/ConfigBootInspectorFreshnessTest.kt)
  has four filesystem-freshness/candidate-isolation cases and two ordered-warning cases; JVM execution alone is not native-image evidence.

## Plan04 Stage 3 implementation ledger

The Stage 3 continuation moved the two approved internal checkpoints without changing construction, `copy`, loader
selection, refusal wording, warning order, or the candidate-before-baseline gate. `ConfigBootInspector` is deliberately
stateless and read-only: no cache, write, logger, DI, history, lock, availability, or loader operation is present. The
four cache mutations, one category swap, and four source-guard mutations below were temporary RED probes and were
restored before the final GREEN checks. Checkpoint unions used `:server:test --rerun` with the affected boot, CLI,
config, freshness, and server classes: 92 tests and 255 tests, each with zero failures/errors.
The final affected union used `ServerRunTest`, `ServerBootCliContractTest`, `BootGateOrderingTest`,
`ConfigBootInspectorFreshnessTest`, `ConfigSeparationArchitectureTest`, `RootWiringArchitectureTest`,
`CliBootGateArchitectureTest`, and `BootRefusalLedgerTest`: 69 tests/8 suites, zero failures/errors, `BUILD SUCCESSFUL`.
Each cache/category/source mutation compiled successfully, failed at its intended assertion, and passed after restoration;
the detailed logs are local records only. All results are JVM evidence; native gates remain parent-owned.

Those historical counts summarize local runs whose detailed logs are not published with this report; they cannot be
independently audited from the report alone. The committed tests can be rerun through the Gradle commands above.

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

This is a hand-enumerated inventory relevant to the configuration split. The earlier rows retain their baseline anchors;
the Stage 3 owner rows below are updated against the landed source. It is a source-grounded ledger, not an automated
completeness proof.

| Current declarations | Intended owner after later chunks | Current callers / compatibility obligation |
| --- | --- | --- |
| `PlainbaseConfig` constructor: `contentDir`, `dataDir`, `host`, `port`, `maxWriteBodyBytes`, `maxAssetBytes`, `git`, `auth`, `storage`, `contentDirSource`, `roots` (38–76) | `PlainbaseConfig.kt` | Application, CLI, runtime root/history inputs, transport, gates, and direct constructor/copy tests; preserve defaults and copy semantics. |
| `appDatabasePath`, `managedRootsPath`, `searchDatabasePath`, `mainContentRoot` (79, 87, 94, 565) | `PlainbaseConfig.kt` | Runtime and CLI path consumers; pure value helpers remain value helpers. |
| `VERSION`, `DEFAULT_PORT`, `MANAGED_ROOTS_FILE`, `DEFAULT_HOST`, `DEFAULT_MAX_WRITE_BODY_BYTES`, `DEFAULT_MAX_ASSET_BYTES`, `DEFAULT_GIT_AUTHOR_NAME`, `DEFAULT_GIT_AUTHOR_EMAIL`, `DEFAULT_PROXY_IDENTITY_HEADER`, `DEFAULT_S3_REGION`, `DEFAULT_S3_POLL_SECONDS` (570–611) | `PlainbaseConfig` companion | Existing defaults and build-info consumers; preserve values and visibility. |
| `ConfigBootInspector.requireContentDir`, `bootRefusals`, `topologyRefusals`, `objectKeyRefusals`, `legacyRefusals`, `explicitRootRefusals` | `ConfigBootInspector.kt` | Application, reindex, config tests, boot-gate tests, and native tests; preserve complete-vs-first behavior. |
| `DataDirFault`, `dataDirFault`, `dataDirComparable`, `dataDirDeclared`, `comparableRootPath` | `ConfigBootInspector.kt` private | Topology and containment checks; retain declared/canonical distinctions and no cache. |
| `PrimaryFault`, `primaryFault`, `canonicalRootPathOrNull`, `bestEffortCanonical` | `ConfigBootInspector.kt` private | Topology and warning callers; preserve ancestor fallback and permission handling. |
| `ConfigValuePolicy.storageWarnings`, `ignoredContentDirWarning`, `editableGlobWarnings` | `ConfigValuePolicy.kt` | Application and inspector/config tests; preserve warning timing, exact text, and direct-commit glob behavior. |
| `ConfigBootInspector.rootsWarnings`, `dataDirContainmentWarnings`, `managedRootsBackupWarning` | `ConfigBootInspector.kt` | Application, CLI, boot and native tests; preserve containment → backup → explicit guard → ignored-content → unavailable-extra → glob-trap ordering. |
| `bindGuardRefusal`, `isNonLoopbackBind`, `secureCookie`, `mcpHostAllowlist`, `mcpOriginAllowlist`, `MCP_LOOPBACK_HOSTS` (490–549, 605) | `TransportSecurityPolicy.kt` / internal `TransportSecurityValues` | Transport, Ktor, REST, gate, bind, secure-context, and config tests; preserve exactly these five derived fields and no credential-bearing output. |
| `agentDirectCommitGlobs` (556–558) | `ConfigValuePolicy.kt` | REST module, direct-commit tests, and config consumers; retain rooted glob derivation. |
| `fromEnv`, `dataDirFrom`, `fromSources`, `fromEnvAndFile`, `fromEnvAndCandidateRoots`, `loadForCommand` (637, 646, 658, 767, 783, 796) | `ConfigLoader.kt` | Application, CLI, native spike, config/root/CLI tests; preserve names, defaults, and exception boundaries until the closeout chunk. |
| `loadManagedRoots`, `damagedRootsMessage`, `parseIfRegularFile`, `parseCandidate` (688, 723, 739, 754) | `ConfigLoader.kt` private | One real/candidate pipeline; null candidate remains empty config and does not observe the live backup. |
| `build`, `contentDirSource`, `positiveSize`, `buildGit`, `buildAuth`, `buildStorage` (817–957) | `ConfigDecoder.kt` | Loader-only decoder path; preserve evaluation order, env-only storage credentials, and proxy-secret file fallback. |
| `MISSING_S3_CREDENTIALS_MESSAGE` | `StorageConfig.kt` internal | One diagnostic shared by decoding and `ConfigBootInspector` object-mode refusal inspection. |
| `OBJECT_STORAGE_KEYS` (623) | `ConfigDecoder.kt` private | Storage diagnostics; list only existing permitted key names. |
| `buildRoots`, `parseRootBlock`, `parseRoot`, `requireCoherentMainHistory`, `parseHistoryMode` (983–1159) | File-private `RootsConfigParser` in `ConfigDecoder.kt` | Root/config/native tests; preserve per-file ordering and history coherence. |
| `requireTreePathPrefix`, `requireParseableCidrs`, `requireParseableGlobs`, `mainDirectCommitGlobs`, `buildDirectCommitGlobsByRoot` (1245–1341) | `ConfigDecoder.kt` private | Decoder cross-field validation; preserve the bare-primary-name branch and avoid duplicate parsers. |
| `Map.longStrict`, `Map.positiveLongStrict`, `Long.toIntInRange`, `Map.boolStrict` (1344–1373); `String.toCommaList`; `Config.stringOrNull`, `intOrNull`, `longOrNull`, `stringListOrNull`, `boolStrict` (1378–1402) | `ConfigDecoder.kt` private | One typed decode pipeline; do not lazily skip currently eager getters. |
| `isAbsoluteHttpUrl`, `isHttpsUrl` (1220, 1233) | `ConfigValuePolicy.kt` internal | Decoder and S3 smoke command; URI parsing only, no DNS or network client. |
| `ConfigSource` with `DEFAULT`, `FILE`, `ENV` (1405) | `ConfigSource.kt` | Loader, decoder, value, inspection, and config-test callers; preserve provenance. |
| `StorageBackend` and parser; `StorageConfig` fields `backend`, `endpoint`, `bucket`, `region`, `prefix`, `pathStyle`, `pollSeconds`, `accessKeyId`, `secretAccessKey`, `ignoredObjectKeys` (1417–1465) | `StorageConfig.kt` | Runtime/storage and decoder callers; retain enum grammar and no secret debug output. |
| `RootsOrigin`; `RootsConfig.list`, `origin`, `primaryDeclared`, `managed`, `primary`, `extras`, `of`, `synthesized` (1472–1562) | `RootsConfig.kt` | Root registry, CLI, decoder, root/boot/native tests; primary access never reorders the list. |
| `GitConfig.enabled`, `authorName`, `authorEmail` (1566–1570) | `GitConfig.kt` | Prepared history selection and config tests; retain the tri-state. |
| `AuthMode` and parser; `AuthConfig.mode`, `trustedProxyCidrs`, `insecureHttp`, `agentDirectCommitGlobs`, `agentDirectCommitGlobsByRoot`, `proxySecret`, `proxyIdentityHeader`, `mcpAllowedHosts`, `mcpAllowedOrigins` (1580–1649) | `AuthConfig.kt` | Transport, decoder, direct-commit, and config callers; preserve enum grammar and defaults. |
| `RemoteAddress.isLoopbackAddress`, `isNonLoopbackBind`, `isInAnyCidr`, `forwardedProtoIsHttps`, `isParseableCidr`; private `isLoopbackBindLiteral`, `stripPort`, `parseNumericLiteral`, `isValidPort`, `isAsciiLiteralCharacter`, `isStrictIpv4`, `matchesCidr`, `parseCidr`, `sharesPrefix`; `ParsedCidr.network`, `ParsedCidr.prefix`; constants `BITS_PER_BYTE`, `IPV4_OCTET_COUNT`, `MAX_IPV4_OCTET_DIGITS`, `MAX_IPV4_OCTET` in `frameworks/net/RemoteAddress.kt` | `frameworks/net/RemoteAddress.kt` | Config policy, principal, secure-context, CSRF, and relocated address tests; Stage1 moves the complete no-DNS-corrected helper without changing its bodies or public methods. |
| `ManagedRootsFile.BACKUP_SUFFIX`, `HEADER`, `serialize`, `writeAtomically`, `verifyLanded`, `copyPreservingPrevious`, `delete`, `hoconQuote`, and logger | Existing `ManagedRootsFile` adapter | Stage 0 does not add the no-follow backup deletion guard or alter I/O error channels. |

## Documentation and source guards

Stage 3 closed the moved-owner and source-guard obligations for this checkpoint. `PlainbaseConfig` now has only
compatibility delegates for the moved surfaces; `ConfigBootInspector` owns the filesystem/topology probes and
`ConfigValuePolicy` owns pure warning derivations. `FileWatcher`, boot, CLI, and test KDoc/comments now name the landed
owners rather than the removed `validateExplicitRoots` implementation. The remaining Stage 4 obligation is deliberate:
remove temporary forwarders only after all compatibility callers and their KDoc links are closed.

- [`RootWiringArchitectureTest.kt`](../../server/src/test/kotlin/com/plainbase/RootWiringArchitectureTest.kt) now
  allows exactly one `RootName.PRIMARY` comparison in `ConfigBootInspector`, three in `RootsConfig`, two in
  `ConfigDecoder`, and one in `RootCommand`; `PlainbaseConfig` has none. The Tier-3 `isPrimary` ledger remains exact.
- [`CliBootGateArchitectureTest.kt`](../../server/src/test/kotlin/com/plainbase/CliBootGateArchitectureTest.kt) is
  non-vacuous for the inspector and permits only `ConfigBootInspector.rootsWarnings(candidate)` in `RootCommand`.
  `BootRefusalLedgerTest` retains the shared topology/bind/per-root and loader ceilings.
- [`ConfigSeparationArchitectureTest.kt`](../../server/src/test/kotlin/com/plainbase/ConfigSeparationArchitectureTest.kt)
  guards pure owners against filesystem effects, requires actual inspector read probes, and rejects loader, wiring,
  logger, history, availability, lock, database, process, and mutation operations in the inspector.
- The native relocation set remains [`HoconParseNativeTest.kt`](../../server/src/nativeTest/kotlin/com/plainbase/frameworks/config/HoconParseNativeTest.kt),
  `RootCommandNativeTest`, `RootCommandNativeHistoryTest`, `RootsLockNativeTest`, and the existing config and boot
  native seams. Stage 0 does not move or duplicate these tests.
- Stage 0b adds the JVM-only `RemoteAddressNoDnsTest` provider/launcher and the execution-time
  `plainbase.test.childRuntimeClasspath` Gradle property sourced from `test.runtimeClasspath`, with only a temporary
  service descriptor and a bounded 30-second process/drain/cleanup proof. It also adds the lean native literal test;
  Stage 1 relocates that test with the corrected parser.

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
| C04-08 topology | `ConfigBootInspector` owns `explicitRootRefusals`, `comparableRootPath`, `bestEffortCanonical`, and the shared `Files.isDirectory`/readable/executable/`toRealPath` probes. | `RootsValidationTest` retains duplicate-symlink, aliased `DATA_DIR`, nonexistent-extra, and permission-channel assertions; `ConfigBootInspectorFreshnessTest` adds same-config canonical retargeting and candidate/baseline refusal isolation. Direct calls preserve the exact refusal keys/messages and declared/canonical fallback distinctions. | Observed: checkpoint 2 passed all named topology/config suites and the six freshness cases in the JVM-folded `:server:test`; this is not native-image execution. |
| C04-09 freshness | `ConfigBootInspector.rootsWarnings(config)` and `bootRefusals(config)` recompute from the retained config and live filesystem on every call; no cache or reload is involved. | `ConfigBootInspectorFreshnessTest` asserts absent/create/remove extra, backup add/remove, canonical retargeting, and candidate → baseline → candidate complete refusal/warning isolation. Four temporary real-cache mutations (identity/global warning and identity/global refusal) each produced assertion RED after successful compilation and restored GREEN. | Observed: six direct-inspector tests passed in checkpoint 1 and checkpoint 2; all four cache probes compiled, failed at intended assertions, and passed after restoration. JVM-folded native-tag execution is not native-image execution. |
| C04-10 diagnostics | `Application` consumes `ConfigBootInspector` refusal/warning projections and `ConfigValuePolicy.storageWarnings` at the existing observation points; `RootCommand` checks candidate before baseline and emits warning-only inspector results afterward. | `ServerRunTest` has both synthesized and explicit layouts and asserts the exact storage warning → ordered roots warning list → bind error channel/order, plus context close; `BootGateTest`, `BootGateOrderingTest`, and `BootRefusalLedgerTest` retain refusal kind/rank/partition coverage. | Observed: checkpoint 2 passed the server/CLI/config union, including 20 `ServerRunTest` methods; the warning-category swap compiled, failed at its exact warning-list assertion, and passed after restoration. Native-tagged JVM execution is not native-image execution. |
| C04-11 security | `TransportSecurityPolicy.derive` now owns the five bind/cookie/MCP result fields; `PlainbaseConfig` retains temporary delegates. `RemoteAddress` and its private parser helpers live in `frameworks/net/RemoteAddress.kt`; Ktor request consumers import that helper directly. | `BindGuardTest` pins exact proxy-completeness ordering (including `insecureHttp=true`), absent/blank secret and CIDR cases, off/builtin/proxy behavior, and nonloopback insecure-cookie behavior. `PlainbaseConfigTest` pins exact ordered defaults and duplicate-preserving direct overrides. `TransportSettingsTest` pins all five projection fields. `AddressParsingTest` and `RemoteAddressNativeTest` retain the moved suites; `SecureContextTest` keeps the Ktor predicate and explicit insecure-config case. | Observed: Stage1 focused JVM suites passed all named assertions; native image execution remains parent-owned. |
| C04-12 effects/error funnel | `loadForCommand` retains the narrow IAE/HOCON funnel; Stage 3 adds bounded pure-owner and inspector read-only guards plus exact primary/CLI ownership ledgers. `PlainbaseConfig` remains a delegate boundary until Stage 4. | Existing `PlainbaseConfigTest` load-for-command and `BootGatePurityTest` assertions remain; the four Stage 3 source probes add a real filesystem operation to a pure owner, a real delete to the inspector, an unledgered primary comparison, and a direct refusal call in `RootCommand`. Each RED followed successful compilation and restored GREEN. | Observed: checkpoint 1 and checkpoint 2 architecture suites passed on final source; each guard mutation failed at its intended assertion after successful compilation and passed after restoration. All are JVM evidence, not native-image execution. |

### Stage2 revision1 C04 reconciliation

The rows below are the execution record for the revision1 follow-through. They supplement the historical rows above; the
remaining Stage3/4 obligations are deliberately left open. `BASELINE-RETRO` means runs of the copied relevant
fixtures against the isolated `/tmp/plainbase-stage2-baseline-9L5tcs` extraction of accepted source
`101a64ed933258ec4ae410cc0972ac5249650ad8`. It was retrospective, not pre-extraction evidence. That run completed 91
tests with one failure: the copied managed-file assertion omitted the unchanged source's full refusal/remedies suffix.
The original source body was inspected, the exact suffix was restored to the current assertion, and the current test is
GREEN. The parent then completed the paired storage/root and port/auth controls, checked both eager file-glob spellings,
asserted root identity through copy, pinned all three semantic diagnostics, and migrated the focused loader calls.
The final identical assertions passed all 91 tests on both accepted baseline and current source (zero failures/errors/skips);
current lint and detekt passed. Only ConfigLoader's owner spelling was adapted in baseline test copies. No new production
code was copied into the baseline. The preceding 341-test current union remains a separate, earlier run.

| Row | Actual production trace | Exact test/assertion and observed result | Remaining obligation |
| --- | --- | --- | --- |
| C04-01 loading | `ConfigLoader.fromSources` derives `DATA_DIR`, reads operator `plainbase.conf`, then selects managed roots before `ConfigDecoder.decode`. | `env-only loading ignores malformed config files and backup evidence` asserts env paths, defaults, `ENV`/`SYNTHESIZED` provenance, primary `docs`, and unchanged bytes; `paired invalid fromEnv values preserve storage then data directory before port and auth` asserts the real NUL `InvalidPathException` after storage is made valid. `BASELINE-RETRO` passed these assertions; current union passed. | Stage4 can remove remaining `PlainbaseConfig` loader delegates after all callers migrate. |
| C04-02 precedence/strictness | `ConfigDecoder.decode` eagerly reads typed `contentDir`, `insecure`, storage, roots/history, port, and auth; auth validates mode, CIDRs, then globs. | `loader preserves contentDir, insecure, storage, roots/history, port, then auth failure order` and `auth mode precedes CIDRs, which precede main direct-commit globs` assert exact classes/messages. `object mode file poll seconds zero and negative values use the exact default` asserts both exact default values. `BASELINE-RETRO` passed; current `PlainbaseConfigTest`/compatibility suites passed. | Stage3 owns the larger topology/warning matrix; no generic validation framework is introduced. |
| C04-03 HOCON/process environment | `ConfigLoader.parseIfRegularFile` and `parseCandidate` resolve HOCON; `ConfigDecoder` performs eager getters after resolution. | `operator HOCON resolves a real process environment value before the injected map` asserts `${PATH}` uses the real process value; `an env main-glob override still eagerly reads a wrong-typed file glob spelling` asserts exact `WrongType` class/message. `BASELINE-RETRO` passed both; current union passed. | Keep process-global environment read characterization and exact HOCON exception distinctions through Stage4 caller closeout. |
| C04-04 provenance/snapshot | File-private `RootsConfigParser` in ConfigDecoder.kt preserves per-file origin and `ConfigLoader` returns one decoded snapshot per load. | `a retained config snapshot does not observe a later managed roots replacement` asserts retained `EXPLICIT` roots versus a fresh load; `a path carrying a non-ASCII character, a quote and a backslash round-trips through the REAL loader` asserts normalized path/provenance. `BASELINE-RETRO` passed; current union passed. | Stage3 still owns the complete inspection freshness matrix. |
| C04-05 root merge/order | `RootsConfigParser` parses operator and managed blocks independently, then merges file 1 before file 2 without hoisting primary. | `the in-memory candidate and the on-disk file parse to the SAME roots - the gate's load-bearing assumption` now writes an operator root plus managed Unicode/quoted content and asserts full `PlainbaseConfig` equality, ordered roots, and explicit origin; existing `ManagedRootsConfigTest`, `RootsConfigTest`, and `RootRankStabilityTest` retain ordering/rank assertions. `BASELINE-RETRO` passed the copied assertions; current union passed. | Stage3 owns the remaining cross-file refusal/topology matrix. |
| C04-06 candidate/error parity | `ConfigLoader.fromEnvAndCandidateRoots` takes candidate text through the same decoder; live managed parsing wraps only managed-file HOCON failures. | `semantically invalid candidate bytes and the same managed file refuse identically` retains semantic exception parity. `malformed candidate syntax stays a parse error while malformed managed syntax is wrapped` asserts candidate `ConfigException.Parse`, exact `String: 1: expecting a close parentheses ')' here, not: end of file`, managed cause class, and the full origin-aware wrapped message. `BASELINE-RETRO` exposed the missing suffix in the copied expectation; current source preserves the baseline body and passes exact assertion. | Keep malformed-candidate versus wrapped-managed distinction; do not claim substring checks or suite totals prove message parity. |
| C04-07 entry/backup matrix | `ConfigLoader.loadManagedRoots` uses regular-file follow semantics and checks backup evidence before synthesizing; `ManagedRootsFile` retains no-follow entry controls. | `operator and managed entries retain regular-file follow behavior across NIO entry kinds` independently checks directory, regular symlink, dangling symlink, and target preconditions; `loader treats a regular backup as damage beside missing and nonregular managed entries` checks regular backup bytes and live target/link preservation for missing, directory, and dangling-link live entries. `BASELINE-RETRO` passed these copied assertions; current union passed. Permission-denied read remains source-reviewed unsupported because no independent denied-read precondition was established. | Stage3 may close permission channels only with a real denied read and cleanup; never resurrect the invalid unlink-permission fixture. |
| C04-08 topology | `PlainbaseConfig` still owns filesystem inspection; Stage2 only supplies normalized roots to it. | Current union passed the existing named root-parser/boot consumers; no new Stage2 test claims the full declared/canonical/permission matrix. `BASELINE-RETRO` did not run topology suites. | Stage3 owns declared, canonical, aliased, nonexistent, permission, and warning-channel coverage. |
| C04-09 freshness | Loader snapshots parsed files; inspection remains on-demand in the retained owner. | `a retained config snapshot does not observe a later managed roots replacement` is the new loader-level snapshot assertion and passed in `BASELINE-RETRO` and current. | Stage3 owns same-config filesystem mutation, memoization RED controls, and inspector freshness. |
| C04-10 diagnostics/order | Boot gate and warning consumers remain in their existing owners; Stage2 preserves loader/decoder error precedence. | Current union passed existing `BootGateTest`, `BootGateOrderingTest`, and `BootRefusalLedgerTest` assertions. These are not a new emitted-warning-order proof; native-tagged suites were JVM folds, not in-image runs. `BASELINE-RETRO` did not run the boot suites. | Stage3 owns complete warning channel/order characterization. |
| C04-11 security/no-DNS | `ConfigValuePolicy` owns pure URL/glob derivations; `RemoteAddress` remains the strict literal parser; no resolver operation is in either pure-owner guard. | Current union passed the affected `BindGuardTest`, `SecureContextTest`, `TransportSettingsTest`, and architecture guards. Revision1 also made the common effect-token guard reject an uncalled `InetAddress.getByName` mutation, with immediate restoration. This is JVM evidence, not a native-image run. | Stage4 owns removal of temporary value delegates and final caller/KDoc closeout; native gate remains parent-owned. |
| C04-12 effects/error funnel | `ConfigLoader.loadForCommand` retains the narrow IAE/HOCON funnel; `ConfigSources` and `ConfigDecoder` calls are guarded across all production Kotlin files. | Corrected architecture guard rejected uncalled external-package `ConfigSources` use and uncalled external `ConfigDecoder.decode` use, then restored GREEN; the common pure-owner guard rejected the uncalled DNS resolver mutation. Existing `loadForCommand` exact prefix/message tests passed in current and `BASELINE-RETRO` behavior fixtures. | Stage4 owns removed forwarders, final caller inventory, and unresolved KDoc links; guards remain operation/call-site checks, not a whole-program purity proof. |

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
| C04-07-entry-matrix | Stage 2 loader | Characterize nonregular operator/managed entries and readable/unreadable preconditions; record unsupported permission fixtures. |
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

This historical Stage 0 record does not claim that Plan04 is complete. Conservative backup deletion is shipped in
Stage 0c below; later chunks still own policy/net separation, values/loader/decoder relocation, inspection/freshness
and warning order, and caller/documentation closeout.

## C04-11 Stage0b measured address correction

Stage0b was executed with the official macOS toolchain: `openjdk version "25.0.4.1"`, GraalVM CE
`25.3.4.1+1.1`, `--max-workers=2`, and `--console=plain`. The six fresh JVM lanes use a temporary
`InetAddressResolverProvider`; the provider's positive control attempted one controlled lookup before
each lane, then the production rows were measured after resetting the counter. The table summarizes
these observations; detailed row-level receipts are retained with the local verification records.

| Lane | Measured correction | Public verdict and resolver attempts |
| --- | --- | --- |
| bind | `dead.beef` no longer looks like loopback; IPv4 zones and malformed ports are exposed; Unicode lookup is eliminated without changing exposed classification | `dead.beef` `false/1 → true/0`, `127.0.0.1%lo0` `false/0 → true/0`, `127.1` `false/0 → true/0`, `127.0.0.1:abc` `false/0 → true/0`, `١٢٧.0.0.1` `true/1 → true/0` |
| remote | dotted-hex, abbreviated/zero-padded IPv4, IPv4 zones, bracketed aliases and invalid ports reject | `dead.beef` `true/1 → false/0`, `127.1` `true/0 → false/0`, `127.0.0.1%lo0` `true/0 → false/0`; canonical loopback remains `true/0` |
| remote CIDR | runtime matching now shares strict literal parsing, including IPv4-zone rejection | `dead.beef` `true/1 → false/0`, `127.0.0.1%lo0` `true/0 → false/0`; mapped loopback `true/0 → true/0` |
| network CIDR | malformed numeric network entries and IPv4 zones no longer match | `dead.beef/8` `true/1 → false/0`, `127.0.0.0%lo0/8` `true/0 → false/0`; `10.0.0.0/8` `true/0 → true/0` |
| parse CIDR | config parser rejects strict/Unicode/dotted-hex malformed networks and IPv4 zones | `dead.beef/8` `false/0 → false/0`, `127.0.0.0%lo0/8` `false/0 → false/0`; Unicode `false/1 → false/0` |
| config | explicit `DATA_DIR` and `CONTENT_DIR` were used; malformed env entries fail fast | `dead.beef/8` `iae:PLAINBASE_TRUSTED_PROXY/0` both, `127.0.0.0%lo0/8` same, mapped padded `loaded/0 → iae:PLAINBASE_TRUSTED_PROXY/0`; Unicode attempt `1 → 0` |

The complete measured matrix is retained in the local Stage0b evidence with completion counts
`bind/36`, `remote/69`, `remote-cidr/38`, `network-cidr/39`, `parse-cidr/38`, and `config/22`.
`JVM-ORIGINAL` and raw-lookup mutation receipts show the expected RED behavior; the final `JVM-GREEN`
suites passed, including the folded native-test class and named downstream consumers. The source guard
rejects `getByName` in the temporary mutation and passes on final source. These observations come from
JVM runs; native classification is checked separately by the native gate.

## C04-06-last-root-delete / C04-07-backup-delete: Stage0c backup-entry preservation

Stage0c was implemented on the accepted predecessor `7d0a76b84b1f18db88139ed57c8dc545215fad3e` on
`codex/backend-04-configuration-separation`. The working tree remained unstaged and uncommitted. The production
change is limited to [`ManagedRootsFile.kt`](../../server/src/main/kotlin/com/plainbase/frameworks/config/ManagedRootsFile.kt),
[`PlainbaseConfig.kt`](../../server/src/main/kotlin/com/plainbase/frameworks/config/PlainbaseConfig.kt), and
[`RootCommand.kt`](../../server/src/main/kotlin/com/plainbase/frameworks/cli/RootCommand.kt): one shared backup-path
spelling, a no-follow entry guard that catches only inspection `NoSuchFileException`, and a typed last-root CLI error.

The real last-root command RED was captured before the production change. Compilation completed, then the two new
command assertions failed (`37 tests completed, 2 failed`), so this was not a compile-error RED. The captured old
behavior was:

- `RootCommandTest`: exit `0`; stdout contained the existing removed-root, restart and detached-row consequence
  lines; stderr contained the existing backup warning; `liveExistsAfter=false`; and the next real-file load failed
  with the existing `IllegalArgumentException` for missing `roots.conf` beside a regular `.bak`.
- `RootCommandNativeTest`: the same exit/output/deletion/next-load outcome under the JVM-folded native source set.

The final focused command/writer run used the official macOS host toolchain: `openjdk 25.0.4.1` and GraalVM CE
`25.3.4.1+1.1`. Exact environment-prefixed commands and their outputs remain in the local Stage0c receipts; the
tracked table uses portable Gradle command spelling:

The earlier Stage0c focused receipt measured 50 tests (`34 + 3 + 13`) before the native entry matrix was split into
independent regular, directory, symlink-to-regular and dangling-symlink methods. That 50-test result is historical;
the revision-1 run below is the post-split measurement.

| Gate | Portable Gradle command | Observed result |
| --- | --- | --- |
| Focused command and writer tests (revision 1) | `./gradlew :server:test --tests 'com.plainbase.frameworks.cli.RootCommandTest' --tests 'com.plainbase.frameworks.cli.RootCommandNativeTest' --tests 'com.plainbase.frameworks.config.ManagedRootsFileNativeTest' --rerun-tasks --max-workers=2 --console=plain` | `BUILD SUCCESSFUL in 25s`; 34 + 3 + 16 tests, with 0 skipped, failures, or errors. |
| Listed regression controls | `./gradlew :server:test --tests 'com.plainbase.frameworks.config.ManagedRootsFileNativeTest' --tests 'com.plainbase.frameworks.cli.RootCommandNativeHistoryTest' --tests 'com.plainbase.frameworks.cli.RootsLockNativeTest' --tests 'com.plainbase.frameworks.config.ManagedRootsConfigTest' --tests 'com.plainbase.frameworks.config.RootRankStabilityTest' --tests 'com.plainbase.CliBootGateArchitectureTest' --tests 'com.plainbase.BootRefusalLedgerTest' --rerun-tasks --max-workers=2 --console=plain` | `BUILD SUCCESSFUL in 31s`; every requested test method reported `PASSED`. |
| Root lintKotlin (revision 1) | `./gradlew lintKotlin --max-workers=2 --console=plain` | `BUILD SUCCESSFUL in 12s`; root `lintKotlin` passed. |

The command GREEN assertions retain the candidate warning before the exact final refusal line, empty stdout, unchanged
live and backup bytes, and the loaded notes root/path/managed provenance. The no-backup control compares the typed
`PlainbaseConfig.fromEnvAndCandidateRoots(null, env)` result with the next real-file load. The remaining-managed JVM
control compares the serialized survivor candidate with the real loader and retains the backup warning; the native
operator-file control has a second managed survivor and still compares the operator configuration bytes exactly.

The native writer matrix covers regular, directory, symlink-to-regular and dangling-symlink backup entries with
`BasicFileAttributes`/`NOFOLLOW_LINKS` preconditions. It preserves symlink target spelling and target bytes, directory
contents, and the live file. Loader/warning asymmetry is intentional and measured: regular and symlink-to-regular
entries warn because the existing classification follows regular-file semantics; directories and dangling symlinks do
not warn. Each still refuses deletion. An absent live file with a backup refuses, while a missing live file with no
backup remains idempotent. A nonempty live directory still raises `DirectoryNotEmptyException`.

Non-`NoSuchFileException` inspection-error propagation is source-reviewed at
[`ManagedRootsFile.kt`](../../server/src/main/kotlin/com/plainbase/frameworks/config/ManagedRootsFile.kt#L202), not
runtime-proven with a permission fixture: denied parent traversal would also prevent an unguarded unlink, and no
filesystem mock/provider framework was introduced. `RootCommand.run` retains its unrelated-failure logger/exit path,
and the remove branch catches only `ManagedRootsBackupPresentException`. The parent-owned native image,
`nativeCompile`, and spike gates were not duplicated here; no unsupported capability was encountered in the focused
JVM-folded run, including both symlink fixtures.
