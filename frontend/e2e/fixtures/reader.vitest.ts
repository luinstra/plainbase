// @vitest-environment node
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { assert, expect, it } from "vitest";

function ids(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return ids(full);
    if (!entry.name.endsWith(".md")) return [];
    const match = readFileSync(full, "utf8").match(/^id: (.+)$/m);
    assert(match, `${full}: expected a frontmatter id`);
    return [match[1]];
  });
}

it("keeps reader corpus page identifiers unique", () => {
  const corpus = fileURLToPath(new URL("./reader", import.meta.url));
  const identifiers = ids(corpus);
  expect(identifiers.length).toBeGreaterThan(5);
  expect(new Set(identifiers).size).toBe(identifiers.length);
});

it("identifies a fixture that is missing its frontmatter id", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "reader-fixture-id-"));
  const file = path.join(dir, "missing.md");
  try {
    writeFileSync(file, "---\ntitle: Missing id\n---\n");
    expect(() => ids(dir)).toThrow(`${file}: expected a frontmatter id`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
