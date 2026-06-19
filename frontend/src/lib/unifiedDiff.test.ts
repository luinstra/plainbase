import { describe, expect, it } from "vitest";
import { languageForPath, MAX_DIFF_RENDER_CHARS, parseUnifiedDiff } from "./unifiedDiff";

/**
 * W7 (D-5/MF-3) unified-diff parser goldens. The load-bearing property is META-FIRST classification:
 * the `---`/`+++` file headers begin with `-`/`+` and MUST classify as `meta`, not del/add. Plus the
 * edge lines (`\ No newline`, binary notices), CRLF stripping, marker stripping, and an empty blob.
 */

describe("parseUnifiedDiff (D-5/MF-3)", () => {
  it("classifies the +++/--- file headers as meta, NOT add/del", () => {
    const blob = ["diff --git a/foo.ts b/foo.ts", "index 1111111..2222222 100644", "--- a/foo.ts", "+++ b/foo.ts"].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta", "meta", "meta"]);
    // The full header text is kept for meta rows (marker column NOT stripped).
    expect(lines[2].text).toBe("--- a/foo.ts");
    expect(lines[3].text).toBe("+++ b/foo.ts");
  });

  it("classifies a multi-hunk diff and strips the single leading marker column", () => {
    const blob = [
      "@@ -1,3 +1,3 @@",
      " context one",
      "-removed line",
      "+added line",
      "@@ -10,2 +10,2 @@",
      " context two",
      "-old",
      "+new",
    ].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "context", "del", "add", "meta", "context", "del", "add"]);
    // Markers stripped for content rows; context keeps its content sans the leading space.
    expect(lines[1].text).toBe("context one");
    expect(lines[2].text).toBe("removed line");
    expect(lines[3].text).toBe("added line");
  });

  it("treats `\\ No newline at end of file` (leading backslash) as meta", () => {
    const blob = ["@@ -1 +1 @@", "-a", "\\ No newline at end of file", "+b"].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "del", "meta", "add"]);
    expect(lines[2].text).toBe("\\ No newline at end of file");
  });

  it("treats a `Binary files …` notice as meta", () => {
    const blob = ["diff --git a/logo.png b/logo.png", "Binary files a/logo.png and b/logo.png differ"].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta"]);
  });

  it("strips a trailing \\r on every line (CRLF diffs)", () => {
    const blob = ["@@ -1 +1 @@\r", " ctx\r", "-old\r", "+new\r"].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "context", "del", "add"]);
    expect(lines[1].text).toBe("ctx");
    expect(lines[2].text).toBe("old");
    expect(lines[3].text).toBe("new");
  });

  it("emits no rows for an empty diff", () => {
    expect(parseUnifiedDiff("")).toEqual([]);
  });

  it("does not emit a spurious trailing context row for a final newline", () => {
    const lines = parseUnifiedDiff("+added\n");
    expect(lines).toHaveLength(1);
    expect(lines[0]).toEqual({ kind: "add", text: "added" });
  });

  it("renders a bare empty line in a hunk as a context row", () => {
    const lines = parseUnifiedDiff(["@@ -1 +1 @@", "", "+x"].join("\n"));
    expect(lines.map((l) => l.kind)).toEqual(["meta", "context", "add"]);
    expect(lines[1].text).toBe("");
  });

  it("classifies git's rename extended-header block as meta (not context)", () => {
    // A pure rename emits similarity/rename lines and no content hunk.
    const blob = [
      "diff --git a/old.ts b/new.ts",
      "similarity index 100%",
      "rename from old.ts",
      "rename to new.ts",
    ].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta", "meta", "meta"]);
    // The full header text is kept verbatim for meta rows.
    expect(lines.map((l) => l.text)).toEqual(blob.split("\n"));
  });

  it("classifies git's new-file / mode extended-header lines as meta", () => {
    const blob = [
      "diff --git a/added.ts b/added.ts",
      "new file mode 100644",
      "index 0000000..1111111",
      "--- /dev/null",
      "+++ b/added.ts",
      "@@ -0,0 +1 @@",
      "+hello",
    ].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta", "meta", "meta", "meta", "meta", "add"]);
    expect(lines[6].text).toBe("hello");
  });

  it("classifies a chmod (mode-change) extended-header block as meta", () => {
    const blob = ["diff --git a/run.sh b/run.sh", "old mode 100644", "new mode 100755"].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta", "meta"]);
  });

  it("classifies a copy extended-header block as meta", () => {
    const blob = [
      "diff --git a/src.ts b/dup.ts",
      "dissimilarity index 0%",
      "copy from src.ts",
      "copy to dup.ts",
    ].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta", "meta", "meta"]);
  });

  it("classifies a deleted-file mode line as meta", () => {
    const blob = ["diff --git a/gone.ts b/gone.ts", "deleted file mode 100644"].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta"]);
  });

  it("does NOT swallow hunk-body lines that render as `--- `/`+++ ` (a `-- `/`++ ` content line)", () => {
    // A removed line whose text starts with `-- ` renders raw as `--- foo`; an added `++ bar` as `+++ bar`.
    // STATE-AWARE classification: inside a hunk these are del/add CONTENT, never the file-header prefixes.
    const blob = ["@@ -1,2 +1,2 @@", " keep", "-- foo", "++ bar"].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "context", "del", "add"]);
    // Only the single leading marker column is stripped.
    expect(lines[2].text).toBe("- foo");
    expect(lines[3].text).toBe("+ bar");
  });

  it("resets `inHunk` across files so a second file's `--- `/`+++ ` headers still classify as meta", () => {
    // Two `diff --git` sections: the first file's hunk body ends; the second file's header block (with its
    // own `--- `/`+++ `) must re-classify as meta, not be dragged along as hunk content.
    const blob = [
      "diff --git a/one.ts b/one.ts",
      "index 1111111..2222222 100644",
      "--- a/one.ts",
      "+++ b/one.ts",
      "@@ -1 +1 @@",
      "-old one",
      "+new one",
      "diff --git a/two.ts b/two.ts",
      "index 3333333..4444444 100644",
      "--- a/two.ts",
      "+++ b/two.ts",
      "@@ -1 +1 @@",
      "-old two",
      "+new two",
    ].join("\n");
    const lines = parseUnifiedDiff(blob);
    expect(lines.map((l) => l.kind)).toEqual([
      "meta", "meta", "meta", "meta", "meta", "del", "add", // file one
      "meta", "meta", "meta", "meta", "meta", "del", "add", // file two
    ]);
    // The second file's source/target headers keep their full text (marker column NOT stripped).
    expect(lines[9].text).toBe("--- a/two.ts");
    expect(lines[10].text).toBe("+++ b/two.ts");
  });
});

describe("languageForPath (MF-2)", () => {
  it("maps known extensions to an hljs language id", () => {
    expect(languageForPath("src/foo.ts")).toBe("typescript");
    expect(languageForPath("a/b/script.py")).toBe("python");
    expect(languageForPath("Main.kt")).toBe("kotlin");
  });

  it("returns null for an unknown or extensionless path", () => {
    expect(languageForPath("notes/no-extension")).toBeNull();
    expect(languageForPath("data.weirdext")).toBeNull();
    expect(languageForPath("trailing.")).toBeNull();
  });
});

describe("MAX_DIFF_RENDER_CHARS", () => {
  it("is a positive byte budget", () => {
    expect(MAX_DIFF_RENDER_CHARS).toBeGreaterThan(0);
  });
});
