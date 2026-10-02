import { useEffect, useId, useRef, useState, type ReactNode, type Ref } from "react";
import { focusDiscussionElement } from "../lib/discussionFocus";

/** Native disclosure: ordinary buttons, with a visible keyboard return target. */
export function DiscussionActions({ label, children, triggerRef }: {
  label: string; children: ReactNode; triggerRef?: Ref<HTMLElement>;
}) {
  const disclosureRef = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    const dismiss = (event: PointerEvent) => {
      const disclosure = disclosureRef.current;
      if (disclosure?.open && event.target instanceof Node && !disclosure.contains(event.target)) disclosure.open = false;
    };
    document.addEventListener("pointerdown", dismiss, true);
    return () => document.removeEventListener("pointerdown", dismiss, true);
  }, []);
  return <details ref={disclosureRef} className="pb-discussion-actions" onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
  }} onToggle={(event) => {
    const disclosure = event.currentTarget;
    delete disclosure.dataset.side;
    if (!disclosure.open) return;
    const items = disclosure.querySelector<HTMLElement>(".pb-discussion-action-items")!;
    const summary = disclosure.querySelector("summary")!;
    const rail = disclosure.closest("[data-pb-rail]");
    const bounds = rail && getComputedStyle(rail).overflowY === "auto" ? rail.getBoundingClientRect() : null;
    const top = Math.max(0, bounds?.top ?? 0);
    const bottom = Math.min(window.innerHeight, bounds?.bottom ?? window.innerHeight);
    const popup = items.getBoundingClientRect();
    // Keep the same anchored popup; flip only when the scrollable rail would clip it.
    if (popup.bottom > bottom && summary.getBoundingClientRect().top - popup.height - 6 >= top) disclosure.dataset.side = "above";
  }} onKeyDown={(event) => {
    if (event.key !== "Escape") return;
    event.preventDefault(); event.stopPropagation();
    event.currentTarget.open = false;
    focusDiscussionElement(event.currentTarget.querySelector("summary"));
  }}>
    <summary ref={triggerRef} tabIndex={0} aria-label={label}>Actions <span aria-hidden="true">⌄</span></summary>
    <div className="pb-discussion-action-items">{children}</div>
  </details>;
}

/** A disabled button stays inert; its explanation remains reachable with a keyboard. */
export function DiscussionActionHint({ label, reason, children }: { label: string; reason: string | null; children: ReactNode }) {
  const hintId = useId();
  const wrapper = useRef<HTMLDivElement>(null);
  const hint = useRef<HTMLSpanElement>(null);
  const [below, setBelow] = useState(false);
  const position = () => {
    if (!wrapper.current || !hint.current) return;
    const rail = wrapper.current.closest("[data-pb-rail]");
    const top = rail && getComputedStyle(rail).overflowY === "auto" ? Math.max(0, rail.getBoundingClientRect().top) : 0;
    setBelow(wrapper.current.getBoundingClientRect().top - hint.current.offsetHeight < top);
  };
  return <div ref={wrapper} className="pb-discussion-action-hint" tabIndex={reason ? 0 : undefined}
    role={reason ? "group" : undefined} aria-label={reason ? `${label} unavailable` : undefined}
    aria-describedby={reason ? hintId : undefined} onMouseEnter={position} onFocus={position}>
    {children}
    {reason && <span ref={hint} id={hintId} role="tooltip" className="pb-discussion-action-tooltip" data-below={below || undefined}>
      {reason}
    </span>}
  </div>;
}

export function closeDiscussionActions(button: HTMLButtonElement): HTMLElement {
  const disclosure = button.closest("details");
  if (!disclosure) return button;
  disclosure.open = false;
  return disclosure.querySelector("summary") ?? button;
}
