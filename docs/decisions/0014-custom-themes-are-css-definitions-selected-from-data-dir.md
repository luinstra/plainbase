# 14. Custom themes are complete CSS definitions selected from DATA_DIR, over one public input contract

- **Status:** Accepted
- **Date:** 2026-10-16
- **Amended:** 2026-10-17
- **Deciders:** luinstra (theme foundation rulings, 2026-10-11 to 2026-10-16)
- **Context:** Give operators a theme contract that separates deployed definitions from application
  implementation details and keeps theme selection beside the rest of their configuration.

## Context

The theme contract needs a deployed format, a selection mechanism and a compatibility boundary.
The choice also needs to distinguish a complete definition from a partial override and make their
failure behavior clear. [The theming guide](../theming.md) is the operator reference.

## Decision

**Format and contract.** Architecture policy: deploy semantic CSS definitions, authored as CSS or
converted from Tinted Base16 or Base24 schemes. Plainbase converts selected `.yaml` and `.yml` schemes
with its own code; the Tinted template remains the reference mapping and the oracle for that output.
Operator configuration remains HOCON, as chosen in [ADR-0009](0009-hocon-config-file-not-yaml.md).
The version 1 contract has 24 public `--pb-theme-*` inputs, four required. The generic layer supplies
defaults for the other inputs and derives private roles. Architecture policy: components consume
those private roles rather than embedding a palette.

**Selection and overrides.** `theme.light` and `theme.dark` in the operator's `DATA_DIR/plainbase.conf`
select CSS definitions or scheme sources for their respective modes. A selected CSS file, or CSS
converted from a selected scheme, replaces the bundled definition for that mode.
The keys have no dedicated environment settings.
Selections name files directly in `DATA_DIR` or directly in `DATA_DIR/themes`, using the validated
name grammar. `custom.css` and `themes/custom.css` are reserved, case-insensitively.
Light and dark definitions have exclusive root scopes.
The optional, fixed `DATA_DIR/custom.css` supplies partial overrides in both modes.

The page links the app stylesheet, generic layer, light definition, dark definition and `custom.css`,
in that order. The last three use the fixed `/_plainbase/theme-light.css`,
`/_plainbase/theme-dark.css` and `/_plainbase/custom.css` endpoints. The theme links are injected into
the page head at build time; application modules import no stylesheet. Architecture policy: use
plain links and the browser's stylesheet handling rather than a script loader.

**Reads and failures.** Stylesheet reads are capped at 262144 bytes, require valid UTF-8 and remove
one leading byte order mark. An unreadable selected file serves the bundled definition for that
mode; an unreadable `custom.css` serves an empty sheet. An override that is missing, empty or contains
only spaces, tabs, line feeds, form feeds or carriage returns is silent, with or without a leading
byte order mark. Other read failures share a warning limit of one per 60 seconds.
An invalid selection value logs one startup warning naming its key and falls back for
that mode, retaining a valid selection for the other mode. Submitted paths and URLs are not echoed
in that warning. A missing embedded light or dark definition is an error naming the resource,
never an empty sheet.

**Trust.** Theme CSS is privileged, public material: writers of `DATA_DIR` control it, and its
endpoints are readable before login. The endpoints accept only GET and use the page's existing
Content Security Policy. Content roots and uploads cannot replace the selected or override theme
bytes. `/_plainbase/` serves only the three fixed stylesheets; every other path inside it returns 404.

**Derivation.** Generic faint and muted text share one color from `--pb-theme-muted-foreground`;
bundled definitions retain private refinements. Generic card text applies in discussion cards,
list cards, evidence and notices, the new-page dialog including its fields, code blocks, diffs,
code in the editor and diagram node labels. Other raised and field surfaces do not use card text;
hover and checked states drawn from the page keep page text. Mermaid's secondary background is
the page and its tertiary background is the card. Generic selection backgrounds preserve the
alpha supplied by `--pb-theme-selection`. Derivation policy: use relative color syntax when deriving
opaque primary channels.

**Compatibility policy.** Renaming, removing or repurposing a public input or a stable `.pb-*` or
`data-pb-*` hook requires a documented versioned migration. Private `--pb-*` roles and generated
classes are unversioned.

## Consequences

- All four theme stylesheets use the same plain link form, so a slow response from any of them can
  delay the interface's first appearance. Browser tests directly verify that delay for the dark
  definition and `custom.css`; the generic layer and light definition are not held in those tests.
- Restart after changing a selection. Selected files and `custom.css` are read again for each GET
  request to their stylesheet endpoints; schemes are converted again, so reload the page after editing their contents.
- A symbolic link or directory at a selected stylesheet path serves the bundled definition. A
  symbolic link or directory at `DATA_DIR/custom.css` is rejected as `NOT_REGULAR` and serves an
  empty sheet, with a warning subject to the read-failure warning limit. A regular selected file
  reached through a symlinked `DATA_DIR` base is accepted. For a `themes/` selection, `DATA_DIR/themes`
  must itself be a real directory; a symbolic link or non-directory there falls back as `NOT_REGULAR`.
  Mounted-volume deployment advice lives in the guide.
- Scheme files follow the CSS read limits, including removal of one leading byte order mark before
  conversion. A conversion failure serves the bundled definition for that mode. Warnings name the
  setting, file name and one of the five scheme reasons, with a line number when known; they never
  include a value or file content and share the read-failure warning limit.
- A declared variant must match the selected mode; otherwise `VARIANT_MISMATCH` causes fallback to
  that mode's bundled definition. A scheme without a variant works in either mode.
- A content root or `CONTENT_DIR` at `DATA_DIR/themes`, in any letter case, refuses to start, and
  `plainbase root add` refuses it. Other root locations inside `DATA_DIR` remain warning-only.
  When `DATA_DIR` is inside a content root, its whole subtree, including `DATA_DIR/themes`, is excluded
  from that root's index, content assets, file watching and page creation.
- Advice: Review contrast for arbitrary palettes, including card foreground against both page and
  card backgrounds. Generic faint and muted text share a color rather than providing a third tier.
- The radius scale has a default `0.25rem` base and seven fixed multipliers. The default base is 4px
  at a 16px root font size; a rem value follows the root font size and a px value gives fixed corners.
- Mermaid receives sRGB conversions from a fixed allowlist of computed color spaces while the
  interface uses native colors. Unsupported computed spaces show diagram source and a status
  message instead of a rendered diagram.
- Architecture policy: the GPL Tinted builder belongs in offline build tooling, outside the
  application distribution. Runtime conversion uses Plainbase's own code, checked against the template's builder output.
