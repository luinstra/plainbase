import type { DiscussionQuoteRequestAnchor } from "../api/types";

export type SelectionCapture = DiscussionQuoteRequestAnchor | { reason: string };
const unavailable = { reason: "Select text in the page and try again, or discuss the whole page." };

/** Capture a rendered selection's source block attributes without converting browser text into byte offsets. */
export function captureSelectionAnchor(article: HTMLElement, selection: Selection | null, contentHash: string): SelectionCapture {
  if (!selection || selection.rangeCount !== 1 || selection.isCollapsed || !selection.toString().trim()) return unavailable;
  const range = selection.getRangeAt(0);
  if (!article.contains(range.startContainer) || !article.contains(range.endContainer)) return unavailable;
  if (rangeIntersectsChrome(range, article, selection.toString()))
    return { reason: "Reselect the page text without the heading link, or discuss the whole page." };
  const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT);
  const bounds = document.createRange();
  let start: [number, number] | null = null;
  let end: [number, number] | null = null;
  let node: Node | null;
  while ((node = walker.nextNode())) {
    const text = node as Text;
    if (!range.intersectsNode(text)) continue;
    bounds.selectNodeContents(text);
    const included = range.cloneRange();
    if (included.compareBoundaryPoints(Range.START_TO_START, bounds) < 0)
      included.setStart(bounds.startContainer, bounds.startOffset);
    if (included.compareBoundaryPoints(Range.END_TO_END, bounds) > 0)
      included.setEnd(bounds.endContainer, bounds.endOffset);
    if (!included.toString().trim()) continue;
    const source = selectedSource(article, text);
    if (!source) return unavailable;
    start ??= source;
    end = source;
  }
  if (!start || !end) return unavailable;
  return {
    kind: "quote", content_hash: contentHash,
    block_start: Math.min(start[0], end[0]), block_end: Math.max(start[1], end[1]),
    selected_text: selection.toString(),
  };
}

function selectedSource(article: HTMLElement, node: Node): [number, number] | null {
  let element = node instanceof Element ? node : node?.parentElement ?? null;
  while (element && element !== article) {
    const raw = element.getAttribute("data-pb-src");
    if (raw !== null) {
      const match = /^([0-9]+)-([0-9]+)$/.exec(raw);
      if (!match) return null;
      const start = Number(match[1]); const end = Number(match[2]);
      return Number.isSafeInteger(start) && Number.isSafeInteger(end) && start >= 0 && end > start ? [start, end] : null;
    }
    element = element.parentElement;
  }
  return null;
}

function rangeIntersectsChrome(range: Range, article: HTMLElement, selectedText: string): boolean {
  let includedLinks = 0;
  for (const anchor of article.querySelectorAll(".pb-heading-anchor")) {
    if (!range.intersectsNode(anchor)) continue;
    const included = range.cloneRange();
    const chrome = document.createRange();
    chrome.selectNodeContents(anchor);
    if (included.compareBoundaryPoints(Range.START_TO_START, chrome) < 0)
      included.setStart(chrome.startContainer, chrome.startOffset);
    if (included.compareBoundaryPoints(Range.END_TO_END, chrome) > 0)
      included.setEnd(chrome.endContainer, chrome.endOffset);
    if (!included.toString()) continue;
    if (included.toString() !== "#") return true;
    includedLinks++;
  }
  // Chromium's user selection omits generated, unselectable heading links even when the
  // geometric Range crosses them. Compare counts; never edit the browser's selected text.
  const countHashes = (text: string) => text.split("#").length - 1;
  return includedLinks > 0 && countHashes(range.toString()) - countHashes(selectedText) !== includedLinks;
}
