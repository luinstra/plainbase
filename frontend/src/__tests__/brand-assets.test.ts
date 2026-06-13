// @vitest-environment node
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const repoRoot = path.resolve(frontendRoot, "..");

const svgFiles = [
  ["mark", path.join(repoRoot, "assets", "brand", "plainbase-mark.svg")],
  ["logo", path.join(repoRoot, "assets", "brand", "plainbase-logo.svg")],
  ["logo-dark", path.join(repoRoot, "assets", "brand", "plainbase-logo-dark.svg")],
  ["favicon", path.join(frontendRoot, "public", "favicon.svg")],
] as const;

const pngFiles = [
  ["mark", path.join(repoRoot, "assets", "brand", "plainbase-mark.png"), 512, 512],
  ["apple-touch-icon", path.join(frontendRoot, "public", "apple-touch-icon.png"), 180, 180],
] as const;

function read(file: string): string {
  return readFileSync(file, "utf8");
}

describe("Plainbase brand assets", () => {
  it.each(svgFiles)("ships an accessible, font-independent %s SVG", (_name, file) => {
    expect(existsSync(file), `${file} must exist`).toBe(true);
    const svg = read(file);
    expect(svg).toContain("<svg");
    expect(svg).toContain("<title");
    expect(svg).toContain("<desc");
    expect(svg).toMatch(/viewBox="0 0 [\d.]+ [\d.]+"/);
    // Letters are outlined to paths so rendering never depends on a font being installed.
    expect(svg).not.toContain("<text");
  });

  it.each(pngFiles)("ships a rendered %s PNG", (_name, file, width, height) => {
    expect(existsSync(file), `${file} must exist`).toBe(true);
    const png = readFileSync(file);
    expect(png.subarray(0, 8)).toEqual(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
    expect(png.readUInt32BE(16)).toBe(width);
    expect(png.readUInt32BE(20)).toBe(height);
  });

  it("wires the SVG favicon and apple-touch-icon through the Vite index", () => {
    const html = read(path.join(frontendRoot, "index.html"));
    expect(html).toContain('<link rel="icon" type="image/svg+xml" href="/favicon.svg" />');
    expect(html).toContain('<link rel="apple-touch-icon" href="/apple-touch-icon.png" />');
  });

  it("leads the README with the light/dark wordmark as the masthead", () => {
    const readme = read(path.join(repoRoot, "README.md"));
    // The wordmark is the title (its alt is the accessible name), so it must be the very
    // first thing in the file — there is no separate textual H1 to compete with it.
    expect(readme.trimStart().startsWith("<picture>")).toBe(true);
    expect(readme).toContain('<source media="(prefers-color-scheme: dark)" srcset="assets/brand/plainbase-logo-dark.svg" />');
    // Pin the src + alt (load-bearing); width is a free layout knob, so match any value.
    expect(readme).toMatch(/<img src="assets\/brand\/plainbase-logo\.svg" alt="Plainbase" width="\d+" \/>/);
  });
});
