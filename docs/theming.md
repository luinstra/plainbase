# Custom themes

## What a theme is

Select a CSS definition or a Tinted Base16 or Base24 scheme (`.yaml` or `.yml`) with `theme.light` and `theme.dark` in `DATA_DIR/plainbase.conf`. Files may sit directly in `DATA_DIR` or directly in `DATA_DIR/themes`. Plainbase converts a selected scheme to CSS for that mode. An optional `DATA_DIR/custom.css` adds partial overrides.

The selected source file and the reader's light or dark display mode are separate concepts.

See [theme settings](configuration.md#themes) and the [theme decision](decisions/0014-custom-themes-are-css-definitions-selected-from-data-dir.md).

## Quick start

1. Copy [the Gruvbox light example](../contrib/tinted/generated/base16-gruvbox-light-hard-adapted.css) to `DATA_DIR/gruvbox.css`.
2. Set this in `DATA_DIR/plainbase.conf`:

   ```hocon
   theme.light = "gruvbox.css"
   ```

3. Restart Plainbase.

With only `theme.light` set, dark uses the bundled dark definition.

### Quick start with a Tinted scheme

1. Advice: Create a real `DATA_DIR/themes` directory and copy [the pinned upstream Dracula scheme](https://github.com/tinted-theming/schemes/blob/3ac91137e879502c442ab89ba0f1a98077d0da9d/base16/dracula.yaml) to `DATA_DIR/themes/dracula.yaml`.
2. Set this in `DATA_DIR/plainbase.conf`:

   ```hocon
   theme.dark = "themes/dracula.yaml"
   ```

3. Restart Plainbase and choose dark display mode.

Plainbase converts that file when its dark stylesheet endpoint is requested; later file edits appear on reload. The file declares a dark variant, so selecting it for light instead serves the bundled light definition.

Policy: Raw upstream schemes carry no contrast guarantee.

Advice: Check contrast where your readers use the theme, or start with the adapted CSS examples.

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

For CSS files that pass the read limits, Plainbase does not validate input values; it serves the CSS as written, apart from removing one leading byte order mark. Scheme files are converted instead. Diagrams accept a narrower set of colors; see [Diagrams](#diagrams).

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

Plainbase reads `theme.light` and `theme.dark` once, at startup; restart after changing either. Selected files and `custom.css` are read again for each GET request to their stylesheet endpoints, and scheme files are converted again, so edits appear when you reload the page.

If Plainbase cannot read or convert a selected file, that mode serves the bundled definition. If it cannot read `custom.css`, there is no override: the sheet is empty.

CSS and scheme files share the same read checks. A selected file falls back to the bundled definition if any of these checks fails:

- The file exists and is a regular file, not a symbolic link or directory.
- Its size is at most `262144` bytes, including any leading byte order mark.
- It contains valid UTF-8.
- After removal of one leading byte order mark, it is not empty or made only of spaces, tabs, line feeds, form feeds or carriage returns.
- The server can open and read it.

For a `themes/` selection, a symbolic link or non-directory at `DATA_DIR/themes` also causes fallback with `NOT_REGULAR`. The same leaf read limits apply to `DATA_DIR/custom.css`. A non-breaking space on its own passes the read check and is served as CSS content, but it is not a valid scheme document.

A `custom.css` that is missing, empty or contains only those five whitespace characters, with or without a leading byte order mark, is silent. Other read failures log warnings naming the setting and its file name, or `custom.css`, and the reason. Read and conversion failures share a window of at most one warning per 60 seconds, with the first eligible failure logged immediately.

For CSS files that pass these read limits, Plainbase serves the CSS as written, apart from removing one leading byte order mark; it does not validate CSS input values. For scheme files, the same leading byte order mark is removed before conversion; source text is never served as written.

The generic layer, both definitions and `custom.css` are plain stylesheet links in the page head, after the app's own stylesheet. All four use the same link form, so a slow response from any of them can delay the interface's first appearance. Browser tests directly verify that delay for the dark definition and `custom.css`; the generic layer and light definition are not held in those tests.

## Security

Theme CSS is privileged and public. Anyone who can write `DATA_DIR` controls the interface, and anyone who can reach the server can read the theme endpoints, even before signing in.

Advice: Never put secrets in theme files.

Theme stylesheets run under the page's Content Security Policy. Fonts must come from Plainbase itself: remote and `data:` font URLs are blocked, as is `@import` of a remote stylesheet. Images referenced from CSS follow the page's image policy, which allows same-origin, `data:` and `https:` URLs.

`/_plainbase/` serves only the three fixed stylesheets: `theme-light.css`, `theme-dark.css` and `custom.css`. Every other path inside that namespace returns 404.

Converted scheme CSS contains only the selected mode's selector and 18 declarations with six-digit hex colors. Names, authors, descriptions, slugs, comments and other source text never reach the converted stylesheet.

When `DATA_DIR` is inside a content root, its whole subtree, including `DATA_DIR/themes`, is excluded from that root's index, content assets, file watching and page creation.

A content root or `CONTENT_DIR` at `DATA_DIR/themes`, in any letter case, refuses to start; `plainbase root add` refuses it too. Other root locations inside `DATA_DIR` remain warning-only. A theme selection reaches only files directly in `DATA_DIR` or directly in `DATA_DIR/themes`.

URLs in a selected definition or `custom.css` resolve against the stylesheet's address under `/_plainbase/`. A relative `url()` that stays inside that namespace returns 404 unless it names one of the three stylesheets. A reference that leaves the namespace, with `../` or a leading `/`, is an ordinary request to the rest of the site.

Advice: Use `https:` or `data:` URLs for CSS images.

## Deploying and editing

Within the read limits, an atomic-rename replacement yields a complete read of the old or new file.

Advice: Write a temporary file in `DATA_DIR` and rename it over the target. Avoid in-place writes that a request could observe partway through.

A selected CSS or scheme file that is a symbolic link or directory is rejected and serves the bundled definition. For a `themes/` selection, `DATA_DIR/themes` must itself be a real directory; a symbolic link or non-directory there serves the bundled definition as `NOT_REGULAR`. A symbolic link or directory at `DATA_DIR/custom.css` is rejected as `NOT_REGULAR` and produces an empty sheet, with a warning subject to the shared warning limit. A regular selected file reached through a symlinked `DATA_DIR` base is still accepted.

Advice: For Kubernetes ConfigMap or Secret volumes mounted as directories, copy each CSS or scheme file into `DATA_DIR` or a real `DATA_DIR/themes` directory as a regular file, for example from an init container. Directory-mounted keys are symbolic links. A `subPath` mount presents a regular file but does not receive source updates, so plan how to refresh it.

Advice: Back up selected theme files and `custom.css`, plus `plainbase.conf` whenever it holds a `theme` block.

## Tinted scheme files

Plainbase reads a restricted scheme form with required `name`, `author`, `system` and `palette` fields. The `system` must be `base16` or `base24`, with exactly that system's palette slots. Unknown keys are ignored only when their values fit the accepted scalar forms; flow and nested values are rejected.

A declared `variant` must match the selected mode: `light` for `theme.light`, `dark` for `theme.dark`. A scheme without a variant works in either mode. A declared `slug` must use lowercase ASCII letters and digits in hyphen-separated groups.

Scheme files may use LF or CRLF line endings; a lone carriage return is rejected. As for CSS, one leading byte order mark is removed before conversion.

For every scheme at [the pinned tinted-theming/schemes commit](https://github.com/tinted-theming/schemes/tree/3ac91137e879502c442ab89ba0f1a98077d0da9d), selected for the mode its variant declares, the converted declarations equal the reference Tinted template's declarations. Those schemes use none of the forms in the rejected-forms table below. Plainbase rejects those forms although the Tinted builder accepts some of them.

### Scheme failure reasons

A scheme that cannot be converted serves the bundled definition for that mode. An eligible warning names the setting, file name and reason, with a line number when known; it never includes a value or file content. Conversion failures share the read-failure warning limit described above.

| Reason | Meaning |
| --- | --- |
| `SCHEME_SYNTAX` | Unsupported characters or document structure, invalid scalar or key forms, duplicate keys or palette slots, a missing name or author, or an invalid slug. Palette indentation errors, a space before a palette colon, a TAB immediately after it, or a value with no separating space also report this reason. |
| `SCHEME_SYSTEM` | Missing system or a system other than `base16` or `base24`. |
| `SCHEME_VARIANT` | A declared variant other than `light` or `dark`. |
| `SCHEME_PALETTE` | A missing or empty palette, unexpected or missing slots, a palette supplied as a value instead of entries, an invalid palette value form, or a color outside the accepted six-digit form. |
| `VARIANT_MISMATCH` | An otherwise valid scheme declares a variant different from the selected mode. |

### Rejected forms

`SP` in the table means an ordinary ASCII space.

Keys use only ASCII letters, digits, `_` and `-`, start with an ASCII letter, are 1 to 1024 characters, and are not words such as `yes` or `no`; a palette entry is exactly two spaces, a slot name, `:`, one or more SP and a double-quoted value.

| Rejected form |
| --- |
| A carriage return that is not part of a CRLF line ending |
| The characters U+0085, U+2028 and U+2029, anywhere in the file |
| document markers other than `---` alone on the first line; keys that are quoted, longer than 1024 characters, do not start with an ASCII letter, are words such as `yes` or `no`, or have a space before the colon; a tab instead of a space after a colon; a line starting with U+FEFF; palette entries indented by anything but two spaces; an indented line after a blank line inside a `\|` block; and a plain value continued on an indented line |
| Values other than a double-quoted value on one line without backslashes, an unquoted value starting with an ASCII letter, or a `\|` block: for example single quotes, escapes, `[ ]` or `{ }` values, anchors, aliases, tags, `>` blocks, `\|` blocks with `-` or `+`, empty `\|` blocks, a `\|` block as the value of `system`, `variant` or `slug`, unquoted values containing `#` or a tab, unquoted words such as `null`, `yes`, `no`, `on`, `inf` or `nan`, and a `#` comment with no space before it |
| A key other than `palette` with nothing after its colon, including one followed by indented entries |
| Palette entries other than the system's slots, such as `base10` to `base17` in a Base16 scheme |
| Palette values other than a double-quoted `#` followed by six hex digits, such as a missing `#`, three-digit shorthand, an unquoted or single-quoted value, an anchor or alias, a comment with no space before its `#`, or a palette written as `{ }` |
| A `slug` other than lowercase ASCII letters and digits in hyphen-separated groups, such as `My_Theme`, `a/b`, `a b` or an empty slug |

## Converting Base16 and Base24 schemes

### Base16 and Base24

The Tinted template is the reference mapping for runtime conversion and the oracle used to check its output. Runtime conversion emits the 18 inputs in this table in the selected mode's exclusive scope. The remaining six use the generic defaults listed in [Inputs](#inputs).

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

A Base16 scheme converts without adding extra palette slots. Base24 refines eight public inputs from five additional slots. The offline example gate requires each source scheme to declare `variant: light` or `variant: dark`; runtime conversion accepts a scheme without a variant in either mode.

The bundled definitions and checked-in adapted examples are tested against the existing contrast rows.

Advice: Do not assume an arbitrary palette is accessible. Check its contrast in the places your readers use.

The examples reproduce from pinned sources. Their adaptations change palette values and the required adapted name and slug, while preserving the remaining scheme metadata. For each adjustment, the record lists exactly the contrast rows that fail when that slot alone is restored to its upstream value, with the other adjustments kept. The Gruvbox light example keeps its upstream `base05`.

See [Tinted reproduction instructions](../contrib/tinted/README.md#reproduction). The offline example and oracle checks use the executable and version pinned by Gradle in [frontend/build.gradle.kts](../frontend/build.gradle.kts).

Architecture policy: The GPL Tinted builder stays in offline tooling outside the application distribution. Plainbase converts selected schemes with its own code, checked against the template's builder output.

## Compatibility

Compatibility policy: The public `--pb-theme-*` inputs and stable `.pb-*` and `data-pb-*` hooks form the version 1 contract. Renaming, removing or repurposing an input or stable hook requires a documented versioned migration.
