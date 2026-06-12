import hljs from "highlight.js/lib/common";
import { useEffect, useRef } from "react";

/**
 * Server-rendered page HTML inside the stable `.pb-prose` selector. The server is the
 * single renderer (§5.8); this component adds presentation only:
 *
 *  - highlight.js over `pre code[class^=language-]` (§C5 — an unregistered language
 *    falls back to hljs auto-detection; either way styling flows through the
 *    `--pb-syntax-*` semantic tokens, never a bundled hljs theme)
 *  - heading anchor links on the ids the server emitted
 *  - deep-link `#fragment` scroll once the content is in the DOM
 */
export function Prose({ html }: { html: string }) {
  const ref = useRef<HTMLElement>(null);

  useEffect(() => {
    const container = ref.current;
    if (!container) return;
    highlightCodeBlocks(container);
    injectHeadingAnchors(container);
    scrollToLocationHash();
  }, [html]);

  // dangerouslySetInnerHTML is safe here: the html is server-sanitized (§C3, escapeHtml)
  return <article ref={ref} className="pb-prose" data-pb-prose dangerouslySetInnerHTML={{ __html: html }} />;
}

export function highlightCodeBlocks(container: HTMLElement): void {
  container.querySelectorAll<HTMLElement>('pre code[class^="language-"], pre code[class*=" language-"]').forEach((block) => {
    const language = [...block.classList].find((c) => c.startsWith("language-"))?.slice("language-".length);
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
    anchor.textContent = "#";
    anchor.setAttribute("aria-label", `Link to ${heading.textContent ?? heading.id}`);
    heading.appendChild(anchor);
  });
}

function scrollToLocationHash(): void {
  const hash = window.location.hash;
  if (!hash) return;
  const id = decodeURIComponent(hash.slice(1));
  document.getElementById(id)?.scrollIntoView();
}
