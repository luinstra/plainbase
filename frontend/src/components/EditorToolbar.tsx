import type { Command, EditorView } from "@codemirror/view";
import { useState, type ReactNode } from "react";
import {
  CALLOUT_TYPES,
  insertCallout,
  insertLink,
  insertTable,
  toggleBlockquote,
  toggleBold,
  toggleBulletList,
  toggleCode,
  toggleCodeBlock,
  toggleHeading,
  toggleItalic,
  toggleNumberedList,
  type CalloutType,
} from "../lib/markdownCommands";

/** Formatting controls share the body editor commands and stay disabled when Preview covers the source. */
const STROKE = { fill: "none", stroke: "currentColor", strokeWidth: 1.6, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

function Icon({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true" focusable="false">
      {children}
    </svg>
  );
}

const BoldIcon = (
  <Icon>
    <path {...STROKE} d="M5 3h4.2a2.4 2.4 0 0 1 0 4.8H5z" />
    <path {...STROKE} d="M5 7.8h4.8a2.6 2.6 0 0 1 0 5.2H5z" />
  </Icon>
);
const ItalicIcon = (
  <Icon>
    <line {...STROKE} x1="10.5" y1="3" x2="6.5" y2="13" />
    <line {...STROKE} x1="8" y1="3" x2="12" y2="3" />
    <line {...STROKE} x1="4" y1="13" x2="8" y2="13" />
  </Icon>
);
// Inline code reads cleaner as a literal `<>` than as a generic glyph.
const InlineCodeIcon = (
  <Icon>
    <polyline {...STROKE} points="5,5 2,8 5,11" />
    <polyline {...STROKE} points="11,5 14,8 11,11" />
  </Icon>
);
const LinkIcon = (
  <Icon>
    <path {...STROKE} d="M6.5 9.5a2.5 2.5 0 0 1 0-3.5l2-2a2.5 2.5 0 0 1 3.5 3.5l-1 1" />
    <path {...STROKE} d="M9.5 6.5a2.5 2.5 0 0 1 0 3.5l-2 2a2.5 2.5 0 0 1-3.5-3.5l1-1" />
  </Icon>
);
const HeadingIcon = (
  <Icon>
    <line {...STROKE} x1="4" y1="3.5" x2="4" y2="12.5" />
    <line {...STROKE} x1="11" y1="3.5" x2="11" y2="12.5" />
    <line {...STROKE} x1="4" y1="8" x2="11" y2="8" />
  </Icon>
);
const BulletIcon = (
  <Icon>
    <circle cx="3" cy="4.5" r="1.1" fill="currentColor" />
    <circle cx="3" cy="11.5" r="1.1" fill="currentColor" />
    <line {...STROKE} x1="6.5" y1="4.5" x2="13.5" y2="4.5" />
    <line {...STROKE} x1="6.5" y1="11.5" x2="13.5" y2="11.5" />
  </Icon>
);
const NumberedIcon = (
  <Icon>
    <line {...STROKE} x1="6.5" y1="4.5" x2="13.5" y2="4.5" />
    <line {...STROKE} x1="6.5" y1="11.5" x2="13.5" y2="11.5" />
    <path {...STROKE} d="M2.4 3.2 3.4 2.7v3.1" />
    <path {...STROKE} d="M2.2 10.2c0-.7 1.5-.7 1.5.1 0 .6-1.5 1.3-1.5 2.4h1.6" />
  </Icon>
);
const QuoteIcon = (
  <Icon>
    <line {...STROKE} x1="3" y1="3.5" x2="3" y2="12.5" />
    <line {...STROKE} x1="6.5" y1="5" x2="13" y2="5" />
    <line {...STROKE} x1="6.5" y1="8" x2="13" y2="8" />
    <line {...STROKE} x1="6.5" y1="11" x2="11" y2="11" />
  </Icon>
);
const CodeBlockIcon = (
  <Icon>
    <rect {...STROKE} x="2" y="3" width="12" height="10" rx="1.5" />
    <polyline {...STROKE} points="6,6.5 4.5,8 6,9.5" />
    <polyline {...STROKE} points="10,6.5 11.5,8 10,9.5" />
  </Icon>
);

const TableIcon = (
  <Icon>
    <rect {...STROKE} x="2" y="3" width="12" height="10" rx="1" />
    <line {...STROKE} x1="2" y1="6.5" x2="14" y2="6.5" />
    <line {...STROKE} x1="8" y1="3" x2="8" y2="13" />
  </Icon>
);

type ToolbarItem = { kind: "sep" } | { kind: "btn"; hook: string; label: string; icon: ReactNode; run: Command };

const ITEMS: ToolbarItem[] = [
  { kind: "btn", hook: "data-pb-fmt-heading", label: "Heading", icon: HeadingIcon, run: toggleHeading },
  { kind: "sep" },
  { kind: "btn", hook: "data-pb-fmt-bold", label: "Bold", icon: BoldIcon, run: toggleBold },
  { kind: "btn", hook: "data-pb-fmt-italic", label: "Italic", icon: ItalicIcon, run: toggleItalic },
  { kind: "btn", hook: "data-pb-fmt-code", label: "Inline code", icon: InlineCodeIcon, run: toggleCode },
  { kind: "btn", hook: "data-pb-fmt-link", label: "Link", icon: LinkIcon, run: insertLink },
  { kind: "btn", hook: "data-pb-fmt-bullet", label: "Bullet list", icon: BulletIcon, run: toggleBulletList },
  { kind: "btn", hook: "data-pb-fmt-numbered", label: "Numbered list", icon: NumberedIcon, run: toggleNumberedList },
  { kind: "btn", hook: "data-pb-fmt-quote", label: "Quote", icon: QuoteIcon, run: toggleBlockquote },
  { kind: "btn", hook: "data-pb-fmt-codeblock", label: "Code block", icon: CodeBlockIcon, run: toggleCodeBlock },
  { kind: "btn", hook: "data-pb-fmt-table", label: "Insert table", icon: TableIcon, run: insertTable },
];

export function EditorToolbar({ view, disabled }: { view: EditorView | null; disabled: boolean }) {
  const [calloutType, setCalloutType] = useState<CalloutType>("NOTE");
  const groups = [
    { label: "Block style", items: ITEMS.slice(0, 1) },
    { label: "Inline", items: ITEMS.slice(2, 6) },
    { label: "Lists", items: ITEMS.slice(6, 8) },
    { label: "Insert", items: ITEMS.slice(8) },
  ];
  const shortcuts: Record<string, string> = { Bold: "⌘B", Italic: "⌘I", "Inline code": "⌘E", Link: "⌘⇧K" };
  return (
    <div className="pb-editor-toolbar flex flex-wrap items-center gap-1" data-pb-toolbar role="toolbar" aria-label="Formatting">
      {groups.map((group) => <div className="pb-editor-tool-group" role="group" aria-label={group.label} key={group.label}>
      {group.items.map((item, index) =>
        item.kind === "sep" ? (
          <span key={`sep-${index}`} className="mx-1 h-5 w-px bg-edge" aria-hidden="true" data-pb-fmt-sep />
        ) : (
          <button
            key={item.hook}
            type="button"
            className="pb-fmt-btn inline-flex items-center justify-center rounded-md p-1.5 text-muted hover:text-ink"
            {...{ [item.hook]: "" }}
            title={shortcuts[item.label] ? `${item.label} (${shortcuts[item.label]})` : item.label}
            aria-label={item.label}
            disabled={disabled || !view}
            onClick={() => {
              if (!view) return;
              item.run(view);
              view.focus();
            }}
          >
            {item.icon}
          </button>
        ),
      )}
      {group.label === "Insert" && <>
      <select
        className="rounded-md border border-edge bg-surface px-1.5 py-1.5 text-xs text-muted focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
        aria-label="Callout type"
        data-pb-callout-type
        value={calloutType}
        disabled={disabled || !view}
        onChange={(event) => setCalloutType(event.target.value as CalloutType)}
      >
        {CALLOUT_TYPES.map((type) => (
          <option key={type} value={type}>
            {type}
          </option>
        ))}
      </select>
      <button
        type="button"
        className="rounded-md px-2 py-1.5 text-xs text-muted hover:text-ink"
        data-pb-fmt-callout
        title="Insert callout"
        aria-label="Insert callout"
        disabled={disabled || !view}
        onClick={() => {
          if (!view) return;
          insertCallout(calloutType)(view);
          view.focus();
        }}
      >
        Callout
      </button>
      </>}
      </div>)}
    </div>
  );
}
