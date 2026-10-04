import { expect, it } from "vitest";
import { checkPreviewLinks, diagnosticsFromPreview } from "./editorDiagnostics";

it("maps UTF-8 link occurrence boundaries to body lines through BOM, frontmatter and mixed newlines", () => {
  const buffer = "\ufeff---\r\ntitle: X\r\n---\r\n😀 text\r\n[a](x)\r[b](y)\n";
  const a = new TextEncoder().encode(buffer.slice(0, buffer.indexOf("[a]"))).length;
  const b = new TextEncoder().encode(buffer.slice(0, buffer.indexOf("[b]"))).length;
  const html = `<p><a data-pb-link-error="not_found" data-pb-link-src="${a}-${a + 6}" data-pb-link-target="x">a</a>
    <a data-pb-link-error="not_found" data-pb-link-src="${b}-${b + 6}" data-pb-link-target="y">b</a></p>`;
  expect(diagnosticsFromPreview(buffer, html)).toEqual([{ line: 2, message: "Broken link: x" }, { line: 3, message: "Broken link: y" }]);
});

it("reports unmappable broken links without inventing a successful check", () => {
  expect(checkPreviewLinks("[a](x)", '<span data-pb-link-error="not_found" data-pb-link-target="x">a</span>')).toEqual({ markers: [], unmapped: true });
});

it("rejects invalid boundaries and groups repeated occurrences on one line", () => {
  const buffer = "😀 [a](x) [b](y)";
  const broken = (range: string, target: string) => `<a data-pb-link-error="not_found" data-pb-link-src="${range}" data-pb-link-target="${target}"></a>`;
  expect(diagnosticsFromPreview(buffer, broken("5-11", "x") + broken("12-18", "y"))).toEqual([{ line: 1, message: "Broken links: x, y" }]);
  for (const range of ["1-4", "0-2", "5-999", "11-5", "NaN-8", "0-0", "-1-4"]) {
    expect(diagnosticsFromPreview(buffer, broken(range, "wrong"))).toEqual([]);
  }
});
