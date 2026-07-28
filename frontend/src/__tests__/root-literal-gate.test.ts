// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

const rootLiteralCases = [
  {
    label: "/docs",
    pattern: /["'`]\/docs(?![\w-])/,
    samples: [
      'redirect({ to: "/docs" })',
      'navigate("/docs?mode=edit")',
      'navigate("/docs#section")',
      'const target = "/docs',
      'const href = `/docs${suffix}`',
    ],
  },
  { label: '"docs"', pattern: /["'`]docs["'`]/, samples: ['folderTitle(folder) || "docs"'] },
] as const;

function sourceFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "__tests__" || entry.name === "__fixtures__" ? [] : sourceFiles(full);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name) ? [full] : [];
  });
}

function stripComments(source: string): string {
  let mode: "code" | "line" | "block" | "single" | "double" | "template" = "code";
  let escaped = false;
  let stripped = "";

  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];
    const next = source[index + 1];

    if (mode === "code") {
      if (char === "/" && next === "/") {
        stripped += "  ";
        mode = "line";
        index += 1;
      } else if (char === "/" && next === "*") {
        stripped += "  ";
        mode = "block";
        index += 1;
      } else {
        stripped += char;
        if (char === "'") mode = "single";
        if (char === '"') mode = "double";
        if (char === "`") mode = "template";
      }
      continue;
    }

    if (mode === "line") {
      if (char === "\n" || char === "\r") {
        stripped += char;
        mode = "code";
      } else {
        stripped += " ";
      }
      continue;
    }

    if (mode === "block") {
      if (char === "*" && next === "/") {
        stripped += "  ";
        mode = "code";
        index += 1;
      } else {
        stripped += char === "\n" || char === "\r" ? char : " ";
      }
      continue;
    }

    if (escaped) {
      stripped += char;
      escaped = false;
    } else if (char === "\\") {
      stripped += char;
      escaped = true;
    } else {
      stripped += char;
      if (
        (mode === "single" && char === "'") ||
        (mode === "double" && char === '"') ||
        (mode === "template" && char === "`")
      ) {
        mode = "code";
      }
    }
  }

  return stripped;
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

    [
      'navigate("/docsomething")',
      'navigate("/docs-foo")',
    ].forEach((sample) => {
      expect(rootLiteralCases[0].pattern.test(sample), `${sample} must not be a /docs match`).toBe(false);
    });

    expect(rootLiteralCases[1].pattern.test('folderTitle(folder) || "handbook"')).toBe(false);
  });

  it("preserves strings containing comment markers while stripping comments", () => {
    const source = 'const x = "https://example.com"; const y = "/docs"; // trailing comment';
    const stripped = stripComments(source);

    expect(stripped).toContain('const x = "https://example.com"; const y = "/docs";');
    expect(stripped).not.toContain("trailing comment");
    expect(rootLiteralCases.some(({ pattern }) => pattern.test(stripped))).toBe(true);
  });

  it("scans a known, non-trivial production source population", () => {
    expect(files.length).toBeGreaterThan(40);
    expect(files).toContain(path.join(frontendRoot, "src", "router.tsx"));
    expect(files).toContain(path.join(frontendRoot, "src", "components", "PageView.tsx"));
  });

  it("no production source embeds a quoted /docs URL or exact docs string literal", () => {
    const violations = files.flatMap((file) => {
      const lines = stripComments(readFileSync(file, "utf8")).split(/\r?\n/);
      return lines.flatMap((line, index) => {
        const matches = rootLiteralCases.filter(({ pattern }) => pattern.test(line)).map(({ label }) => label);
        return matches.length === 0 ? [] : [`${path.relative(frontendRoot, file)}:${index + 1} (${matches.join(", ")})`];
      });
    });

    expect(violations, `root literals found while scanning ${files.length} production source files`).toEqual([]);
  });
});
