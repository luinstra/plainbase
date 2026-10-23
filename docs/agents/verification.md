# Verification

Read before code changes, tests or build-result claims. Use Gradle from the target worktree.
[Development](../DEVELOPMENT.md) covers setup and disposable state; build logic and
[CI](../../.github/workflows/ci.yml) are the executable reference.

## Required code-change gates

1. Run `./gradlew build`: the JVM/JAR floor includes compilation, tests, Kotlin lint and dependency
   allowlist verification. A native-only pass does not replace this floor.
2. For server changes, run `./gradlew :server:nativeTest`, then
   `./gradlew :server:nativeCompile`, then `server/build/native/nativeCompile/plainbase spike`.
   The spike requires 9/9. Keep each shell invocation separate.
3. Run the relevant additional integration/browser/operating checks for the changed contract.

The native gate requires GraalVM CE **25.3.4.1 / JDK 25.0.4.1** on `JAVA_HOME`/`GRAALVM_HOME`;
the application toolchain is Java 25. Use the official `graal-25.3.4.1` archive, and verify the selected
binary. CI pins `setup-graalvm` to `version: 25.3.4.1`, `java-version: 25`. The asdf 25.0.2 entry is a
JVM-only fallback, not a supported native toolchain. Machine-specific install paths are not project defaults.
The universal JAR is the release floor; native artifact failures must be reported separately and
block the native artifact.

## Prove execution, not just discovery

For `nativeTest`, inspect the **in-image JUnit run summary**: tests found and successful must be
nonzero, with zero failures. `nativeTestList` XML is the JVM discovery/listing pass, not proof of
in-image execution. `BUILD SUCCESSFUL`, `SKIPPED`, cached output and missing counts do not establish
a fresh native run. If fresh execution is required and caching obscures it, rerun deliberately with
`--rerun-tasks --console=plain` and retain the unfiltered output. Do not reuse historical test counts.

Similarly, confirm Vitest/browser test summaries actually show the requested files and tests.
A delegate's claimed pass needs its command, worktree/revision, exit result and relevant summary.
Check actual PR checks before describing a PR as mergeable; after merging, inspect the separate
push-triggered `main` workflow when merge delivery is in scope.

## Test design

- Use Kotest/MockK for JVM logic tests. Native tests use kotlin.test/JUnit with `@Tag("native")`,
  following the existing `src/nativeTest` split. Keep Kotest/MockK out of the native classpath.
- Native-tag divergence surfaces by default: NIO edge behavior, process execution, xerial JDBC/JNI,
  charset/Unicode decoding and SecureRandom/crypto. Cover the surface; do not port all pure domain
  tests into the native suite. Keep `#` out of backtick test names (JUnit 6 native ID parsing).
- Freeze exact values for realistic and adversarial inputs; use safety/determinism/normalization
  invariants for benign rare Unicode. Assert cross-platform properties, not APFS-specific detector
  behavior such as inode reuse. Attackers control adversarial inputs, so rarity is no defense.
- When fixing parsers, use a real parser as a test-only differential oracle where appropriate.
  Expand the generator when a review finds an untested class, not just a single golden case.
- Auth changes need enforced-mode coverage (`builtin`/`proxy` as applicable). A smoke run with
  `auth.mode=off` cannot prove authorization. Inspect current CI and fixtures rather than assuming
  their auth mode from old notes.

## Frontend: use Gradle's managed Node toolchain

Never run raw `vitest`, `playwright`, npm, or `node_modules/.bin/*` for project verification.

| Purpose | Command |
| --- | --- |
| One Vitest file | `./gradlew :frontend:vitestFile -PtestFile=src/__tests__/<file>` |
| Full Vitest suite, including token discipline | `./gradlew :frontend:vitestFile` |
| Full Vitest suite plus token discipline | `./gradlew :frontend:npmTest` |
| Type check and production bundle | `./gradlew :frontend:build` |
| Real-server Playwright smoke | `./gradlew :frontend:smokeTest` |

Without `-PtestFile`, `vitestFile` runs the same full suite as `npmTest`, including the token-discipline
test. With a file filter, only matching tests run. `npmTest` always runs; it is never up-to-date cached.
`npmTest` alone does not run the type check that `:frontend:build` provides. The smoke suite is
separate from `build`, downloads Chromium on first use and includes headed caret tests. On headless
Linux use `xvfb-run -a ./gradlew :frontend:smokeTest`.

For discussion auth caret checks (display required):

```sh
./gradlew :frontend:smokeTest -PsmokeArgs='discussions-auth.spec.ts --project=auth-caret' --console=plain
```

For the headless review auth smoke:

```sh
./gradlew :frontend:smokeTest -PsmokeArgs='review.spec.ts --project=auth' --console=plain
```
