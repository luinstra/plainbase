# Plainbase brand assets

A restrained, filesystem-native identity for the GitHub project page and the app web icon:
a teal **slash** before lowercase letters, so the mark reads as a path segment or CLI token
(`/pb`, `/plainbase`) rather than a marketing logo. Sober infrastructure, not a mascot.

## Identity

- **Slash-led path mark.** The slash is the through-line — it carries the filesystem/CLI
  thesis and ties the compact mark (`/pb`) to the wordmark (`/plainbase`).
- **JetBrains Mono Bold**, so the letters read as monospace/terminal and match the product's
  developer surface. The wordmark and the mark share the exact same letterforms.
- **Letters are outlined to paths** in every shipped SVG. Rendering never depends on
  JetBrains Mono (or any font) being installed — the `brand-assets` test enforces that no
  asset contains a live `<text>` element.

## Colors

| Role | Light surfaces | Dark surfaces |
|---|---|---|
| Slash (teal) | `#0f766e` | `#14b8a6` (brightened so it holds on dark) |
| Letters (ink) | `#0f172a` | `#f1f5f9` |
| Icon tile | `#f4f4f5` | — (the tile gives the mark a boundary on white) |

## Asset set

| File | Size | Use |
|---|---|---|
| `plainbase-mark.svg` | 128 viewBox | Canonical `/pb` mark on the gray tile |
| `plainbase-mark.png` | 512×512 | Raster mark for GitHub/social/icon fallback |
| `plainbase-logo.svg` | ~387×73 | Wordmark, dark ink — light backgrounds |
| `plainbase-logo-dark.svg` | ~387×73 | Wordmark, light ink — dark backgrounds |

The web/app copies live under `frontend/public/`: `favicon.svg` (the mark),
`apple-touch-icon.png` (180×180, the mark), and `plainbase-logo{,-dark}.svg` (served to the
SPA header).

## Where they're used

- **README** leads with a `<picture>` that swaps `plainbase-logo.svg` / `plainbase-logo-dark.svg`
  by `prefers-color-scheme`, so GitHub shows the right one in light and dark themes.
- **App header** (`frontend/src/components/Shell.tsx`) shows the wordmark, swapped by the
  manual theme toggle's `[data-theme]` attribute (display-only CSS in `app.css`). The slash
  stays brand-teal rather than adopting the UI's blue accent — it's a brand element, not a
  control. (This puts the logo in the app header, which the original concept left out of
  scope; the expansion is deliberate.)
- **`frontend/index.html`** links `favicon.svg` and `apple-touch-icon.png`.

## Regenerating

The committed SVGs are already outlined, so they render anywhere without fonts. To
**re-render the PNGs** from them (Inkscape):

```sh
inkscape assets/brand/plainbase-mark.svg --export-width=512 --export-height=512 \
  --export-filename=assets/brand/plainbase-mark.png
inkscape assets/brand/plainbase-mark.svg --export-width=180 --export-height=180 \
  --export-filename=frontend/public/apple-touch-icon.png
```

To **change the lettering**, JetBrains Mono Bold must be installed: author a source SVG with
the slash path plus a `<text>` element in `font-family:"JetBrains Mono"; font-weight:700`,
then outline and crop with
`inkscape src.svg --export-text-to-path --export-area-drawing --export-plain-svg --export-filename=out.svg`,
and re-derive the dark variant by swapping the two fills (ink `#0f172a`→`#f1f5f9`, slash
`#0f766e`→`#14b8a6`).

## Visual rules

- No gradients, shadows, illustrations, mascots, or decorative AI symbols.
- Filled vector shapes only; nothing that depends on hairline strokes at small sizes.
- Rounded corners stay modest and technical.
- The wordmark works on a transparent background; the icon uses the gray tile for a boundary
  on white.
- Wordmark letter-spacing is normal, never tightened.

## Non-goals

Full brand guidelines, campaign variants, animation, or AI-generated bitmap art. This is a
single quiet identity that fits the existing UI, not a brand layer on top of it.
