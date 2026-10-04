import { splitFrontmatter } from "./frontmatter";

export interface EditorDiagnostic { line: number; message: string }

export function diagnosticsFromPreview(buffer: string, html: string): EditorDiagnostic[] {
  return checkPreviewLinks(buffer, html).markers;
}

export function checkPreviewLinks(buffer: string, html: string): { markers: EditorDiagnostic[]; unmapped: boolean } {
  const template = document.createElement("template");
  template.innerHTML = html;
  const occurrences = Array.from(template.content.querySelectorAll("[data-pb-link-error]"));
  if (!occurrences.length) return { markers: [], unmapped: false };
  const bodyStart = buffer.length - splitFrontmatter(buffer).body.length;
  const boundaries = new Map<number, number>();
  let bytes = 0;
  let line = 1;
  // The renderer counts UTF-8 bytes; CodeMirror counts normalized body lines.
  for (let index = 0; index < buffer.length;) {
    if (index >= bodyStart) boundaries.set(bytes, line);
    const code = buffer.codePointAt(index)!;
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
    if (index >= bodyStart && (code === 13 || (code === 10 && buffer[index - 1] !== "\r"))) line++;
    index += code > 0xffff ? 2 : 1;
  }
  boundaries.set(bytes, line);
  const lines = new Map<number, Set<string>>();
  let unmapped = false;
  for (const occurrence of occurrences) {
    const range = /^(\d+)-(\d+)$/.exec(occurrence.getAttribute("data-pb-link-src") ?? "");
    if (!range) { unmapped = true; continue; }
    const start = Number(range[1]);
    const end = Number(range[2]);
    const sourceLine = boundaries.get(start);
    if (start >= end || sourceLine === undefined || !boundaries.has(end)) { unmapped = true; continue; }
    const target = (occurrence.getAttribute("data-pb-link-target") ?? "Unknown target").replace(/\s+/g, " ").slice(0, 160);
    const targets = lines.get(sourceLine) ?? new Set<string>();
    targets.add(target);
    lines.set(sourceLine, targets);
  }
  return { markers: Array.from(lines, ([line, targets]) => ({ line, message: `Broken ${targets.size === 1 ? "link" : "links"}: ${Array.from(targets).join(", ").slice(0, 320)}` }))
    .sort((a, b) => a.line - b.line), unmapped };
}
