/** Async discussion work may finish while its retained margin is hidden or replaced. */
export function focusDiscussionElement(element: HTMLElement | null | undefined) {
  if (!element?.isConnected || element.closest("[hidden]") || element.matches(":disabled, [aria-disabled='true']")) return false;
  for (let ancestor = element.parentElement; ancestor; ancestor = ancestor.parentElement) {
    if (ancestor instanceof HTMLDetailsElement && !ancestor.open && !ancestor.querySelector(":scope > summary")?.contains(element)) return false;
  }
  element.focus();
  return document.activeElement === element;
}
