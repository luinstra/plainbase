# Plainbase UI — Design Handoff

This package is the visual + interaction spec for Plainbase's human-facing UI.
It is **repo-aware**: it maps each decision onto the existing frontend
(`frontend/src/styles/tokens.css`, `app.css`, and the `Shell` / `Sidebar` /
`PageView` / `Prose` / `Toc` / `SearchPalette` components), so the work is mostly
**editing the token tier and adding a few components** — not a rewrite.

---

## About the design files

The HTML files in this bundle are **design references** — hi-fidelity prototypes
showing intended look and behavior. **Do not ship the HTML.** Recreate the designs
in the real frontend (React + the existing Tailwind v4 + `--pb-*` token system),
reusing the established `.pb-*` stable selectors and component structure.

Fidelity: **hi-fi.** Colors, type, spacing, and interactions are final. Match them.

The prototypes carry an in-page "Tweaks" panel (warmth / accent / dark-depth /
etc.). Those were **exploration knobs** used to land the decisions below — they are
NOT a product feature. Ship the **locked defaults** in the "Decisions" section.

---

## The one big idea

The current build is a solid skeleton with a generic personality: a **blue** accent
and **system fonts**. Every change here grows the UI out of the **logo** instead —
its teal slash and its JetBrains Mono wordmark set the rules. Net effect: same
structure, far stronger identity. Five moves:

1. **Teal is the accent** (from the logo) — replaces blue everywhere interactive.
2. **JetBrains Mono earns a job** — the voice of *structure*: file paths, tags,
   status, keys, code, breadcrumb separators.
3. **IBM Plex Sans for everything human** — UI + reading. (Deliberately not Inter.)
4. **The slash is a motif** — a skewed teal mark = "you are here": active nav,
   breadcrumb separators, callout bars.
5. **Restraint by default** — type and spacing carry it; decoration is rare.

---

## Decisions (the locked defaults to ship)

| Aspect | Decision |
|---|---|
| Accent | **Teal** — light `#0f766e` (teal-700), dark `#2dd4bf` (teal-400) |
| UI + body font | **IBM Plex Sans** (400/500/600/700) |
| Mono / structural font | **JetBrains Mono** (400/500/700) — already the wordmark |
| Reading column | Sans by default; a serif (Source Serif 4) option is acceptable |
| Neutrals | Keep your **zinc** ramp, warmed ~**5%** toward a paper tone (see Warmth) |
| Light surface | warm off-white (`--pb-surface` = gray-50, `--pb-surface-raised` ≈ white) |
| Dark surface | **"Soft"** depth — off-black, *not* near-black (see Dark mode) |
| Page metadata | **Rail** (quiet list above the TOC) by default; **Byline** under the title is the alt |
| Home page | **The root folder landing** — no bespoke template (see Folder landing) |
| Sidebar | Clean tree + a crisp disclosure **caret** + **bold folder labels**. No icons, no workspace header, no git footer. |
| Editor | **CodeMirror 6** source + live preview; frontmatter as a structured **Panel** (default) or **Raw YAML** |

---

## Design tokens → map onto `tokens.css`

Your `tokens.css` two-tier model is perfect for this. Changes are almost entirely
in **Tier 1 (add teal + warm primitives)** and **Tier 2 (repoint semantics)**.
`reference/plainbase.css` in this bundle is the full, working token sheet to port.

### Tier 1 — add primitives

**Teal ramp** (new hue; add to the primitive list + the discipline-test regex):
```
--pb-teal-50:#f0fdfa; --pb-teal-100:#ccfbf1; --pb-teal-200:#99f6e4; --pb-teal-300:#5eead4;
--pb-teal-400:#2dd4bf; --pb-teal-500:#14b8a6; --pb-teal-600:#0d9488; --pb-teal-700:#0f766e;
--pb-teal-800:#115e59; --pb-teal-900:#134e4a;
```

**Warmth** — keep zinc, but blend it a touch toward a warm paper tone. Add a warm
counterpart ramp + a `--pb-warmth` knob, and make the consumed gray ramp a live
`color-mix` of the two (this stays inside tokens.css, so token-discipline passes):
```
--pb-warmth: 5%;                         /* the chosen default — barely-there warmth */
/* warm counterparts (same lightness, warm hue) */
--pb-warm-50:#faf8f3; --pb-warm-100:#f3f0e9; … --pb-warm-950:#100d09;
/* consumed ramp = blend */
--pb-gray-50: color-mix(in srgb, #fafafa, var(--pb-warm-50) var(--pb-warmth));
…  /* repeat for every gray step (see reference/plainbase.css) */
```
If a live blend is more than you want, the simpler option is to hardcode the
already-blended values at 5% — visually identical, but you lose the single knob.

### Tier 2 — repoint semantics

