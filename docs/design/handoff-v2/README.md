# Plainbase UI - Design Handoff v2

This is the second design pass over the human-facing UI. It **amends**
[the v1 handoff](../handoff/README.md): everything in v1 still holds unless a section
below replaces it. v1 gave Plainbase its identity (teal accent, IBM Plex Sans,
JetBrains Mono, warm-tinted zinc, the "soft" dark depth). v2 is about **usability and
polish**: wayfinding, explaining state, keeping context, and contrast.

The visual reference is the design canvas "Plainbase UI Critique" (a private claude.ai
artifact, `https://claude.ai/artifact/Xfu6SNWJn6CduLWKqcTQqr`). It holds the ranked
findings, a before/after board per screen (dark), and a light-mode section. **Do not
ship the canvas markup.** Recreate the designs in the real frontend with the existing
Tailwind v4 + `--pb-*` token system and the stable `.pb-*` selectors.

Scope: **desktop** (1280 px and up). Mobile is explicitly out of scope for this pass.

---

## Decisions (locked by the owner, 2026-10-02)

| Aspect | v2 decision | Amends v1? |
|---|---|---|
| Dark surfaces | **Keep today's tones**: content `--pb-surface` (#1f1f22), header and sidebar `--pb-surface-raised` (#272729, one step lighter than content). The slight sidebar/content contrast is wanted. | No (confirms "soft" depth) |
| Dark faint text | Raise until it passes 4.5:1 on every dark surface (target about #9a9aa3) | Yes |
| Dark borders | One step more visible than today | Yes |
| Dark primary button | **Soft teal tint**, not a solid bright teal fill | Yes |
| Light surfaces | **Warm paper page** (#fbfaf7), **chrome** (header, sidebar) one step darker (#f4f2ec), **cards white** | Yes |
| Light active nav | Solid teal tint (#d5ece6), not accent at 13% alpha | Yes |
| Light faint text | #6b645a; 4.5:1 minimum including hover and tinted states, about 5.2:1 or better on page, chrome and cards | Yes |
| Monospace | **JetBrains Mono stays**; it is for data only: paths, tags, status, dates, keys, code | Narrows v1 |
| Section labels | Sentence-case **IBM Plex Sans**, 12-13 px semibold (no uppercase mono) | Yes |
| Page metadata | **Chip strip under the title** (v1's "Byline" alternative), not the rail | Yes |
| Right rail | **Outline first** with an active-section marker, then Discussions | Yes |
| Sidebar tree icons | Faint page icons in the **sidebar tree**; **none** on the folder-landing page list | Yes (partly) |
| Search | Title matches instant as today; **full-text hits auto-append below** after the debounce. No bridge row. | Yes (amends ADR-0005) |
| Editor | **No split view.** Keep the quick preview overlay, surfaced as a Write / Preview switch | Yes (drops v1 Split) |
| Page summaries on landings | **Deferred**: needs a new tree wire field | n/a |
| Discussions polish | **In scope** | n/a |

---

## Global rules

1. **Contrast floor.** No UI text below 12 px. Text meets 4.5:1 against the surface it
   sits on, in both themes. Icons, focus rings, carets and status marks meet 3:1.
   Decorative borders and separators retain the specified colors and are exempt from
   that mark threshold. This exception does not certify controls whose only identifying
   cue is a low-contrast input outline; those require a separate functional-boundary check.
2. **Mono means data.** JetBrains Mono for paths, tags, status, dates, keyboard keys and
   code. Every label, heading and control is Plex Sans.
3. **Wayfinding before metadata.** Outline, breadcrumbs and search outrank file info.
4. **Every state explains itself.** Broken, unsaved and resolved each get a word, an
   icon where it helps, and an action.
5. **Keep context.** Create, search and edit start from where the user already is.

---

## Tokens

All changes stay inside `frontend/src/styles/tokens.css` so the token-discipline gate
keeps passing (`./gradlew :frontend:npmTest`). Exact implementation (new steps vs.
`color-mix`) is the chunk's call; the **targets** below are what must hold.

### Dark (`[data-theme="dark"]`)

| Role | Target | Notes |
|---|---|---|
| `--pb-surface` (content) | #1f1f22 | unchanged |
| `--pb-surface-raised` (header, sidebar, cards) | #272729 | unchanged |
| Search field / inputs on chrome | about #2f2f33 | one step above raised |
| `--pb-border` | about #36363b | one step stronger than gray-750 |
| `--pb-text` / `--pb-text-muted` | #f4f4f5 / about #b1b1ba | 15.0:1 / 7.7:1 on surface |
| `--pb-text-faint` | about #9a9aa3 | 5.9:1 on surface, 4.5:1 or better on hover |
| Primary button | bg accent at about 16%, 1 px accent border at about 40%, text teal-300 | 6.5:1 or better on every surface |

### Light (`:root`)

| Role | Target | Notes |
|---|---|---|
| Page (`--pb-surface`) | #fbfaf7 | warm paper instead of `var(--pb-white)` |
| Chrome (header, sidebar) | #f4f2ec | needs its own token, separate from cards |
| Cards, panels (`--pb-surface-raised`) | #ffffff | cards lift off the page |
| `--pb-border` | #e4dfd4 | |
| Active nav row | #d5ece6 | solid, plus teal icon and medium weight |
| `--pb-text` / muted / faint | #1c1a16 / #5c564d / #6b645a | 16.7 / 7.0 / 5.6:1 on paper |
| `--pb-accent`, `--pb-link` | teal-700 #0f766e | 5.2:1 on paper; primary button fill with white text (5.5:1) |
| Logo slash, `--pb-focus-ring` | teal-600 #0d9488 | non-text marks only |
| Broken-link / warning text | #92400e | 6.8:1 on paper |

`--pb-warmth` stays at 5%: the light paper tone comes from the page surface, not from
warming the gray ramp, and dark keeps today's tones.

---

## Screens

### 1. Shell and header (`Shell.tsx`)
- Header (h 56): wordmark left; a **wide search field** (about 480 px, centered) that
  opens the palette, with the `⌘K` hint inside it; right side: Edit (outlined, on pages),
  **New page** (the single primary button), divider, theme toggle.
- In the editor the header keeps only New page and the theme toggle; editing actions live
  in the editor bar.

### 2. Sidebar (`Sidebar.tsx`, `RootSelector.tsx`)
- **Space switcher**: the existing root selector restyled as a labelled control: a
  letter tile, the space name, a one-line caption, and an up/down chevron. Same behavior
  (only shown with 2+ roots).
- Tree: carets and semibold folder labels as in v1; **faint file icon on page rows**;
  long titles truncate with an ellipsis (full title on hover).
- **Folder names**: when a folder has no landing-page title, derive one from the slug
  (`release-notes` to "Release notes"). The slug remains visible in path chips.
- Active row: the stronger tint from Tokens; never color alone (weight changes too).

### 3. Reading view (`PageView.tsx`, `Toc.tsx`, `Prose.tsx`)
- Breadcrumbs start with the space name.
- **Chip strip under the title**: file path chip (mono, with copy), status, owner;
  empty fields render as dashed "+ Status" / "+ Owner" chips that open the editor's
  properties.
- **Right rail**: "On this page" first, with an **active-section marker** (scroll-spy);
  then a Discussions block with a count and a "Start a discussion" button.
- **Broken links** (`data-pb-link-error`): dotted amber underline plus a small
  broken-link icon instead of the red wavy underline. Hover or focus opens a card:
  "This page doesn't exist", one sentence naming the target, and **Create page** /
  **Edit link** actions.
- **Long unbroken inline code** (hashes) renders as a truncated chip with a copy button.

### 4. Search palette (`SearchPalette.tsx`, `SearchResultItem.tsx`)
- Title matches appear instantly (the zero-network quick-switch stays). After the
  debounce, **full-text hits append below** in the same list with highlighted snippets
  and heading breadcrumbs. The bridge row is removed. Record this as an amendment to
  ADR-0005.
- Results grouped by space; scope chips (All spaces / each root) above the list.
- Breadcrumb trail instead of a truncated raw path; about 8 rows visible.
- Stronger, blurred scrim. Footer keyboard hints stay.

### 5. Folder landing
- Folder cards in a **fluid** grid that fills the column (no fixed card width); folder
  icon tile, title, `N pages` and a small mono path on **one line**.
- Pages in one bordered panel of rows (title, chevron). **No file icons here**. A
  one-line summary per page is **deferred** until a summary field exists on the wire.
- Header: title, `N folders · N pages`, and a secondary "New page here" button.

### 6. New page
- Opens as a **dialog** over the current view instead of a separate page.
- Location picker **prefilled with the current folder**; large title input; a live,
  editable URL preview instead of a Slug field up front.
- Templates (Blank, How-to, Reference, Meeting notes) as cards with a one-line purpose.
- "More options" (custom slug, folder landing page) collapsed. No body field: the page
  opens in the editor after creation.

### 7. Editor (`EditorPage.tsx`, `EditorToolbar.tsx`, `MetaForm.tsx`)
- Editor bar: breadcrumb, a **Write / Preview** segmented switch (replacing the eye
  button), an always-visible save state ("Unsaved changes", "Saving", "Saved"),
  Discard, and Save with the `⌘S` hint.
- Preview keeps today's overlay behavior: it covers the source in place so cursor,
  scroll and undo survive; the toolbar dims while previewing.
- Source pane centered at a readable width with line numbers. **Table rows never wrap**
  (prose may). Broken links get a gutter marker while writing.
- Toolbar grouped (block style, inline, lists, insert) with tooltips on every button.
- **Properties as chips** under the bar (Status, Owner, Review by, Tags) instead of the
  side column of native inputs.

### 8. Discussions (`DiscussionPanel.tsx`, `DiscussionThread.tsx`, `DiscussionsIndex.tsx`)
- The list updates without a manual Refresh button.
- Initials avatars on threads and comments.
- **Open / Resolved** as a filter, with a status pill on each thread.
- The quoted passage is highlighted in the page while its thread is open.

---

## Acceptance checks (every chunk)

- `./gradlew build` (JVM floor) and `./gradlew :frontend:build` green; server-touching
  chunks also run the native gate.
- No new text under 12 px; contrast targets above hold in **both** themes.
- Mono appears only on data (paths, tags, status, dates, keys, code).
- Smoke tests that select by `.pb-*` / `data-pb-*` keep passing, or are updated in the
  same chunk.

## Out of scope for this pass
- Mobile layout (no navigation below tablet width; one wide table widens the page).
- Page summaries on folder landings (needs a provisional tree field, ADR-0007).
- Empty, loading and 404 states (still undesigned, as in v1).
