// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

const rootLiteralCases = [
  {
    label: "/docs",
    pattern: /\/docs/,
    samples: [
      'redirect({ to: "/docs" })',
      'navigate("/docs?mode=edit")',
      'navigate("/docs#section")',
      'const target = "/docs',
      'const href = `/docs${suffix}`',
      "// never hardcode /docs here",
    ],
  },
  {
    label: "main root literal",
    pattern: /["'`]main["'`]/,
    samples: ['entryFor(roots, "main")', "entryFor(roots, 'main')", "entryFor(roots, `main`)", '// the root was "main"'],
  },
] as const;

function sourceFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "__tests__" || entry.name === "__fixtures__" ? [] : sourceFiles(full);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name) ? [full] : [];
  });
}

const files = sourceFiles(path.join(frontendRoot, "src"));

describe("frontend root literal gate", () => {
  it("the gate patterns catch every reviewed root-literal boundary", () => {
    rootLiteralCases.forEach(({ label, pattern, samples }) => {
      samples.forEach((sample) => {
        expect(
          pattern.test(sample),
          `${label} sample ${JSON.stringify(sample)} must be visible to the gate`,
        ).toBe(true);
      });
    });

    expect(rootLiteralCases[1].pattern.test('folderTitle(folder) || "handbook"')).toBe(false);
  });

  it("scans a known, non-trivial production source population", () => {
    expect(files.length).toBeGreaterThan(40);
    expect(files).toContain(path.join(frontendRoot, "src", "router.tsx"));
    expect(files).toContain(path.join(frontendRoot, "src", "components", "PageView.tsx"));
  });

  it("no production source contains /docs or an exact quoted main root literal, including in comments", () => {
    const violations = files.flatMap((file) => {
      const lines = readFileSync(file, "utf8").split(/\r?\n/);
      return lines.flatMap((line, index) => {
        const matches = rootLiteralCases.filter(({ pattern }) => pattern.test(line)).map(({ label }) => label);
        return matches.length === 0 ? [] : [`${path.relative(frontendRoot, file)}:${index + 1} (${matches.join(", ")})`];
      });
    });

    expect(violations, `root literals found while scanning ${files.length} production source files`).toEqual([]);
  });
});
