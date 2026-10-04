import { useEffect, useId, useRef, useState, type RefObject } from "react";
import { createPortal } from "react-dom";
import { NewPageLink } from "./NewPageFlow";

export interface ReadingContext { root: string; path: string; url: string | null; editable: boolean }

function isMarkdownTarget(target: string): boolean {
  try {
    return decodeURIComponent(target.split("#", 1)[0].split("?", 1)[0]).endsWith(".md");
  } catch {
    return false;
  }
}

/** Contextual controls stay outside the source fragment so they cannot become quoted page text. */
export function ReadingTools({ article, context, html }: { article: RefObject<HTMLElement | null>; context: ReadingContext; html: string }) {
  const [target, setTarget] = useState<HTMLElement | null>(null);
  const [notice, setNotice] = useState("");
  const [, updatePosition] = useState(0);
  const card = useRef<HTMLDivElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const id = useId();
  const current = useRef(target);
  current.current = target;
  const keep = () => { if (timer.current) clearTimeout(timer.current); };
  const leave = () => { keep(); timer.current = setTimeout(() => setTarget(null), 150); };

  useEffect(() => {
    const root = article.current;
    if (!root) return;
    setTarget(null);
    const links = Array.from(root.querySelectorAll<HTMLElement>("a[data-pb-link-error]"));
    for (const link of links) {
      link.tabIndex = 0;
      link.setAttribute("role", "button");
      link.setAttribute("aria-controls", id);
      link.setAttribute("aria-haspopup", "dialog");
    }
    const copies: HTMLElement[] = [];
    const codeIds = new Map<HTMLElement, string | null>();
    for (const code of root.querySelectorAll<HTMLElement>("code")) {
      const value = code.textContent ?? "";
      if (code.closest("pre, a") || !/\S{48}/u.test(value) || code.parentElement?.classList.contains("pb-inline-token")) continue;
      const wrapper = document.createElement("span");
      wrapper.className = "pb-inline-token";
      code.before(wrapper); wrapper.append(code);
      codeIds.set(code, code.getAttribute("id"));
      if (!code.id) code.id = `${id}-code-${copies.length}`;
      const reveal = document.createElement("button");
      reveal.type = "button"; reveal.className = "pb-inline-copy";
      reveal.setAttribute("aria-controls", code.id);
      reveal.setAttribute("aria-expanded", "false");
      reveal.setAttribute("aria-label", "Show full code"); reveal.title = "Show full code";
      reveal.setAttribute("data-pb-selection-chrome", "");
      reveal.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="m9 5-7 7 7 7m6-14 7 7-7 7"/></svg>';
      reveal.onclick = () => {
        const expanded = reveal.getAttribute("aria-expanded") !== "true";
        reveal.setAttribute("aria-expanded", String(expanded));
        reveal.setAttribute("aria-label", expanded ? "Collapse code" : "Show full code");
        reveal.title = expanded ? "Collapse code" : "Show full code";
        wrapper.toggleAttribute("data-pb-code-expanded", expanded);
      };
      wrapper.append(reveal);
      const copy = document.createElement("button");
      copy.type = "button"; copy.className = "pb-inline-copy";
      copy.setAttribute("aria-label", "Copy code"); copy.title = "Copy code";
      copy.setAttribute("data-pb-selection-chrome", "");
      copy.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V4H4v12h4"/></svg>';
      copy.onclick = () => {
        if (!navigator.clipboard) { setNotice("Could not copy code"); return; }
        void navigator.clipboard.writeText(value).then(() => setNotice("Code copied"), () => setNotice("Could not copy code"));
      };
      wrapper.append(copy); copies.push(wrapper);
    }
    const linkOf = (event: Event) => event.target instanceof Element ? event.target.closest<HTMLElement>("a[data-pb-link-error]") : null;
    const enter = (event: Event) => { const link = linkOf(event); if (link) { keep(); setTarget(link); } };
    const activate = (event: Event) => { const link = linkOf(event); if (link) { event.preventDefault(); keep(); setTarget(link); } };
    const key = (event: KeyboardEvent) => {
      if ((event.key === "Enter" || event.key === " ") && linkOf(event)) activate(event);
      if (event.key === "Tab" && !event.shiftKey && event.target === current.current) {
        const first = card.current?.querySelector<HTMLElement>("a[href], button");
        if (first) { event.preventDefault(); first.focus(); }
      }
      if (event.key === "Tab" && event.shiftKey && event.target === card.current?.querySelector("a[href], button")) {
        event.preventDefault(); current.current?.focus();
      }
      if (event.key === "Tab" && !event.shiftKey && card.current?.contains(event.target as Node)) {
        const actions = card.current.querySelectorAll<HTMLElement>("a[href], button");
        if (event.target === actions[actions.length - 1]) {
          const controls = Array.from(document.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]'))
            .filter((element) => !card.current?.contains(element) && element.getClientRects().length > 0);
          const next = controls[controls.indexOf(current.current!) + 1];
          if (next) { event.preventDefault(); next.focus(); }
          keep(); setTarget(null);
        }
      }
      if (event.key === "Escape" && event.target instanceof Node && (current.current?.contains(event.target) || card.current?.contains(event.target))) {
        keep(); current.current?.focus(); setTarget(null);
      }
    };
    const close = () => { keep(); setTarget(null); };
    const scroll = (event: Event) => {
      if (current.current && !(event.target instanceof Node && card.current?.contains(event.target))) updatePosition((position) => position + 1);
    };
    const outside = (event: PointerEvent) => { if (event.target instanceof Node && !current.current?.contains(event.target) && !card.current?.contains(event.target)) setTarget(null); };
    root.addEventListener("mouseover", enter); root.addEventListener("focusin", enter);
    root.addEventListener("mouseout", leave); root.addEventListener("focusout", leave);
    root.addEventListener("click", activate); document.addEventListener("keydown", key); document.addEventListener("pointerdown", outside);
    window.addEventListener("scroll", scroll, true); window.addEventListener("resize", close);
    return () => {
      keep();
      root.removeEventListener("mouseover", enter); root.removeEventListener("focusin", enter);
      root.removeEventListener("mouseout", leave); root.removeEventListener("focusout", leave);
      root.removeEventListener("click", activate); document.removeEventListener("keydown", key); document.removeEventListener("pointerdown", outside);
      window.removeEventListener("scroll", scroll, true); window.removeEventListener("resize", close);
      for (const wrapper of copies) {
        const code = wrapper.querySelector("code");
        if (code) {
          const originalId = codeIds.get(code);
          if (originalId == null) code.removeAttribute("id"); else code.id = originalId;
          wrapper.replaceWith(code);
        }
      }
      for (const link of links) { link.removeAttribute("tabindex"); link.removeAttribute("role"); link.removeAttribute("aria-controls"); link.removeAttribute("aria-haspopup"); }
    };
  }, [article, html, id]);

  const rect = target?.getBoundingClientRect();
  const top = rect ? Math.max(12, Math.min(rect.bottom + 8, window.innerHeight - 280)) : 12;
  const missing = target?.dataset.pbLinkError === "broken_missing";
  const missingPage = missing && isMarkdownTarget(target?.dataset.pbLinkTarget ?? "");
  const outside = target?.dataset.pbLinkError === "outside_content_root";
  const title = missingPage ? "This page doesn't exist" : missing ? "Target not found" : outside ? "Outside this space" :
    target?.dataset.pbLinkError === "blocked_scheme" ? "This link is blocked" : "This link is unavailable";
  const folder = context.path.split("/").slice(0, -1).join("/");
  return <>
    <span role="status" className="sr-only">{notice}</span>
    {target && rect && createPortal(<div ref={card} id={id} role="dialog" aria-label={title} className="pb-link-card"
      style={{ top, maxHeight: Math.min(400, window.innerHeight - top - 12), left: Math.max(12, Math.min(rect.left, window.innerWidth - 332)) }}
      onMouseEnter={keep} onMouseLeave={leave} onFocus={keep} onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) leave(); }}>
      <p className="font-semibold text-ink">{title}</p>
      {outside && <p className="mt-2">This target is outside the current space and cannot be opened here.</p>}
      <p className="mt-2">Target: <code>{(target.dataset.pbLinkTarget ?? "Unknown target").slice(0, 2048)}</code></p>
      {context.editable && <div className="mt-3 flex gap-4">
        {missingPage && <NewPageLink root={context.root} folder={folder} className="text-link hover:underline">Create page</NewPageLink>}
        {context.url && <a href={`${context.url}?mode=edit`} className="text-link hover:underline">Edit link</a>}
      </div>}
    </div>, document.body)}
  </>;
}
