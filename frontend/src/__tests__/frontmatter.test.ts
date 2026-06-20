import { describe, expect, it } from "vitest";
import {
  frontmatterList,
  frontmatterValue,
  removeFrontmatterKey,
  setFrontmatterList,
  setFrontmatterValue,
  splitFrontmatter,
} from "../lib/frontmatter";

/**
 * The "save as new" recovery path (D-5) must send the BODY ONLY — the server prepends its own
 * freshly-minted frontmatter, so a buffer carrying the old `---…---` block would compose two blocks
 * and render the stale one as literal text (the dual-model review's BLOCKING FIX 1).
 */
describe("splitFrontmatter", () => {
  it("splits a leading frontmatter block from the body", () => {
    const buffer = "---\nid: 0197a3f2\ntitle: Deploy Guide\n---\n\n# Deploy Guide\n\nbody.\n";
    const { frontmatter, body } = splitFrontmatter(buffer);
    expect(frontmatter).toBe("id: 0197a3f2\ntitle: Deploy Guide");
    // The body is everything after the closing fence's newline (the blank line the server emits is kept).
    expect(body).toBe("\n# Deploy Guide\n\nbody.\n");
    // The split body carries NO leading frontmatter / no stale id.
    expect(body.startsWith("---")).toBe(false);
    expect(body).not.toContain("id: 0197a3f2");
  });

  it("treats a buffer with no leading block as all body", () => {
    const buffer = "# Just a heading\n\nno frontmatter here.\n";
    expect(splitFrontmatter(buffer)).toEqual({ frontmatter: null, body: buffer });
  });

  it("does not treat a mid-document `---` (thematic break) as frontmatter", () => {
    const buffer = "# Heading\n\nintro\n\n---\n\nmore\n";
    const { frontmatter, body } = splitFrontmatter(buffer);
    expect(frontmatter).toBeNull();
    expect(body).toBe(buffer);
  });

  it("handles a frontmatter block with no trailing body", () => {
    const { frontmatter, body } = splitFrontmatter("---\ntitle: X\n---\n");
    expect(frontmatter).toBe("title: X");
    expect(body).toBe("");
  });

  it("tolerates CRLF line endings", () => {
    const { frontmatter, body } = splitFrontmatter("---\r\ntitle: X\r\n---\r\nbody\r\n");
    expect(frontmatter).toBe("title: X");
    expect(body).toBe("body\r\n");
  });

  // Divergence cases — each asserts the SAME boundary the server's FrontmatterBlock.kt grammar uses
  // (the dual-model review found the old splitter diverged on every one of these).

  it("strips a leading UTF-8 BOM before detecting the opener (server strips the BOM first)", () => {
    // Server: a `﻿` BOM precedes the `---` opener and is accounted for; the block is still recognized
    // and the body resumes after the closer. The stale `id:` block must NOT survive into the body.
    const { frontmatter, body } = splitFrontmatter("﻿---\nid: old\n---\nbody\n");
    expect(frontmatter).toBe("id: old");
    expect(body).toBe("body\n");
    expect(body).not.toContain("id: old");
  });

  it("strips the BOM even when the buffer has no frontmatter block (server bodyStart is post-BOM)", () => {
    const { frontmatter, body } = splitFrontmatter("﻿# Heading\n\nbody\n");
    expect(frontmatter).toBeNull();
    expect(body).toBe("# Heading\n\nbody\n");
  });

  it("accepts a `...` closing delimiter (the server's other frozen closer)", () => {
    // FrontmatterBlock.isDelimiterLine accepts three dots OR three dashes; the old splitter treated a
    // `...`-closed block as all-body, so the stale `id:` block leaked into the rendered content.
    const { frontmatter, body } = splitFrontmatter("---\nid: old\n...\nbody\n");
    expect(frontmatter).toBe("id: old");
    expect(body).toBe("body\n");
    expect(body).not.toContain("id: old");
  });

  it("does NOT treat `--- ` (trailing space) as an opener — it is plain Markdown to the server", () => {
    // FrontmatterBlock.openerEnd rejects any trailing char after `---`; the old splitter's `[ \t]*` opener
    // matched `--- ` and wrongly stripped the first body line.
    const buffer = "--- \nfirst real body line\n\nmore\n";
    const { frontmatter, body } = splitFrontmatter(buffer);
    expect(frontmatter).toBeNull();
    expect(body).toBe(buffer);
  });

  it("does NOT treat `--- ` (trailing space) as a closer — the block stays unclosed (all body)", () => {
    // With no EXACT `---`/`...` closer the server finds NO frontmatter (the opener is a thematic break).
    const buffer = "---\nid: old\n--- \nbody\n";
    const { frontmatter, body } = splitFrontmatter(buffer);
    expect(frontmatter).toBeNull();
    expect(body).toBe(buffer);
  });

  it("does NOT treat `----` (four dashes) as a delimiter line", () => {
    const buffer = "----\nnot frontmatter\n";
    const { frontmatter, body } = splitFrontmatter(buffer);
    expect(frontmatter).toBeNull();
    expect(body).toBe(buffer);
  });

  it("tolerates bare CR line endings (the server's third EOL form)", () => {
    const { frontmatter, body } = splitFrontmatter("---\rid: old\r---\rbody\r");
    expect(frontmatter).toBe("id: old");
    expect(body).toBe("body\r");
  });

  it("does NOT treat a bare `---` at EOF as an opener (server: a block needs a terminated opener)", () => {
    const buffer = "---";
    expect(splitFrontmatter(buffer)).toEqual({ frontmatter: null, body: buffer });
  });
});