**Light** (the meaningful diffs from today):
```
--pb-accent:        var(--pb-teal-700);   /* was blue-600 */
--pb-link:          var(--pb-teal-700);
--pb-link-hover:    color-mix(in srgb, var(--pb-accent), #000 24%);
--pb-accent-soft:   color-mix(in srgb, var(--pb-accent) 13%, transparent);  /* new — tints */
--pb-focus-ring:    var(--pb-teal-600);
--pb-selection-bg:  var(--pb-teal-100);
```

**Dark — ship the "Soft" depth** (the near-black original read as a flat void;
this lifts the floor so elevation is legible). Under `[data-theme="dark"]`:
```
--pb-surface:        var(--pb-gray-850);  /* #1f1f23 — was gray-925 */
--pb-surface-raised: var(--pb-gray-800);  /* #27272a */
--pb-surface-sunken: var(--pb-gray-900);
--pb-surface-hover:  var(--pb-gray-750);
--pb-surface-active: var(--pb-gray-700);
--pb-border:         var(--pb-gray-750);
--pb-border-strong:  var(--pb-gray-600);
--pb-text:           var(--pb-gray-100);
--pb-text-muted:     var(--pb-gray-400);
--pb-accent:         var(--pb-teal-400);
--pb-link:           var(--pb-teal-400);
--pb-accent-soft:    color-mix(in srgb, var(--pb-accent) 18%, transparent);
```
(Deep/Medium were rejected; Soft is the default. If you ever expose it, gate
alternates on `[data-theme="dark"][data-dark="deep"|"medium"]`.)

### Spacing / radius / motif
- 4px base step. Radii small: **4 / 6 / 9px** (`--r-sm/md/lg`). This is a precise tool.
- **Slash motif:** a 3px-wide bar, `transform: skewX(-12deg)`, in `--pb-accent`.
  Used as the active-nav indicator, the breadcrumb separator glyph (`/` in mono,
  skewed, teal), and the left bar on callouts.

---

## Typography → `app.css` `--font-*`

Currently system fonts. Switch to:
```
--font-sans: "IBM Plex Sans", ui-sans-serif, system-ui, sans-serif;
--font-mono: "JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace;
```
Add the Google Fonts (or self-host): IBM Plex Sans 400/500/600/700, JetBrains
Mono 400/500/700, (optional) Source Serif 4 for the serif reading option.

**Prose scale** (already close to your `app.css` `.pb-prose`):
| Element | Size | Weight | Notes |
|---|---|---|---|
| h1 | 34px | 650 | letter-spacing −0.02em |
| h2 | 23px | 600 | 1px bottom border (`--pb-border`) |
| h3 | 18.5px | 600 | |
| body | 16px / 1.75 | 400 | max-width ~72ch |
| UI text | 13.5–14px | 400–500 | sidebar, buttons, breadcrumbs |
| meta / code | 11–13px | — | **JetBrains Mono** — paths, tags, dates, kbd |

**Structural = mono.** Anything that is a path, tag, status, date, keyboard key,
or code renders in JetBrains Mono. This is the single biggest identity lever.

---

## Screens & components

References in this bundle are listed per screen. Map each to the existing component.

### 1. App shell — `Shell.tsx`
Sticky header (h 56): wordmark (teal slash + `plainbase` in JetBrains Mono) ·
right side = `⌘K` search trigger + theme toggle. Body = sidebar + content.
*No change in structure; restyle via tokens + fonts.*

