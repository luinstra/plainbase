// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { declarations, topLevelBlocks } from "./contrastResolver";

/**
 * Color definitions stay in the bundled light and dark theme definitions.
 *
 *  1. Color LITERALS (hex, rgb()/hsl()/oklch() with numeric args) live ONLY in
 *     src/styles/themes/light.css and src/styles/themes/dark.css.
 *     The generic definition carries no literals or primitives.
 *  2. PRIMITIVE tokens (--pb-<hue>-<step>) are consumed only inside those same paths;
 *     components and app.css reference the semantic tier exclusively, so dark mode and
 *     runtime theme overrides stay a pure semantic-layer swap.
 *  3. NAMED colors (`color: red`, `background: white`) are color literals too; flagged
 *     in color-bearing property positions, CSS and inline-style objects alike.
 */

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const SANCTIONED_COLOR_FILES = new Set([
  path.join("src", "styles", "themes", "light.css"),
  path.join("src", "styles", "themes", "dark.css"),
]);
const sanctionedPaths = [...SANCTIONED_COLOR_FILES].join(", ");

const HEX_COLOR = /(^|[^0-9A-Za-z&-])#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})(?![0-9A-Za-z])/;
const COLOR_FUNCTION = /\b(?:rgba?|hsla?|oklch|oklab|lab|lch|hwb)\(\s*[\d.]/i;
// Hue list mirrors the built-in primitive palettes; keep in sync when adding a hue.
const PRIMITIVE_TOKEN = /--pb-(?:white|black|gray|warm|teal|blue|red|green|amber|violet)\b/;

// Named colors are only flagged in color-bearing property positions (`color: red` in CSS,
// `fill: "red"` in an inline-style object); prose and identifiers that merely contain a
// color word stay legal. `transparent`, `currentColor`, `none`, and the CSS-wide keywords
// (inherit/initial/unset/revert) are not in the list, so they pass. Hyphens are optional
// to catch camelCase inline-style props (`backgroundColor`).
const COLOR_PROPERTIES = [
  "color",
  "background(?:-?color)?",
  "border(?:-?(?:top|right|bottom|left|block|inline)(?:-?(?:start|end))?)?-?color",
  "outline-?color",
  "fill",
  "stroke",
  "caret-?color",
  "accent-?color",
  "text-?decoration-?color",
  "column-?rule-?color",
].join("|");
const CSS_NAMED_COLORS = [
  "aliceblue|antiquewhite|aqua|aquamarine|azure|beige|bisque|black|blanchedalmond|blue|blueviolet|brown|burlywood|cadetblue",
  "chartreuse|chocolate|coral|cornflowerblue|cornsilk|crimson|cyan|darkblue|darkcyan|darkgoldenrod|darkgray|darkgreen|darkgrey",
  "darkkhaki|darkmagenta|darkolivegreen|darkorange|darkorchid|darkred|darksalmon|darkseagreen|darkslateblue|darkslategray",
  "darkslategrey|darkturquoise|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|firebrick|floralwhite|forestgreen",
  "fuchsia|gainsboro|ghostwhite|gold|goldenrod|gray|green|greenyellow|grey|honeydew|hotpink|indianred|indigo|ivory|khaki",
  "lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan|lightgoldenrodyellow|lightgray|lightgreen",
  "lightgrey|lightpink|lightsalmon|lightseagreen|lightskyblue|lightslategray|lightslategrey|lightsteelblue|lightyellow|lime",
  "limegreen|linen|magenta|maroon|mediumaquamarine|mediumblue|mediumorchid|mediumpurple|mediumseagreen|mediumslateblue",
  "mediumspringgreen|mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin|navajowhite|navy|oldlace|olive",
  "olivedrab|orange|orangered|orchid|palegoldenrod|palegreen|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum",
  "powderblue|purple|rebeccapurple|red|rosybrown|royalblue|saddlebrown|salmon|sandybrown|seagreen|seashell|sienna|silver",
  "skyblue|slateblue|slategray|slategrey|snow|springgreen|steelblue|tan|teal|thistle|tomato|turquoise|violet|wheat|white",
  "whitesmoke|yellow|yellowgreen",
].join("|");
const NAMED_COLOR = new RegExp(`(?:^|[^-\\w])(?:${COLOR_PROPERTIES})\\s*:\\s*["'\`]?(?:${CSS_NAMED_COLORS})\\b`, "i");

function sourceFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "__snapshots__" ? [] : sourceFiles(full);
    return /\.(ts|tsx|css|html)$/.test(entry.name) ? [full] : [];
  });
}

