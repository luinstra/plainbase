# Unreleased Tinted examples for Plainbase

These offline examples demonstrate the Plainbase candidate theme contract.
They are tooling and documentation data outside the application bundle. They
are not a runtime theme catalog.

The production Mustache template emits 18 public `--pb-theme-*` inputs in one
exclusive mode scope. The six omitted inputs (`--pb-theme-card`,
`--pb-theme-card-foreground`, `--pb-theme-border`, `--pb-theme-font-sans`,
`--pb-theme-font-mono`, `--pb-theme-radius`) use generic defaults. The complete
mapping and Base24 fallback table are in
[roster.json](../../frontend/src/__tests__/fixtures/theme/roster.json).
Base24 enriches eight roles with five bright slots; its unused extra slots
are intentionally not emitted.

Every installable adaptation must declare `variant: light` or `variant: dark`.
The pinned builder defaults an absent variant to dark, so the independent YAML
preflight proves source presence. It also rejects noncanonical declared slugs,
Base16 extended slots, numeric palette values and shorthand hex. The builder
remains the palette-completeness and malformed-hex witness.

## Reproduction

Run from the repository root:

```sh
./gradlew :frontend:tintedTemplateCheck
```

Review the captured CSS under `frontend/build/tinted/results/examples/`, then
copy it manually to `contrib/tinted/generated/`. Tests never update the checked-in
examples or provenance. Gradle's managed Node/npm toolchain owns all checks.

The builder is `tinted-builder-rust v0.21.0`, SHA-256 pinned and cached by Gradle
for these hosts:

| Host | Target |
| --- | --- |
| macOS arm64 | `aarch64-apple-darwin` |
| Linux x64 | `x86_64-unknown-linux-musl` |
| Linux arm64 | `aarch64-unknown-linux-gnu` |

Hosts outside these three pins, including Intel macOS, cannot run
`./gradlew build`: the gate fails there by design. Local acceptance uses macOS
arm64; Linux x64 is the CI `build-test` witness. No arm64 CI job was added, and
the Linux arm64 binary's glibc floor remains unverified.

The builder's tool license is GPL-3.0-only. It is executed as a separate process
at build time, never copied, linked, bundled or redistributed. No builder source
is included here.

## Provenance and adaptation

Upstream is [tinted-theming/schemes](https://github.com/tinted-theming/schemes),
at commit `3ac91137e879502c442ab89ba0f1a98077d0da9d` on `spec-0.11`.
Pinned upstream copies remain byte-identical. Only palette values, adapted
name and canonical slug differ in the adapted sources.
[sources.json](sources/sources.json) records exact hashes, last file commits,
authors, paths and every palette adjustment with informational row names.

| Example | Upstream | Author |
| --- | --- | --- |
| Light | `base16/gruvbox-light-hard.yaml` | Dawid Kurek and morhetz |
| Dark | `base16/dracula.yaml` | clach04 |
| Genuine Base24 | `base24/dracula.yaml` | clach04 |

The `-adapted` filenames identify palette adjustments for Plainbase's existing
contrast rows. Every adapted example passes all 156 rows, including alpha
composition on layered surfaces; raw upstream mapping has no contrast claim.
The formulas, assertion floors and slot mapping are unchanged.

In both Dracula sources, upstream `base04` (`9ea8c7`) is a Tinted adaptation,
marked "NOT a Dracula color!" upstream. The original Dracula comment hue is
`base03`, which the template does not map. No contrast claim is made about that
original comment hue; these adapted examples further adjust `base04`.

The Gruvbox light `base05` change (`504945` to `110f0e`) is forced, not tuned:
the frozen `text-faint` mix of foreground and background must clear 4.5 on
`surface-chrome` (`base01`), and only a near-black foreground does. Expect the
same for light schemes whose `base01` is tinted.

## Scheme license notice

The upstream schemes are MIT licensed. Their notice follows:

Copyright (c) 2022 Tinted Theming (https://github.com/tinted-theming)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