describe("frontmatterValue", () => {
  it("reads a plain scalar", () => {
    expect(frontmatterValue("id: abc\ntitle: Deploy Guide", "title")).toBe("Deploy Guide");
  });

  it("unwraps a double-quoted scalar and its escapes", () => {
    expect(frontmatterValue('title: "A: B \\"q\\""', "title")).toBe('A: B "q"');
  });

  it("unwraps a single-quoted scalar verbatim", () => {
    expect(frontmatterValue("title: 'kept literal'", "title")).toBe("kept literal");
  });

  it("returns null for an absent or blank key", () => {
    expect(frontmatterValue("title: X", "owner")).toBeNull();
    expect(frontmatterValue("title:   ", "title")).toBeNull();
  });

  it("reads a scalar on an inner CRLF line (the \\r must not defeat the match)", () => {
    // splitFrontmatter on a CRLF doc yields inner lines joined by \r\n, so every line BUT the last
    // carries a trailing \r. `.` excludes \r and non-multiline `$` needs true end-of-string, so without
    // the shared CR/LF line splitter the match fails → the rail form showed CRLF pages' metadata as blank.
    expect(frontmatterValue("status: review\r\ntitle: Deploy Guide", "status")).toBe("review");
    expect(frontmatterValue("status: review\r\ntitle: Deploy Guide", "title")).toBe("Deploy Guide");
  });

  it("reads scalars from a bare-CR (classic-Mac) block — every field, not just the first (regression #4)", () => {
    // The block detector accepts bare `\r`, so a bare-CR frontmatter block is real. A naive `split("\n")`
    // would treat the whole inner text as ONE line → all but the first field read blank → a subsequent edit
    // rewrites from an incomplete view (data loss). The shared splitter reads every line.
    const fm = splitFrontmatter("---\rstatus: review\rowner: Ada\rupdated: 2026-01-01\r---\rbody\r").frontmatter!;
    expect(frontmatterValue(fm, "status")).toBe("review");
    expect(frontmatterValue(fm, "owner")).toBe("Ada");
    expect(frontmatterValue(fm, "updated")).toBe("2026-01-01");
  });
});

/**
 * The C2 surgical WRITE primitives — the client analogue of the server `FrontmatterPatcher`: a single-point
 * splice, never a parse-and-reserialize. The GATING property is byte-fidelity: a single-field edit changes
 * EXACTLY the targeted line and leaves every other byte (unknown keys, comments, quoting, key order, the
 * body, the BOM, the EOL style) byte-identical (the client mirror of `FrontmatterPatcher.postCheckHolds`).
 */
