# Contributing to Plainbase

Thanks for your interest! Plainbase is young and small, so the rules below are
short - but they are enforced by CI, so reading them first will save your PR a
round trip.

## Before you start

For anything beyond a typo or small fix, please open an issue first and
describe what you want to change. Plainbase has strong architectural
constraints (below), and a short conversation up front beats a rejected PR.

## Sign your commits (DCO)

Plainbase uses the [Developer Certificate of Origin](https://developercertificate.org/).
By signing off you certify that you wrote the change (or otherwise have the
right to submit it) under the project's Apache-2.0 license. You keep your
copyright.

Add a sign-off to every commit:

```sh
git commit -s
```

which appends a `Signed-off-by: Your Name <you@example.com>` line. PRs with
unsigned commits fail the DCO check and can't merge.

## Ground rules the build enforces

- **Verification floor:** `./gradlew build` must pass (compiles, tests,
  formatting, dependency allowlist). Server changes should also survive the
  native gate: `./gradlew :server:nativeCompile`, then
  `server/build/native/nativeCompile/plainbase spike` (8/8).
- **Server dependencies default to NO.** Every addition must work under
  GraalVM native-image and be recorded via
  `./gradlew :server:writeDependencyAllowlist`. Netty, Jackson, Gson, and
  Exposed are banned outright; the stack is Ktor CIO, kotlinx.serialization,
  and SQLDelight - see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
- **Formatting:** Spotless + ktlint (`./gradlew spotlessApply`), 140-column
  limit. The build fails on formatting drift, so let the tool do the arguing.
- **Commits:** conventional style (`feat:`, `fix:`, `docs:`, `chore:`, ...),
  one logical concern per commit.

## Architecture in one paragraph

Hexagonal: `domain/` holds models, ports, and services and imports no
framework; `frameworks/` holds adapters grouped by technology. Ports are named
naturally (`XxxProvider`, `ContentStore`), implementations as `<Tech><Port>`.
When in doubt, imitate the cleanest surrounding code.

## Licensing

Plainbase is [Apache-2.0](LICENSE). The Plainbase name and logo are covered by
the [trademark policy](TRADEMARK.md).
