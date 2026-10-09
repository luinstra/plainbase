import { useEffect, useRef, type RefObject } from "react";
import { captureSelectionAnchor, type SelectionCapture } from "./selectionAnchor";

const unavailable = { reason: "Select text in the page and try again, or discuss the whole page." };

/** Retain article selections across focus transfer; capture only on explicit activation. */
export function useDiscussionSelection(wrapper: RefObject<HTMLDivElement | null>, identity: string, hash: string | null, ready: boolean) {
  const candidate = useRef<{ identity: string; hash: string; capture: SelectionCapture } | null>(null);
  const current = useRef({ identity, hash, ready });
  current.current = { identity, hash, ready };
  useEffect(() => { candidate.current = null; }, [identity, hash, ready]);
  useEffect(() => {
    if (!ready) return;
    const beginSelection = (event: PointerEvent) => {
      const article = wrapper.current?.querySelector<HTMLElement>("[data-pb-selection-surface], .pb-prose");
      // Clicking blank space in the article can remove every range without leaving a caret.
      // Discard the previous passage; a new drag will repopulate it through selectionchange.
      if (event.target instanceof Node && article?.contains(event.target)) candidate.current = null;
    };
    const retain = () => {
      const article = wrapper.current?.querySelector<HTMLElement>("[data-pb-selection-surface], .pb-prose");
      const selection = window.getSelection();
      const source = current.current;
      if (!article || !selection || selection.rangeCount !== 1 || !source.hash) return;
      const range = selection.getRangeAt(0);
      if (!article.contains(range.startContainer) || !article.contains(range.endContainer)) return;
      candidate.current = selection.isCollapsed ? null : { identity: source.identity, hash: source.hash,
        capture: captureSelectionAnchor(article, selection, source.hash) };
    };
    document.addEventListener("pointerdown", beginSelection);
    document.addEventListener("selectionchange", retain);
    return () => {
      document.removeEventListener("pointerdown", beginSelection);
      document.removeEventListener("selectionchange", retain);
    };
  }, [wrapper, ready]);
  const captureIfSelected = (rejectOutsideSelection = false): SelectionCapture | null => {
    const source = current.current;
    if (!source.ready || !source.hash || source.identity !== identity || source.hash !== hash)
      return { reason: "The page could not be read. Reload it before selecting a passage." };
    const article = wrapper.current?.querySelector<HTMLElement>("[data-pb-selection-surface], .pb-prose");
    const selection = window.getSelection();
    if (selection && selection.rangeCount > 1) return unavailable;
    if (article && selection?.rangeCount === 1) {
      const range = selection.getRangeAt(0);
      if (article.contains(range.startContainer) && article.contains(range.endContainer))
        candidate.current = selection.isCollapsed ? null : { identity: source.identity, hash: source.hash,
          capture: captureSelectionAnchor(article, selection, source.hash) };
      else if (rejectOutsideSelection && !selection.isCollapsed) return unavailable;
    }
    const retained = candidate.current;
    return retained?.identity === source.identity && retained.hash === source.hash ? retained.capture : null;
  };
  const capture = (rejectOutsideSelection = false): SelectionCapture => captureIfSelected(rejectOutsideSelection) ?? unavailable;
  const reset = () => {
    candidate.current = null;
    const article = wrapper.current?.querySelector<HTMLElement>("[data-pb-selection-surface], .pb-prose");
    const selection = window.getSelection();
    if (!article || !selection || selection.rangeCount !== 1) return;
    const range = selection.getRangeAt(0);
    if (article.contains(range.startContainer) && article.contains(range.endContainer)) selection.removeAllRanges();
  };
  return { capture, captureIfSelected, reset };
}
