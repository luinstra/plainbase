# Plainbase Logo and Web Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the approved Plainbase file-spine logo assets, wire the web favicon, and show the restrained GitHub README lockup.

**Architecture:** Treat brand assets as static source files. A small filesystem-based Vitest contract test verifies the required SVG files, favicon link, README placement, and accessible SVG metadata; visual quality is checked by inspecting the SVGs and reviewing the rendered shapes at small sizes.

**Tech Stack:** SVG, Vite public static assets, README Markdown/HTML, Vitest with Node filesystem reads.

---

### Task 1: Brand Asset Contract and Implementation

**Files:**
- Create: `frontend/src/__tests__/brand-assets.test.ts`
- Create: `assets/brand/plainbase-mark.svg`
- Create: `assets/brand/plainbase-logo.svg`
- Create: `frontend/public/favicon.svg`
- Modify: `frontend/index.html`
- Modify: `README.md`

- [ ] **Step 1: Write the failing brand asset test**

Create `frontend/src/__tests__/brand-assets.test.ts` with this exact content:

```ts
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
  ["favicon", path.join(frontendRoot, "public", "favicon.svg")],
] as const;

function read(file: string): string {
  return readFileSync(file, "utf8");
}

describe("Plainbase brand assets", () => {
  it.each(svgFiles)("ships an accessible %s SVG", (_name, file) => {
    expect(existsSync(file), `${file} must exist`).toBe(true);
    const svg = read(file);
    expect(svg).toContain("<svg");
    expect(svg).toContain("<title");
    expect(svg).toContain("<desc");
    expect(svg).toMatch(/viewBox="0 0 \d+ \d+"/);
  });

  it("wires the SVG favicon through the Vite index", () => {
    const html = read(path.join(frontendRoot, "index.html"));
    expect(html).toContain('<link rel="icon" type="image/svg+xml" href="/favicon.svg" />');
  });

  it("uses the horizontal lockup as the first README element", () => {
    const readme = read(path.join(repoRoot, "README.md"));
    const firstLine = readme.split(/\r?\n/).find((line) => line.trim().length > 0);
    expect(firstLine).toBe('<img src="assets/brand/plainbase-logo.svg" alt="Plainbase" width="240" />');
  });
});
```

- [ ] **Step 2: Run the test and verify RED**

Run from `frontend/`:

```bash
rtk npm test -- src/__tests__/brand-assets.test.ts
```

Expected: FAIL. The failure should report that `assets/brand/plainbase-mark.svg`, `assets/brand/plainbase-logo.svg`, or `frontend/public/favicon.svg` does not exist, and may also report the missing favicon link and README lockup.

- [ ] **Step 3: Add the brand SVG assets**

