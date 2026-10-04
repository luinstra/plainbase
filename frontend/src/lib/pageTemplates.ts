/**
 * Client-only body scaffolds offered on the `/new` form (C3, WI-3). Pure constants — no React, no DOM, no
 * server round-trip (a server-side template store stays deferred per the phase plan). Each `body` is short
 * NFC-plain ASCII Markdown ending in a trailing newline, like a real authored page; `Blank` (the default)
 * is `""`, so a Blank create POSTs no `body` field and is byte-identical to a plain create.
 */
export interface PageTemplate {
  readonly id: string;
  readonly label: string;
  readonly purpose: string;
  readonly body: string;
}

export const PAGE_TEMPLATES: readonly PageTemplate[] = [
  { id: "blank", label: "Blank", purpose: "A clean page for any idea", body: "" },
  { id: "howto", label: "How-to", purpose: "Walk through a task, step by step", body: "## Overview\n\n## Steps\n\n1. \n\n## See also\n" },
  { id: "reference", label: "Reference", purpose: "Keep facts and details easy to find", body: "## Summary\n\n## Reference\n\n| Name | Description |\n| --- | --- |\n|  |  |\n" },
  { id: "meeting", label: "Meeting notes", purpose: "Capture decisions and next steps", body: "## Attendees\n\n## Agenda\n\n## Decisions\n\n## Action items\n\n- [ ] \n" },
];
