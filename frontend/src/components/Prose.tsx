import hljs from "highlight.js/lib/common";
import { useEffect, useMemo, useRef, type ReactNode } from "react";
import { useDeepLinkHighlight } from "../lib/deepLink";
import { renderMermaidBlocks } from "../lib/mermaid";
import { ReadingTools, type ReadingContext } from "./ReadingTools";

/**
 * Server-rendered page HTML inside the stable `.pb-prose` selector. The server is the
 * single renderer (§5.8); this component adds presentation only:
 *
 *  - highlight.js over `pre code[class^=language-]` (§C5 — an unregistered language
 *    falls back to hljs auto-detection; either way styling flows through the
 *    `--pb-syntax-*` semantic tokens, never a bundled hljs theme)
 *  - Mermaid rendering for canonical lowercase `mermaid` fences, with source fallback
 *  - heading anchor links on the ids the server emitted
 *  - deep-link `#fragment` scroll + pulse once the content is in the DOM (Resolution 1)
 */
export function Prose({ html, metadata, title, reading }: { html: string; metadata?: ReactNode; title?: string; reading?: ReadingContext }) {
  const ref = useRef<HTMLElement>(null);
  const parts = useMemo(() => {
    if (!metadata) return null;
    const template = document.createElement("template");
    template.innerHTML = html;
    const first = Array.from(template.content.childNodes).find((node) => node.nodeType !== Node.TEXT_NODE || node.textContent?.trim());
    const matchesTitle = first instanceof HTMLElement && /^H[1-6]$/.test(first.tagName)
      && first.textContent?.trim().toLowerCase() === title?.trim().toLowerCase();
    if (!(first instanceof HTMLElement) || (first.tagName !== "H1" && !matchesTitle)) return { title: null, body: html };
    const heading = first.outerHTML;
    first.remove();
    return { title: heading, body: template.innerHTML };
  }, [html, title, !!metadata]);

  useEffect(() => {
    const container = ref.current;
    if (!container) return;
    highlightCodeBlocks(container);
    injectHeadingAnchors(container);
    return renderMermaidBlocks(container);
  }, [html, parts?.title]);

  // `ready` is a synchronous derived value (NOT useState): the content for THIS html is
  // committed by the time the hook's own effect runs, so the scroll lands on first commit.
  // `html` identity drives a re-scroll on a cross-page nav that keeps the same fragment.
  useDeepLinkHighlight(html.length > 0, html);

  // dangerouslySetInnerHTML is safe here: the html is server-sanitized (§C3, escapeHtml)
  if (parts) return <><article ref={ref} data-pb-selection-surface className="pb-reading-article">
    {parts.title ? <div className="pb-prose pb-title-prose" dangerouslySetInnerHTML={{ __html: parts.title }} /> :
      <h1 className="pb-reading-title" data-pb-selection-chrome>{title}</h1>}
    {metadata}
    <div className="pb-prose" data-pb-prose dangerouslySetInnerHTML={{ __html: parts.body }} />
  </article>{reading && <ReadingTools key={parts.title ? "titled" : "untitled"} article={ref} context={reading} html={html} />}</>;
  return <><article ref={ref} className="pb-prose" data-pb-prose data-pb-selection-surface dangerouslySetInnerHTML={{ __html: html }} />
    {reading && <ReadingTools article={ref} context={reading} html={html} />}</>;
}

export function highlightCodeBlocks(container: HTMLElement): void {
  container.querySelectorAll<HTMLElement>('pre code[class^="language-"], pre code[class*=" language-"]').forEach((block) => {
    const language = [...block.classList].find((c) => c.startsWith("language-"))?.slice("language-".length);
    if (language === "mermaid") return;
    if (language && hljs.getLanguage(language)) {
      hljs.highlightElement(block);
    } else {
      // hljs v11 skips (and warns on) unregistered languages, e.g. the fixtures' `hcl`.
      // Fall back to auto-detection over the registered common set; output is generated
      // from the block's text, so the server's sanitization guarantee is preserved.
      block.innerHTML = hljs.highlightAuto(block.textContent ?? "").value;
      block.classList.add("hljs");
    }
  });
}

export function injectHeadingAnchors(container: HTMLElement): void {
  container.querySelectorAll<HTMLElement>("h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]").forEach((heading) => {
    if (heading.querySelector(".pb-heading-anchor")) return;
    const anchor = heading.ownerDocument.createElement("a");
    anchor.className = "pb-heading-anchor";
    anchor.href = `#${heading.id}`;
    anchor.textContent = "#"; // selectionAnchor's chrome check counts this literal when a range crosses the link.
    anchor.setAttribute("aria-label", `Link to ${heading.textContent ?? heading.id}`);
    heading.appendChild(anchor);
  });
}