const files = [...sourceFiles(path.join(frontendRoot, "src")), path.join(frontendRoot, "index.html"), path.join(frontendRoot, "e2e")]
  .flatMap((p) => (p.endsWith("e2e") ? sourceFiles(p) : [p]))
  .filter((p) => !p.endsWith(path.join("__tests__", "token-discipline.test.ts")));

describe("token discipline", () => {
  it("scans a non-trivial file set", () => {
    expect(files.length).toBeGreaterThan(10);
  });

  it.each(files.map((f) => [path.relative(frontendRoot, f)] as const))("%s carries no color literals", (relative) => {
    if (SANCTIONED_COLOR_FILES.has(relative)) return;
    const lines = readFileSync(path.join(frontendRoot, relative), "utf8").split("\n");
    const offending = lines.filter((line) => HEX_COLOR.test(line) || COLOR_FUNCTION.test(line));
    expect(offending, `color literals belong in ${sanctionedPaths}`).toEqual([]);
  });

  it.each(files.map((f) => [path.relative(frontendRoot, f)] as const))("%s uses no named CSS colors", (relative) => {
    if (SANCTIONED_COLOR_FILES.has(relative)) return;
    const lines = readFileSync(path.join(frontendRoot, relative), "utf8").split("\n");
    const offending = lines.filter((line) => NAMED_COLOR.test(line));
    expect(offending, `named colors are color literals; they belong in ${sanctionedPaths}`).toEqual([]);
  });

  it.each(files.map((f) => [path.relative(frontendRoot, f)] as const))("%s references no primitive tokens", (relative) => {
    if (SANCTIONED_COLOR_FILES.has(relative)) return;
    const lines = readFileSync(path.join(frontendRoot, relative), "utf8").split("\n");
    const offending = lines.filter((line) => PRIMITIVE_TOKEN.test(line));
    expect(offending, `primitive tokens belong in ${sanctionedPaths}; other files consume the semantic tier only`).toEqual([]);
  });

  it("the dark definition declares its semantic tier in the exclusive dark scope", () => {
    const blocks = topLevelBlocks(readFileSync(path.join(frontendRoot, "src", "styles", "themes", "dark.css"), "utf8"));
    expect(blocks.map((block) => block.selector)).toEqual([':where(:root[data-theme="dark"])']);
    const values = declarations(blocks[0].body);
    for (const semantic of ["--pb-surface", "--pb-text", "--pb-link", "--pb-link-broken", "--pb-code-bg"]) {
      expect(values).toHaveProperty(semantic);
    }
  });

  it("the light definition anchors the accent and warmth in its exclusive scope", () => {
    const blocks = topLevelBlocks(readFileSync(path.join(frontendRoot, "src", "styles", "themes", "light.css"), "utf8"));
    expect(blocks.map((block) => block.selector)).toEqual([':where(:root:not([data-theme="dark"]))']);
    const values = declarations(blocks[0].body);
    expect(values["--pb-theme-primary"]).toBe("var(--pb-teal-700)");
    expect(values["--pb-warmth"]).toBe("5%");
    expect(values).toHaveProperty("--pb-teal-700");
    expect(values).toHaveProperty("--pb-warm-50");
  });
});
