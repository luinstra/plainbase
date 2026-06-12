# Plainbase Logo and Web Icon Design

## Goal

Create a restrained Plainbase logo system for the GitHub-facing project page and the app web icon.

The identity should feel like sober infrastructure with enough polish for a modern internal docs product. It should support the current product thesis: plain files as the source of truth, a durable knowledge base, and structured access for humans and agents.

## Context

Plainbase currently presents itself with a text-only header and no favicon source in `frontend/index.html`. The frontend visual system is quiet and utilitarian: neutral surfaces, blue accent tokens, system fonts, and a docs-first layout.

The mark should fit that existing UI instead of introducing a marketing-heavy brand layer.

## Chosen Direction

Use a simple "file spine" mark:

- A vertical left spine represents the stable base and page identity.
- Two horizontal file slabs extend from the spine, representing plain Markdown files in a filesystem tree.
- The combined silhouette can be read as a subtle P/B monogram after the name is known, but it should first read as files plus storage.
- The mark should be geometric, compact, and legible at 16 by 16 pixels.

This keeps the design closer to infrastructure and docs than to a mascot, sparkle, or generic AI mark.

## Visual Rules

- No gradients, shadows, illustrations, mascots, or decorative AI symbols.
- No dependency on tiny text, thin strokes, or detailed folds in the favicon.
- Prefer filled vector shapes over hairline strokes for small-size clarity.
- Rounded corners are allowed, but should stay modest and technical.
- The primary mark must work in one color.
- The color version may use the existing Plainbase blue accent plus near-black or white.
- Letter spacing in the wordmark should be normal, not tightened.

## Asset Set

Implementation should add these source assets:

- `assets/brand/plainbase-mark.svg`: canonical transparent SVG mark for GitHub, docs, and reuse.
- `assets/brand/plainbase-logo.svg`: horizontal lockup with mark plus the `Plainbase` wordmark.
- `frontend/public/favicon.svg`: web icon optimized for browser tabs and Vite static serving.

If the implementation environment has a reliable local rasterizer, it may also add PNG exports:

- `frontend/public/apple-touch-icon.png`: 180 by 180 app icon.
- `assets/brand/plainbase-mark-512.png`: large GitHub/social preview fallback.

PNG exports are useful but not required for the first pass if SVG rendering is enough.

## GitHub Page Usage

The README should use the horizontal lockup near the top as a quiet brand signal without making the README feel like a landing page. The current first line, positioning quote, and quickstart should remain easy to scan.

Recommended README placement:

- Add `assets/brand/plainbase-logo.svg` as the first element above `# Plainbase`, left-aligned at restrained width.
- Keep the existing `# Plainbase` heading for accessibility and GitHub outline behavior.
- Do not replace the product positioning line.

## Web Icon Usage

The web icon should use the same mark, simplified if needed for favicon clarity.

`frontend/index.html` should include:

- `link rel="icon"` pointing at `/favicon.svg`.
- Optional `link rel="apple-touch-icon"` only if the PNG export exists.

The icon should remain visible in light and dark browser chrome. If a transparent one-color favicon is too fragile, the favicon may use a solid blue or near-black square with the file-spine mark knocked out in white.

## Integration Boundaries

Keep implementation isolated to brand assets plus minimal references:

- Asset files under `assets/brand/`.
- Static web icon files under `frontend/public/`.
- Minimal `frontend/index.html` link tags.
- Optional README image reference.

Do not change application layout, theme tokens, backend code, or generated build output as part of the logo pass.

## Validation

Before considering the implementation complete:

- Inspect the SVGs directly to ensure the mark is centered and not clipped.
- Check the favicon at 16, 32, and 64 pixel sizes.
- Confirm `frontend/index.html` references only files that exist.
- Run the frontend build or the narrowest available project check that validates the changed frontend source.
- Review `git diff` to ensure unrelated active server/search changes were not included.

## Non-goals

- Full brand guidelines.
- Multiple campaign-style logo variants.
- Animated logo treatments.
- AI-generated bitmap illustration.
- Redesigning the application header or theme.