describe("setFrontmatterValue", () => {
  // A block with an unknown key, a comment, a quoted value, and a specific key order — the GATING seed.
  const SEED = "---\nid: 0197\n# a comment\ntitle: 'Deploy Guide'\nstatus: draft\nowner: ops\n---\n\n# Body\n\ntext.\n";

  it("changes ONLY the targeted line — unknown keys, comments, quoting, order, body byte-identical", () => {
    const out = setFrontmatterValue(SEED, "status", "published");
    // The whole buffer equals the input with EXACTLY the status line flipped (the property assertion).
    expect(out).toBe(SEED.replace("status: draft\n", "status: published\n"));
    // And nothing else moved: comment, quoting, order, and body survive verbatim.
    expect(out).toContain("# a comment\n");
    expect(out).toContain("title: 'Deploy Guide'\n");
    expect(splitFrontmatter(out).body).toBe(splitFrontmatter(SEED).body);
  });

  it("inserts an absent key without touching existing lines (EOL copied)", () => {
    const out = setFrontmatterValue(SEED, "review", "2026-01-01");
    // The new line lands at the END of the block (just before the closer), leaving the leading order intact.
    expect(out).toBe(SEED.replace("owner: ops\n---", "owner: ops\nreview: 2026-01-01\n---"));
    expect(splitFrontmatter(out).body).toBe(splitFrontmatter(SEED).body);
  });

  it("copies the replaced line's CRLF terminator on an in-place update", () => {
    const crlf = "---\r\nstatus: draft\r\nowner: ops\r\n---\r\nbody\r\n";
    const out = setFrontmatterValue(crlf, "status", "active");
    expect(out).toBe("---\r\nstatus: active\r\nowner: ops\r\n---\r\nbody\r\n");
  });

  it("quotes a value that would otherwise break the line grammar", () => {
    const out = setFrontmatterValue("---\nowner: ops\n---\nbody\n", "owner", "Smith: ops");
    expect(out).toBe('---\nowner: "Smith: ops"\n---\nbody\n');
    // Round-trips back through the scalar reader.
    expect(frontmatterValue(splitFrontmatter(out).frontmatter!, "owner")).toBe("Smith: ops");
  });

  it("a value containing \\n/\\r never breaks the single line (no physical extra line, #5)", () => {
    // emitScalar collapses raw CR/LF to a space — a frontmatter scalar for these fields is single-line by
    // nature, so an injected newline must NOT write a `tags:`-style continuation that corrupts the grammar.
    const out = setFrontmatterValue("---\nowner: ops\n---\nbody\n", "owner", "a\nb\rc\r\nd");
    expect(out).toBe("---\nowner: a b c d\n---\nbody\n");
    // The block still has exactly one inner line (the body boundary is unchanged).
    expect(splitFrontmatter(out).body).toBe("body\n");
    // And it round-trips as a single scalar.
    expect(frontmatterValue(splitFrontmatter(out).frontmatter!, "owner")).toBe("a b c d");
  });

  it("preserves a `...`-closed block and a leading BOM", () => {
    const bom = "﻿---\nstatus: draft\n...\nbody\n";
    const out = setFrontmatterValue(bom, "status", "active");
    expect(out).toBe("﻿---\nstatus: active\n...\nbody\n");
  });

  it("on a block-less buffer creates a fresh fenced block, body byte-identical", () => {
    const out = setFrontmatterValue("# Heading\n\nbody.\n", "status", "draft");
    expect(out).toBe("---\nstatus: draft\n---\n# Heading\n\nbody.\n");
  });

  it("creates a block-less buffer's block with the file's first EOL (CRLF) and no-trailing-EOL body", () => {
    expect(setFrontmatterValue("# H\r\nbody", "status", "draft")).toBe("---\r\nstatus: draft\r\n---\r\n# H\r\nbody");
    // No EOL anywhere → \n; the body (no trailing newline) is appended verbatim.
    expect(setFrontmatterValue("body", "status", "draft")).toBe("---\nstatus: draft\n---\nbody");
  });
});

