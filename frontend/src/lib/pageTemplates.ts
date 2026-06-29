/**
 * Client-only body scaffolds offered on the `/new` form (C3, WI-3). Pure constants — no React, no DOM, no
 * server round-trip (a server-side template store stays deferred per the phase plan). Each `body` is short
 * NFC-plain ASCII Markdown ending in a trailing newline, like a real authored page; `Blank` (the default)
 * is `""`, so a Blank create POSTs no `body` field and is byte-identical to a plain create.
 */
export interface PageTemplate {
  readonly id: string;
  readonly label: string;
  readonly body: string;
}

export const PAGE_TEMPLATES: readonly PageTemplate[] = [
  { id: "blank", label: "Blank", body: "" },
  { id: "howto", label: "How-to", body: "## Overview\n\n## Steps\n\n1. \n\n## See also\n" },
  { id: "reference", label: "Reference", body: "## Summary\n\n## Reference\n\n| Name | Description |\n| --- | --- |\n|  |  |\n" },
  { id: "meeting", label: "Meeting notes", body: "## Attendees\n\n## Agenda\n\n## Decisions\n\n## Action items\n\n- [ ] \n" },
];