Create `assets/brand/plainbase-mark.svg` with this exact content:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-labelledby="plainbase-mark-title plainbase-mark-desc">
  <title id="plainbase-mark-title">Plainbase mark</title>
  <desc id="plainbase-mark-desc">Two plain file slabs anchored to a stable vertical base.</desc>
  <style>
    .pb-mark { fill: #2563eb; }
    @media (prefers-color-scheme: dark) { .pb-mark { fill: #60a5fa; } }
  </style>
  <rect class="pb-mark" x="10" y="8" width="14" height="48" rx="4" />
  <rect class="pb-mark" x="22" y="12" width="32" height="18" rx="4" />
  <rect class="pb-mark" x="22" y="34" width="28" height="18" rx="4" />
</svg>
```

Create `assets/brand/plainbase-logo.svg` with this exact content:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="260" height="64" viewBox="0 0 260 64" role="img" aria-labelledby="plainbase-logo-title plainbase-logo-desc">
  <title id="plainbase-logo-title">Plainbase</title>
  <desc id="plainbase-logo-desc">Plainbase wordmark with a file-spine mark.</desc>
  <style>
    .pb-logo-mark { fill: #2563eb; }
    .pb-logo-word {
      fill: #18181b;
      font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
      font-size: 32px;
      font-weight: 700;
      letter-spacing: 0;
    }
    @media (prefers-color-scheme: dark) {
      .pb-logo-mark { fill: #60a5fa; }
      .pb-logo-word { fill: #f4f4f5; }
    }
  </style>
  <g aria-hidden="true">
    <rect class="pb-logo-mark" x="6" y="8" width="14" height="48" rx="4" />
    <rect class="pb-logo-mark" x="18" y="12" width="32" height="18" rx="4" />
    <rect class="pb-logo-mark" x="18" y="34" width="28" height="18" rx="4" />
  </g>
  <text class="pb-logo-word" x="70" y="43">Plainbase</text>
</svg>
```

Create `frontend/public/favicon.svg` with this exact content:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-labelledby="plainbase-favicon-title plainbase-favicon-desc">
  <title id="plainbase-favicon-title">Plainbase</title>
  <desc id="plainbase-favicon-desc">File-spine icon on a blue square.</desc>
  <rect width="64" height="64" rx="14" fill="#2563eb" />
  <rect x="14" y="12" width="13" height="40" rx="4" fill="#ffffff" />
  <rect x="25" y="16" width="27" height="15" rx="4" fill="#ffffff" />
  <rect x="25" y="35" width="24" height="15" rx="4" fill="#ffffff" />
</svg>
```

- [ ] **Step 4: Wire the favicon in `frontend/index.html`**

Modify the `<head>` in `frontend/index.html` so the viewport line is followed by the favicon link:

```html
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <title>Plainbase</title>
```

- [ ] **Step 5: Add the GitHub README lockup**

Modify the top of `README.md` so the first non-empty line is the image tag and the existing heading remains:

```md
<img src="assets/brand/plainbase-logo.svg" alt="Plainbase" width="240" />

# Plainbase
```

Keep the existing positioning quote and quickstart content below the heading unchanged.

- [ ] **Step 6: Run the brand asset test and verify GREEN**

Run from `frontend/`:

```bash
rtk npm test -- src/__tests__/brand-assets.test.ts
```

Expected: PASS. The output should show `frontend/src/__tests__/brand-assets.test.ts` passing with 5 tests.

- [ ] **Step 7: Commit the brand asset implementation**

Stage only the files from this task:

```bash
rtk git add frontend/src/__tests__/brand-assets.test.ts assets/brand/plainbase-mark.svg assets/brand/plainbase-logo.svg frontend/public/favicon.svg frontend/index.html README.md
rtk git diff --staged --stat
rtk git commit -m "feat(brand): add Plainbase logo assets"
```

Expected staged stat: one new test file, three SVG files, and two modified existing files. The commit must not include active server/search worktree files.

### Task 2: Final Verification

**Files:**
- Read: `assets/brand/plainbase-mark.svg`
- Read: `assets/brand/plainbase-logo.svg`
- Read: `frontend/public/favicon.svg`
- Read: `frontend/index.html`
- Read: `README.md`

- [ ] **Step 1: Confirm required SVG metadata and shapes**

Run from the repository root:

```bash
rtk rg -n "<title|<desc|<rect|<text|viewBox" assets/brand/plainbase-mark.svg assets/brand/plainbase-logo.svg frontend/public/favicon.svg
```

Expected: each SVG reports a `viewBox`, a `<title>`, a `<desc>`, and filled shape elements. `plainbase-logo.svg` also reports a `<text>` element containing `Plainbase`.

- [ ] **Step 2: Confirm README and favicon references**

Run from the repository root:

```bash
rtk rg -n "plainbase-logo.svg|favicon.svg" README.md frontend/index.html
```

Expected:

```text
README.md:1:<img src="assets/brand/plainbase-logo.svg" alt="Plainbase" width="240" />
frontend/index.html:6:    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
```

- [ ] **Step 3: Run the frontend build**

Run from `frontend/`:

```bash
rtk npm run build
```

Expected: exit code 0. The build should typecheck and produce Vite output without errors.

- [ ] **Step 4: Review repository status**

Run from the repository root:

```bash
rtk git status --short
```

Expected: only pre-existing active server/search worktree changes remain. No uncommitted brand files should remain after Task 1's commit.
