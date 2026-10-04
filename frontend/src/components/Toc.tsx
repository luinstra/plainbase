import type { HeadingDto } from "../api/types";
import { useEffect, useRef, useState } from "react";

/** "On this page" rail built from the server's `headings` array (ids are server-owned). */
export function Toc({ headings }: { headings: HeadingDto[] }) {
  const nav = useRef<HTMLElement>(null);
  const [active, setActive] = useState<string | null>(null);
  useEffect(() => {
    if (headings.filter((heading) => heading.level === 2 || heading.level === 3).length < 2) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      const scope = nav.current?.closest(".pb-reading-layout") ?? document;
      const candidates = headings.filter((heading) => heading.level === 2 || heading.level === 3)
        .map((heading) => ({ id: heading.id, element: Array.from(scope.querySelectorAll<HTMLElement>("h2[id], h3[id]")).find((element) => element.id === heading.id) }))
        .filter((heading) => heading.element);
      const before = candidates.filter((heading) => heading.element!.getBoundingClientRect().top <= 96);
      const atEnd = window.scrollY > 0 && window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2;
      setActive((atEnd ? candidates.at(-1) : before.at(-1) ?? candidates[0])?.id ?? null);
    };
    const schedule = () => { if (!frame) frame = requestAnimationFrame(update); };
    update();
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    return () => { cancelAnimationFrame(frame); window.removeEventListener("scroll", schedule); window.removeEventListener("resize", schedule); };
  }, [headings]);
  const items = headings.filter((h) => h.level === 2 || h.level === 3);
  if (items.length < 2) return null;

  return (
    <nav ref={nav} className="pb-toc mb-8 text-sm" data-pb-toc aria-label="On this page">
      <p className="mb-2 font-semibold text-ink">On this page</p>
      <ul className="space-y-1 border-l border-edge">
        {items.map((heading) => (
          <li key={heading.id} className={heading.level === 3 ? "pl-6" : "pl-3"}>
            <a href={`#${heading.id}`} aria-current={active === heading.id ? "location" : undefined} className="block py-0.5 text-muted hover:text-ink">
              {heading.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