### 2. Sidebar — `Sidebar.tsx` / `.pb-sidebar`  → ref: any screen
**Keep it minimal.** A file tree. The only enrichments that survived review:
- **Disclosure caret**: a CSS chevron (not a glyph dot, not an icon). Draw with
  borders on a `::before`, ~7px box, 2px stroke, `--pb-text-muted`; rotate −45°→45°
  on open. (Rejected: folder icons, page icons, status dots, a workspace header
  with a book glyph, a "+ New" button, a git-status footer. Don't add these.)
- **Folder labels**: `font-weight: 600`, `--pb-text`. Page links: `--pb-text-muted`,
  normal weight. This contrast is what makes a directory read as a directory.
- **Active page**: `--pb-accent-soft` background + the teal slash bar (`::before`).

### 3. Doc reading page — `PageView.tsx` (Breadcrumbs + Prose + Toc)  → ref: `Doc Reading Page.html`
Three columns: sidebar · reading column (≤720px) · right rail.
- **Header band (chrome, above the markdown):** breadcrumbs (teal-slash separators).
  The markdown body renders **standalone** below it — never inject app chrome
  *inside* the rendered markdown.
- **Reading column = your `.pb-prose`.** First paragraph is a normal paragraph
  (no special "lead" styling — content stays standard markdown).
- **Right rail — NEW: page metadata.** Frontmatter (owner, status, tags, updated,
  review, source path) shown as a quiet **Rail** block (de-chromed list) above the
  **"On this page" TOC**. Alternate = **Byline** (a one-line owner · status · updated ·
  tags row in the header band; rail then shows TOC only). Default **Rail**.
  - `status` renders as a mono dot-chip (active=green, draft=amber, review=blue).
  - `owner` → small initials avatar in `--pb-accent-soft`.
- **Doc footer:** `Last updated … · Edit this page` (mono) + prev/next pager.

### 4. Folder landing — `FolderListing` / `FolderLanding`  → ref: `Folder Landing.html`
**One rule for every folder, root included.** A folder with an `index.md`/`README.md`
renders that authored page AS the whole landing — the index content IS the folder view, and
the **generated child listing is suppressed** (the children stay in the sidebar tree, and the
index keeps its own `/p/{id}` permalink). A folder with **no** index shows the generated
child listing instead:
- **Subfolders → cards** in a responsive `auto-fill, minmax(216px,1fr)` grid. Card =
  folder icon + name + one-line description (from `_folder.yaml`) + `path/ · N pages`.
- **Pages → a compact list** in a `auto-fill, minmax(310px,1fr)` grid (flows to
  columns when long). Row = page icon + title + status dot + date.
- These auto-fill grids are what handle all shapes: many folders, many files, or both.

### 5. Welcome / home  → ref: `Welcome Page.html`
**Is the root folder landing** — *not* a distinct template. Root `index.md` (welcome
blurb + an authored `## Start here` links list) **is** the landing: under the Phase 5.5
REPLACE rule the index content replaces the generated card listing (top-level folders
stay reachable via the sidebar tree). "Start here" is **authored markdown**, not
app-rendered featured chrome.

### 6. Search palette — `SearchPalette.tsx` / `.pb-search`  → ref: `Search Palette.html`
`⌘K` overlay over a dimmed app. Input row (⌕ + input + `esc`) · results · footer hints.
- Live filter over titles / headings / snippets / paths; title-prefix matches rank first.
- Each result: page (doc icon) or heading (`#`) + title with the matched substring
  in `<mark>` (`--pb-accent`) + a snippet line + a mono path. Empty query = a "Recent"
  group; no match = a "No matches" state.
- Keyboard: ↑/↓ move (teal-slash marker on the selected row), ↵ open, Esc close.
- A "snippets on/off" density option is reasonable to keep.

### 7. Editor — NEW  → ref: `Editor.html`
Browser markdown editing. The prototype uses **CodeMirror 5** for single-file
loading; **ship CodeMirror 6** (modular — and it's literally what Obsidian's source
mode uses). Theme it from the `--pb-*` tokens (a small `EditorView.theme` +
`HighlightStyle` of pure `var(--pb-*)` references) so it inherits light/dark/accent
with zero extra wiring. Preview = your server renderer (or `markdown-it`).
- Header: file path (mono) + unsaved dot · view toggle **Split / Write / Preview** · theme.
- **Frontmatter editing — two modes (a real product decision):** **Panel** =
  structured property fields (title, status, owner, tags, review) above a clean body
  editor; **Raw YAML** = the `---` block lives at the top of the source. Preview
  always strips frontmatter. Default **Panel**.
- Markdown toolbar (bold/italic/code/link wrap selection; h2/list/quote/fence insert).
- **Git-native save bar:** branch ref + a commit-message field + Discard + Save (⌘S).

---

## Interactions & states
- **Theme toggle:** `data-theme="dark"` on `<html>`, persisted to `localStorage`
  (`pb-theme`), with a pre-paint bootstrap to avoid a flash. (Matches your `theme.ts`.)
- **Slide/entrance:** none required; this is a docs app, keep it calm.
- **Hover:** rows/cards lift subtly (border → `--pb-border-strong`, bg → hover token).
- **Empty / loading / 404:** not yet designed — flag for a follow-up pass.

## Assets
- **Wordmark:** `frontend/public/plainbase-logo.svg` / `-dark.svg` (already in repo;
  teal slash + JetBrains Mono letters). No new brand assets introduced.
- **Icons** (folder, page, git, search, sun/moon): simple inline stroke SVGs in the
  references — swap for your icon set of choice; keep them 1.8px stroke, ~16px.

## Files in this bundle
- `reference/plainbase.css` — the complete working token + component stylesheet
  (port this into `tokens.css` + `app.css`).
- `reference/Doc Reading Page.html` — doc reading page (metadata rail/byline, TOC).
- `reference/Folder Landing.html` — folder listing (cards + list; 3 content shapes).
- `reference/Welcome Page.html` — home as the root folder landing.
- `reference/Search Palette.html` — ⌘K palette.
- `reference/Editor.html` — markdown editor (CodeMirror + live preview).
- `reference/Design System.html` — the full system board (color, type, components).

Open any reference in a browser to see the live behavior; the Tweaks panel is dev
chrome — ignore it when implementing.
