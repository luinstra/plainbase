# Custom themes

## What a theme is

Theme definitions are CSS files selected for light and dark with `theme.light` and `theme.dark` in `DATA_DIR/plainbase.conf`. An optional `DATA_DIR/custom.css` adds partial overrides.

The selected CSS definition and the reader's light or dark display mode are separate concepts.

See [theme settings](configuration.md#themes) and the [theme decision](decisions/0014-custom-themes-are-css-definitions-selected-from-data-dir.md).

## Quick start

1. Copy [the Gruvbox light example](../contrib/tinted/generated/base16-gruvbox-light-hard-adapted.css) to `DATA_DIR/gruvbox.css`.
2. Set this in `DATA_DIR/plainbase.conf`:

   ```hocon
   theme.light = "gruvbox.css"
   ```

3. Restart Plainbase.

With only `theme.light` set, dark uses the bundled dark definition.

## How the stylesheets load

The page links these stylesheets in order:

1. The app's stylesheet.
2. The generic layer.
3. The light definition.
4. The dark definition.
5. `custom.css`.

A selected definition completely replaces the bundled definition for that mode.

Each definition must scope its rules with its mode's selector below. All four theme stylesheets load in both modes, so an unscoped `:root` rule in a light definition also applies in dark mode.

- Light: `:where(:root:not([data-theme="dark"]))`
- Dark: `:where(:root[data-theme="dark"])`

Advice: Copy a [bundled light definition](../frontend/src/styles/themes/light.css) or [dark definition](../frontend/src/styles/themes/dark.css) when you want to retain and adjust its private refinements.

## Inputs

The Summary column is an orientation aid for the generic layer. It is neither complete nor guaranteed; where a scope is exact, a later section says so.

| Input | Kind | Required | Default | Summary (generic layer) |
| --- | --- | --- | --- | --- |
| `--pb-theme-background` | color | yes | none | Page background, and the base that default card, muted, border and selection colors mix from. |
| `--pb-theme-foreground` | color | yes | none | Page text, and the base for default card text, muted text, borders, links, and feedback and syntax colors. |
| `--pb-theme-primary` | color | yes | none | Primary action fill, and the base for links, accents, the default selection and the default focus outline. |
| `--pb-theme-primary-foreground` | color | yes | none | Text on primary actions and accent fills. |
| `--pb-theme-card` | color | no | `color-mix(in srgb, var(--pb-theme-background), var(--pb-theme-foreground) 3%)` | Raised surfaces such as cards, dialogs, form fields, code blocks and diagram nodes. |
| `--pb-theme-card-foreground` | color | no | `var(--pb-theme-foreground)` | Text in discussion cards, list cards, evidence and notices, the new-page dialog, code text (code blocks, diffs and code in the editor) and diagram node labels. |
| `--pb-theme-muted` | color | no | `color-mix(in srgb, var(--pb-theme-background), var(--pb-theme-foreground) 6%)` | Quiet surfaces such as the header, sidebar, table headers and inline code. |
| `--pb-theme-muted-foreground` | color | no | `color-mix(in srgb, var(--pb-theme-foreground), var(--pb-theme-background) 15%)` | Secondary and faint text, archived status and the default comment color. |
| `--pb-theme-border` | color | no | `color-mix(in srgb, var(--pb-theme-foreground), var(--pb-theme-background) 75%)` | Edges and dividers, and the base for stronger edges, blockquote rules and diagram node borders. |
| `--pb-theme-ring` | color | no | `rgb(from var(--pb-theme-primary) r g b / 1)` | Keyboard focus outline. |
| `--pb-theme-selection` | color | no | `color-mix(in srgb, var(--pb-theme-primary), var(--pb-theme-background) 88%)` | Text selection, the active navigation item, discussion highlights and the deep-link pulse. |
| `--pb-theme-selection-foreground` | color | no | `var(--pb-theme-foreground)` | Text inside a selection, including the editor's. |
| `--pb-theme-destructive` | color | no | `var(--pb-theme-foreground)` | Danger callouts, errors, deprecated status and diff deletions. |
| `--pb-theme-warning` | color | no | `var(--pb-theme-foreground)` | Warning callouts, broken links and draft status. |
| `--pb-theme-success` | color | no | `var(--pb-theme-foreground)` | Active status and diff additions. |
| `--pb-theme-syntax-keyword` | color | no | `var(--pb-theme-foreground)` | Keywords in highlighted code. |
| `--pb-theme-syntax-string` | color | no | `var(--pb-theme-foreground)` | Strings in highlighted code. |
| `--pb-theme-syntax-comment` | color | no | `var(--pb-theme-muted-foreground)` | Comments in highlighted code. |
| `--pb-theme-syntax-literal` | color | no | `var(--pb-theme-foreground)` | Numbers and literals in highlighted code. |
| `--pb-theme-syntax-title` | color | no | `var(--pb-theme-foreground)` | Titles such as function, class and tag names in highlighted code. |
| `--pb-theme-syntax-attribute` | color | no | `var(--pb-theme-foreground)` | Attribute names in highlighted code. |
| `--pb-theme-font-sans` | font | no | `"IBM Plex Sans", ui-sans-serif, system-ui, sans-serif` | Interface and prose font family. |
| `--pb-theme-font-mono` | font | no | `"JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace` | Code and editor font family. |
| `--pb-theme-radius` | length | no | `0.25rem` | Base corner radius; seven decorative steps scale from it. |

Bundled definitions preserve private refinements alongside their public input bindings.

Advice: To change those refinements, copy a bundled definition and edit it.

The generic layer supplies no fallback for the four required inputs, so a definition must set all four.

For files that pass the read limits, Plainbase does not validate CSS input values; it serves the file as written, apart from removing one leading byte order mark. Diagrams accept a narrower set of colors; see [Diagrams](#diagrams).

## Text tiers

Generic faint and muted text follow `--pb-theme-muted-foreground`, share one color, and are both checked on their shared contrast surfaces.

The bundled definitions keep separate private faint refinements.

Advice: To retain a third text tier, copy a bundled definition and edit `--pb-text-faint` in that definition's scope.

Compatibility policy: `--pb-text-faint` is a private role and is not versioned.

## Cards and dialogs

In the generic layer, `--pb-theme-card-foreground` colors text in discussion cards, list cards, evidence and notices, the new-page dialog (including the dialog's fields), code text (code blocks, diffs and code in the editor) and diagram node labels. Inside cards and the dialog, hover and checked states drawn from the page keep page text.

Card text applies nowhere else: other raised and field surfaces, such as the search palette and the header search field, do not use it.

Advice: Check card foreground against both the page and card backgrounds, and keep the backgrounds close.

## Fonts, logo and radius

### Fonts

The body uses `--pb-theme-font-sans`. Prose code blocks use `--pb-theme-font-mono`. The editor's content and scroller use the mono font role.

The default font stacks are listed in [Inputs](#inputs). See [Security](#security) for font URL restrictions.

Advice: Prefer the default stacks or families installed on your readers' machines.

### Logo

The logo uses fixed light and dark image sources, with one shown per mode.

### Radius

The default radius is `0.25rem`, which is 4px at a 16px root font size. Every step is a fixed multiple of `--pb-theme-radius`, so a rem value follows the root font size and a `px` value gives fixed corners.

| Step | Multiplier | Default |
| --- | --- | --- |
| `--pb-radius` | 1 | `0.25rem` |
| `--pb-radius-xs` | 0.8 | `0.2rem` |
| `--pb-radius-sm` | 1.2 | `0.3rem` |
| `--pb-radius-md` | 1.5 | `0.375rem` |
| `--pb-radius-lg` | 2 | `0.5rem` |
| `--pb-radius-xl` | 2.5 | `0.625rem` |
| `--pb-radius-2xl` | 3 | `0.75rem` |
| `--pb-radius-3xl` | 4 | `1rem` |

App CSS radius declarations use the semantic scale, `50%`, `20px` or `inherit`.

## Diagrams

The table shows Mermaid color variables, their private roles and their input dependencies in the generic layer.

| Mermaid variables | Role | Follows (generic layer) |
| --- | --- | --- |
| `background`, `secondaryColor`, `clusterBkg`, `edgeLabelBackground` | `--pb-surface` | `--pb-theme-background` |
| `primaryColor`, `mainBkg` | `--pb-diagram-node-bg` | `--pb-theme-background`, `--pb-theme-foreground`, `--pb-theme-card` |
| `primaryTextColor`, `tertiaryTextColor`, `nodeTextColor` | `--pb-diagram-node-text` | `--pb-theme-foreground`, `--pb-theme-card-foreground` |
| `primaryBorderColor`, `nodeBorder` | `--pb-diagram-node-border` | `--pb-theme-background`, `--pb-theme-foreground`, `--pb-theme-border` |
| `lineColor` | `--pb-link` | `--pb-theme-foreground`, `--pb-theme-primary` |
| `secondaryTextColor`, `textColor`, `titleColor` | `--pb-text` | `--pb-theme-foreground` |
| `secondaryBorderColor`, `clusterBorder` | `--pb-border-strong` | `--pb-theme-background`, `--pb-theme-foreground`, `--pb-theme-border` |
| `tertiaryColor` | `--pb-surface-raised` | `--pb-theme-background`, `--pb-theme-foreground`, `--pb-theme-card` |
| `tertiaryBorderColor` | `--pb-border` | `--pb-theme-background`, `--pb-theme-foreground`, `--pb-theme-border` |

Mermaid receives each color converted to sRGB; the rest of the interface uses your colors as written. Conversion works on the color the browser computes, not the text you wrote, and accepts these computed color spaces: sRGB, HSL, HWB, Lab, LCH, OKLab, OKLCH and Display P3.

If a color in the table above or `--pb-diagram-shadow` computes to another space, such as `color(xyz-d65 ...)`, `color(srgb-linear ...)` or `color(rec2020 ...)`, the diagram does not render; Plainbase shows its source and a status message instead. The shadow color is also probed and converted to sRGB.

Diagrams redraw when you switch display mode. Edits to diagram colors in `custom.css` appear after reloading the page.

Advice: Write diagram colors, and any mixes that feed them, in one of the supported spaces.

## Partial overrides with `custom.css`

In `DATA_DIR/custom.css`, a `:root` rule overrides inputs in both modes. For one mode, use the light selector `:where(:root:not([data-theme="dark"]))` or the dark selector `:where(:root[data-theme="dark"])`. These scope rules override equal-specificity definition rules by stylesheet order. Within `custom.css`, `:root` is more specific than either `:where(...)` selector.

For light, `:root:not([data-theme="dark"])` also works and wins by specificity. An equal-specificity rule in `custom.css` also wins over the app's rule.

Advice: If a rule against an editor class does not apply, raise its specificity. Editor classes are unversioned. Avoid named layers for overrides that need to beat unlayered app CSS.

## Selectors and private roles

Compatibility policy: `.pb-*` classes and `data-pb-*` attributes are stable hooks. Private `--pb-*` roles, Mermaid and CodeMirror classes, and Tailwind utilities are unversioned.

Advice: Prefer the public `--pb-theme-*` inputs and set them on the root. Avoid relying on a descendant-only input change to recompute derived colors.

## Loading, reloading and limits

Plainbase reads `theme.light` and `theme.dark` once, at startup; restart after changing either. Selected files and `custom.css` are read again for each GET request to their stylesheet endpoints, so edits appear when you reload the page.

If Plainbase cannot read a selected file, that mode serves the bundled definition. If it cannot read `custom.css`, there is no override: the sheet is empty.

Reasons include a missing file; an empty file or one containing only spaces, tabs, line feeds, form feeds or carriage returns, with or without a leading byte order mark; a file larger than `262144` bytes, including any byte order mark; invalid UTF-8; a symbolic link or directory at a selected stylesheet path; a symbolic link or directory at `DATA_DIR/custom.css`; or a file the server cannot open or read. A non-breaking space on its own is served as content.

A `custom.css` that is missing, empty or contains only those five whitespace characters, with or without a leading byte order mark, is silent. Other read failures log warnings naming the setting and its file name, or `custom.css`, and the reason. Read-failure warnings share a window of at most one warning per 60 seconds, with the first eligible failure logged immediately.

For files that pass these read limits, Plainbase serves the CSS as written, apart from removing one leading byte order mark; it does not validate CSS input values.

The generic layer, both definitions and `custom.css` are plain stylesheet links in the page head, after the app's own stylesheet. All four use the same link form, so a slow response from any of them can delay the interface's first appearance. Browser tests directly verify that delay for the dark definition and `custom.css`; the generic layer and light definition are not held in those tests.

## Security

Theme CSS is privileged and public. Anyone who can write `DATA_DIR` controls the interface, and anyone who can reach the server can read the theme endpoints, even before signing in.

Advice: Never put secrets in theme files.

Theme stylesheets run under the page's Content Security Policy. Fonts must come from Plainbase itself: remote and `data:` font URLs are blocked, as is `@import` of a remote stylesheet. Images referenced from CSS follow the page's image policy, which allows same-origin, `data:` and `https:` URLs.

`/_plainbase/` serves only the three fixed stylesheets: `theme-light.css`, `theme-dark.css` and `custom.css`. Every other path inside that namespace returns 404.

URLs in a selected definition or `custom.css` resolve against the stylesheet's address under `/_plainbase/`. A relative `url()` that stays inside that namespace returns 404 unless it names one of the three stylesheets. A reference that leaves the namespace, with `../` or a leading `/`, is an ordinary request to the rest of the site.

Advice: Use `https:` or `data:` URLs for CSS images.

## Deploying and editing

Within the read limits, an atomic-rename replacement yields a complete read of the old or new file.

Advice: Write a temporary file in `DATA_DIR` and rename it over the target. Avoid in-place writes that a request could observe partway through.

A selected stylesheet that is a symbolic link or directory is rejected and serves the bundled definition. A symbolic link or directory at `DATA_DIR/custom.css` is rejected as `NOT_REGULAR` and produces an empty sheet, with a warning subject to the read-failure warning limit. A regular selected file reached through a symlinked `DATA_DIR` base is accepted.

Advice: For Kubernetes ConfigMap or Secret volumes mounted as directories, copy each CSS file into `DATA_DIR` as a regular file, for example from an init container. Directory-mounted keys are symbolic links. A `subPath` mount presents a regular file but does not receive source updates, so plan how to refresh it.

Advice: Back up selected theme files and `custom.css`, plus `plainbase.conf` whenever it holds a `theme` block.

## Converting Base16 and Base24 schemes

### Base16 and Base24

The Tinted template emits the 18 inputs in this table. The remaining six use the generic defaults listed in [Inputs](#inputs).

| Input | Base16 | Base24 |
| --- | --- | --- |
| `--pb-theme-background` | `base00` | `base00` |
| `--pb-theme-foreground` | `base05` | `base05` |
| `--pb-theme-primary` | `base0D` | `base0D` |
| `--pb-theme-primary-foreground` | `base00` | `base00` |
| `--pb-theme-muted` | `base01` | `base01` |
| `--pb-theme-muted-foreground` | `base04` | `base04` |
| `--pb-theme-ring` | `base0D` | `base16` |
| `--pb-theme-selection` | `base02` | `base02` |
| `--pb-theme-selection-foreground` | `base05` | `base05` |
| `--pb-theme-destructive` | `base08` | `base12` |
| `--pb-theme-warning` | `base0A` | `base13` |
| `--pb-theme-success` | `base0B` | `base14` |
| `--pb-theme-syntax-keyword` | `base0E` | `base17` |
| `--pb-theme-syntax-string` | `base0B` | `base14` |
| `--pb-theme-syntax-comment` | `base04` | `base04` |
| `--pb-theme-syntax-literal` | `base09` | `base09` |
| `--pb-theme-syntax-title` | `base0D` | `base16` |
| `--pb-theme-syntax-attribute` | `base08` | `base12` |

A Base16 scheme converts without adding extra palette slots. Base24 refines eight public inputs from five additional slots. The conversion gate requires each source scheme to declare `variant: light` or `variant: dark`.

The bundled definitions and checked-in adapted examples are tested against the existing contrast rows.

Advice: Do not assume an arbitrary palette is accessible. Check its contrast in the places your readers use.

The examples reproduce from pinned sources. Their adaptations change palette values and the required adapted name and slug, while preserving the remaining scheme metadata. For each adjustment, the record lists exactly the contrast rows that fail when that slot alone is restored to its upstream value, with the other adjustments kept. The Gruvbox light example keeps its upstream `base05`.

See [Tinted reproduction instructions](../contrib/tinted/README.md#reproduction). The conversion gate uses the executable and version pinned by Gradle in [frontend/build.gradle.kts](../frontend/build.gradle.kts).

Architecture policy: Scheme conversion belongs in offline build tooling. Deploy the resulting CSS definitions; keep the builder outside the application distribution.

## Compatibility

Compatibility policy: The public `--pb-theme-*` inputs and stable `.pb-*` and `data-pb-*` hooks form the version 1 contract. Renaming, removing or repurposing an input or stable hook requires a documented versioned migration.
