import { useEffect, type RefObject } from "react";
import type { DiscussionDetail } from "../api/types";

type Source = { root: string | null; id: string; path: string; hash: string | null; html: string; ready: boolean };

/** Paint existing source blocks only; source text and the browser Selection remain authoritative. */
export function useDiscussionHighlight(wrapper: RefObject<HTMLDivElement | null>, source: Source, detail: DiscussionDetail | null) {
  useEffect(() => {
    const article = wrapper.current?.querySelector<HTMLElement>("[data-pb-selection-surface]");
    const range = detail?.range;
    if (!article || !source.ready || !source.root || !source.hash || detail?.range_content_hash !== source.hash || !range ||
      !["exact", "moved"].includes(detail.state) ||
      (detail.page.resolution === "by_path" ? detail.page.path !== source.path : detail.page.id !== source.id) ||
      !Number.isSafeInteger(range.byte_start) || !Number.isSafeInteger(range.byte_end) || range.byte_start < 0 || range.byte_end <= range.byte_start) return;
    let painted: HTMLElement[] = [];
    const clear = () => { for (const element of painted) element.classList.remove("pb-discussion-passage"); painted = []; };
    const paint = () => {
      clear();
      const blocks = Array.from(article.querySelectorAll<HTMLElement>("[data-pb-src]")).flatMap((element) => {
        if (element.closest("[data-pb-selection-chrome]")) return [];
        const match = /^([0-9]+)-([0-9]+)$/.exec(element.getAttribute("data-pb-src") ?? "");
        if (!match) return [];
        const start = Number(match[1]); const end = Number(match[2]);
        return Number.isSafeInteger(start) && Number.isSafeInteger(end) && start >= 0 && end > start &&
          start < range.byte_end && end > range.byte_start ? [{ element, start, end }] : [];
      });
      const minimal = blocks.filter((block) => {
        const children = blocks.filter((child) => child !== block && block.element.contains(child.element)).sort((a, b) => a.start - b.start);
        let covered = Math.max(block.start, range.byte_start);
        for (const child of children) {
          if (child.start > covered) break;
          covered = Math.max(covered, child.end);
        }
        return covered < Math.min(block.end, range.byte_end);
      });
      painted = minimal.filter((block) => !minimal.some((parent) => parent !== block && parent.element.contains(block.element)))
        .map((block) => block.element);
      for (const element of painted) element.classList.add("pb-discussion-passage");
    };
    paint();
    // Mermaid replaces its source asynchronously; class-only updates do not retrigger this observer.
    const observer = new MutationObserver(paint);
    observer.observe(article, { childList: true, subtree: true });
    return () => { observer.disconnect(); clear(); };
  }, [wrapper, source.root, source.id, source.path, source.hash, source.html, source.ready, detail]);
}
