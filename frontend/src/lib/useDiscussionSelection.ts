import { useEffect, useRef, type RefObject } from "react";
import { captureSelectionAnchor, type SelectionCapture } from "./selectionAnchor";

const unavailable = { reason: "Select text in the page and try again, or discuss the whole page." };

/** Retain article selections across focus transfer; capture only on explicit activation. */
export function useDiscussionSelection(wrapper: RefObject<HTMLDivElement | null>, identity: string, hash: string | null, ready: boolean) {
  const candidate = useRef<{ identity: string; hash: string; capture: SelectionCapture } | null>(null);
  const current = useRef({ identity, hash, ready });
  current.current = { identity, hash, ready };
  useEffect(() => { candidate.current = null; }, [identity, hash]);
  useEffect(() => {
    const retain = () => {
      const article = wrapper.current?.querySelector<HTMLElement>(".pb-prose");
      const selection = window.getSelection();
      const source = current.current;
      if (!article || !selection || selection.rangeCount !== 1 || !source.hash) return;
      const range = selection.getRangeAt(0);
      if (!article.contains(range.startContainer) || !article.contains(range.endContainer)) return;
      candidate.current = { identity: source.identity, hash: source.hash,
        capture: captureSelectionAnchor(article, selection, source.hash) };
    };
    document.addEventListener("selectionchange", retain);
    return () => document.removeEventListener("selectionchange", retain);
  }, [wrapper]);
  const capture = (rejectOutsideSelection = false): SelectionCapture => {
    const source = current.current;
    if (!source.ready || !source.hash || source.identity !== identity || source.hash !== hash)
      return { reason: "The page could not be read. Reload it before selecting a passage." };
    const article = wrapper.current?.querySelector<HTMLElement>(".pb-prose");
    const selection = window.getSelection();
    if (article && selection?.rangeCount === 1) {
      const range = selection.getRangeAt(0);
      if (article.contains(range.startContainer) && article.contains(range.endContainer))
        candidate.current = { identity: source.identity, hash: source.hash,
          capture: captureSelectionAnchor(article, selection, source.hash) };
      else if (rejectOutsideSelection && !selection.isCollapsed) return unavailable;
    }
    const retained = candidate.current;
    return retained?.identity === source.identity && retained.hash === source.hash ? retained.capture : unavailable;
  };
  const reset = () => {
    candidate.current = null;
    const article = wrapper.current?.querySelector<HTMLElement>(".pb-prose");
    const selection = window.getSelection();
    if (!article || !selection || selection.rangeCount !== 1) return;
    const range = selection.getRangeAt(0);
    if (article.contains(range.startContainer) && article.contains(range.endContainer)) selection.removeAllRanges();
  };
  return { capture, reset };
}