describe("removeFrontmatterKey", () => {
  it("drops only the target line", () => {
    const seed = "---\nid: 0197\nstatus: draft\nowner: ops\n---\nbody\n";
    expect(removeFrontmatterKey(seed, "status")).toBe("---\nid: 0197\nowner: ops\n---\nbody\n");
  });

  it("is a no-op when the key is absent", () => {
    const seed = "---\nid: 0197\n---\nbody\n";
    expect(removeFrontmatterKey(seed, "status")).toBe(seed);
  });

  it("leaves an empty block intact when the last key is removed (no structural collapse)", () => {
    expect(removeFrontmatterKey("---\nstatus: draft\n---\nbody\n", "status")).toBe("---\n---\nbody\n");
  });

  it("drops a block list's whole indented run", () => {
    const seed = "---\nstatus: draft\ntags:\n  - a\n  - b\nowner: ops\n---\nbody\n";
    expect(removeFrontmatterKey(seed, "tags")).toBe("---\nstatus: draft\nowner: ops\n---\nbody\n");
  });
});

describe("setFrontmatterList / frontmatterList", () => {
  it("add/remove a tag changes only the tags block", () => {
    const seed = "---\nstatus: draft\ntags:\n  - a\n  - b\nowner: ops\n---\nbody\n";
    const added = setFrontmatterList(seed, "tags", ["a", "b", "c"]);
    expect(added).toBe("---\nstatus: draft\ntags:\n  - a\n  - b\n  - c\nowner: ops\n---\nbody\n");
    const removed = setFrontmatterList(seed, "tags", ["a"]);
    expect(removed).toBe("---\nstatus: draft\ntags:\n  - a\nowner: ops\n---\nbody\n");
  });

  it("inserts a tags block at the end when none exists", () => {
    const seed = "---\nstatus: draft\n---\nbody\n";
    expect(setFrontmatterList(seed, "tags", ["x", "y"])).toBe("---\nstatus: draft\ntags:\n  - x\n  - y\n---\nbody\n");
  });

  it("removes the key when the list is emptied", () => {
    const seed = "---\ntags:\n  - a\nowner: ops\n---\nbody\n";
    expect(setFrontmatterList(seed, "tags", [])).toBe("---\nowner: ops\n---\nbody\n");
  });

  it("round-trips: frontmatterList reads back exactly what setFrontmatterList wrote", () => {
    const items = ["alpha", "beta gamma", "v: 1", "x,y"];
    const out = setFrontmatterList("---\nstatus: draft\n---\nbody\n", "tags", items);
    expect(frontmatterList(out, "tags")).toEqual(items);
  });

  it("reads an externally-authored block list AND inline [a, b]", () => {
    expect(frontmatterList("---\ntags:\n  - one\n  - two\n---\nbody\n", "tags")).toEqual(["one", "two"]);
    expect(frontmatterList('---\ntags: [one, "two, three", four]\n---\nbody\n', "tags")).toEqual(["one", "two, three", "four"]);
  });

  it("reads a bare-CR (classic-Mac) block list — every item (regression #4)", () => {
    expect(frontmatterList("---\rstatus: draft\rtags:\r  - one\r  - two\r---\rbody\r", "tags")).toEqual(["one", "two"]);
  });

  it("keeps the block's EOL when editing a tags list in a mixed-EOL block (#8)", () => {
    // The block uses CRLF; a tags edit must reuse the replaced span's CRLF for its internal `  - item`
    // separators (not normalize them to the file's first/guessed EOL).
    const crlf = "---\r\nstatus: draft\r\ntags:\r\n  - a\r\n  - b\r\nowner: ops\r\n---\r\nbody\r\n";
    const out = setFrontmatterList(crlf, "tags", ["a", "b", "c"]);
    expect(out).toBe("---\r\nstatus: draft\r\ntags:\r\n  - a\r\n  - b\r\n  - c\r\nowner: ops\r\n---\r\nbody\r\n");
    // No bare LF anywhere — every separator stayed CRLF (no normalization inside the block).
    expect(out.replace(/\r\n/g, "")).not.toContain("\n");
  });

  it("returns [] for an absent key or a buffer with no block", () => {
    expect(frontmatterList("---\nstatus: draft\n---\nbody\n", "tags")).toEqual([]);
    expect(frontmatterList("# no block\n", "tags")).toEqual([]);
  });
});
