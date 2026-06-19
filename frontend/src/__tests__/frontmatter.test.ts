import { describe, expect, it } from "vitest";
import { frontmatterValue, splitFrontmatter } from "../lib/frontmatter";

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
});
