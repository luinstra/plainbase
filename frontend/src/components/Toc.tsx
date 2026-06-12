import type { HeadingDto } from "../api/types";

/** "On this page" rail built from the server's `headings` array (ids are server-owned). */
export function Toc({ headings }: { headings: HeadingDto[] }) {
  const items = headings.filter((h) => h.level === 2 || h.level === 3);
  if (items.length < 2) return null;

  return (
    <nav
      className="pb-toc sticky top-20 hidden max-h-[calc(100vh-6rem)] w-56 shrink-0 overflow-y-auto text-sm xl:block"
      data-pb-toc
      aria-label="On this page"
    >
      <p className="mb-2 font-semibold text-ink">On this page</p>
      <ul className="space-y-1 border-l border-edge">
        {items.map((heading) => (
          <li key={heading.id} className={heading.level === 3 ? "pl-6" : "pl-3"}>
            <a href={`#${heading.id}`} className="block py-0.5 text-muted hover:text-ink">
              {heading.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
