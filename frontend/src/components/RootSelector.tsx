import { type KeyboardEvent, useEffect, useRef, useState } from "react";
import type { RootTree } from "../api/types";

const LABEL_ID = "pb-root-selector-label";
const LISTBOX_ID = "pb-root-selector-listbox";
const VALUE_ID = "pb-root-selector-value";

function labelOf(entry: RootTree): string {
  return `${entry.root}${entry.available ? "" : " (unavailable)"}`;
}

/**
 * The themed multi-root picker. A native select delegates its open popup to the browser/OS,
 * including the bright system-blue selection color, so that surface cannot follow Plainbase's
 * semantic tokens. This small listbox keeps the same navigation contract while making both the
 * closed control and open choices part of the document theme.
 */
export function RootSelector({
  entries,
  selected,
  onSelect,
}: {
  entries: RootTree[];
  selected: RootTree;
  onSelect: (entry: RootTree) => void;
}) {
  const [open, setOpen] = useState(false);
  const selectedIndex = Math.max(0, entries.findIndex((entry) => entry.root === selected.root));
  const [activeIndex, setActiveIndex] = useState(selectedIndex);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const selectableIndexes = entries
    .map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => entry.tree.url !== null || entry.root === selected.root)
    .map(({ index }) => index);

  useEffect(() => {
    if (!open) return;
    optionRefs.current[activeIndex]?.focus();
  }, [activeIndex, open]);

  useEffect(() => {
    if (!open) setActiveIndex(selectedIndex);
  }, [open, selectedIndex]);

  useEffect(() => {
    if (!open) return;
    const closeFromOutside = (event: PointerEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", closeFromOutside);
    return () => document.removeEventListener("pointerdown", closeFromOutside);
  }, [open]);

  const openMenu = () => {
    setActiveIndex(selectedIndex);
    setOpen(true);
  };

  const closeMenu = (restoreFocus = false) => {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  };

  const moveActive = (offset: number) => {
    if (selectableIndexes.length === 0) return;
    const position = selectableIndexes.indexOf(activeIndex);
    const start = position === -1 ? 0 : position;
    const next = (start + offset + selectableIndexes.length) % selectableIndexes.length;
    setActiveIndex(selectableIndexes[next]);
  };

  const choose = (entry: RootTree) => {
    if (entry.tree.url === null && entry.root !== selected.root) return;
    closeMenu(true);
    if (entry.root !== selected.root) onSelect(entry);
  };

  const handleTriggerKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    event.preventDefault();
    openMenu();
  };

  const handleListboxKeyDown = (event: KeyboardEvent<HTMLUListElement>) => {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        moveActive(1);
        break;
      case "ArrowUp":
        event.preventDefault();
        moveActive(-1);
        break;
      case "Home":
        event.preventDefault();
        setActiveIndex(selectableIndexes[0] ?? selectedIndex);
        break;
      case "End":
        event.preventDefault();
        setActiveIndex(selectableIndexes.at(-1) ?? selectedIndex);
        break;
      case "Escape":
        event.preventDefault();
        closeMenu(true);
        break;
      case "Tab":
        closeMenu();
        break;
    }
  };

  return (
    <div ref={containerRef} className="relative px-4 pt-4">
      <label id={LABEL_ID} className="sr-only" htmlFor="pb-root-selector">
        Documentation root
      </label>
      <button
        ref={triggerRef}
        id="pb-root-selector"
        type="button"
        aria-labelledby={`${LABEL_ID} ${VALUE_ID}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={LISTBOX_ID}
        onClick={() => (open ? closeMenu() : openMenu())}
        onKeyDown={handleTriggerKeyDown}
        className="flex w-full items-center justify-between gap-3 rounded-md border border-edge bg-surface px-3 py-2 text-left font-mono text-sm text-ink hover:bg-hovered"
        data-pb-root-selector
        data-pb-selected-root={selected.root}
      >
        <span id={VALUE_ID}>{labelOf(selected)}</span>
        <span aria-hidden="true" className="pb-root-selector-caret" />
      </button>
      {open && (
        <ul
          id={LISTBOX_ID}
          role="listbox"
          aria-labelledby={LABEL_ID}
          onKeyDown={handleListboxKeyDown}
          className="absolute left-4 right-4 z-20 mt-1 space-y-0.5 rounded-md border border-edge bg-raised p-1 shadow-lg"
          data-pb-root-menu
        >
          {entries.map((entry, index) => {
            const active = entry.root === selected.root;
            const disabled = entry.tree.url === null && !active;
            return (
              <li key={entry.root} role="presentation">
                <button
                  ref={(element) => {
                    optionRefs.current[index] = element;
                  }}
                  type="button"
                  role="option"
                  aria-selected={active}
                  aria-disabled={disabled || undefined}
                  disabled={disabled}
                  onClick={() => choose(entry)}
                  onMouseMove={() => {
                    if (!disabled) setActiveIndex(index);
                  }}
                  className={
                    active
                      ? "w-full rounded bg-active px-3 py-2 text-left font-mono text-sm text-ink"
                      : "w-full rounded px-3 py-2 text-left font-mono text-sm text-muted hover:bg-hovered hover:text-ink disabled:cursor-not-allowed disabled:opacity-50"
                  }
                  data-pb-root-option={entry.root}
                  data-pb-root-label={entry.root}
                  data-pb-root-active={active ? "" : undefined}
                >
                  {labelOf(entry)}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
