import { Outlet, useRouter } from "@tanstack/react-router";
import type { MouseEvent } from "react";
import { interceptableHref } from "../lib/links";
import { SearchPalette } from "./SearchPalette";
import { Sidebar } from "./Sidebar";
import { ThemeToggle } from "./ThemeToggle";

/** Opens the (always-mounted) palette via its custom event — the click counterpart to Cmd/Ctrl+K. */
function SearchTrigger() {
  return (
    <button
      type="button"
      onClick={() => document.dispatchEvent(new CustomEvent("pb:search-open"))}
      className="pb-search-trigger flex items-center gap-2 rounded-md border border-edge bg-surface px-3 py-1.5 text-sm text-muted hover:text-ink"
      data-pb-search-trigger
      aria-label="Search"
    >
      <span aria-hidden="true">⌕</span>
      <span className="max-sm:hidden">Search</span>
      <kbd className="ml-2 rounded border border-edge px-1.5 font-mono text-xs text-faint max-sm:hidden">⌘K</kbd>
    </button>
  );
}

/**
 * App shell: header + tree sidebar + content outlet. One delegated click handler routes
 * every internal `/docs/...` / `/p/...` anchor — sidebar links AND links inside the
 * server-rendered prose — through the SPA router; external links keep native behavior
 * (lib/links.ts decides).
 */
export function Shell() {
  const router = useRouter();

  const onClick = (event: MouseEvent) => {
    const href = interceptableHref(event.nativeEvent);
    if (href) {
      event.preventDefault();
      router.history.push(href);
    }
  };

  return (
    <div className="pb-shell min-h-screen bg-surface text-ink" data-pb-shell onClick={onClick}>
      <header
        className="pb-header sticky top-0 z-10 flex h-14 items-center justify-between border-b border-edge bg-raised px-5"
        data-pb-header
      >
        <a href="/" className="pb-logo-home flex items-center" aria-label="Plainbase" data-pb-home>
          <img className="pb-logo pb-logo-light" src="/plainbase-logo.svg" alt="" aria-hidden="true" />
          <img className="pb-logo pb-logo-dark" src="/plainbase-logo-dark.svg" alt="" aria-hidden="true" />
        </a>
        <div className="flex items-center gap-3">
          <SearchTrigger />
          <ThemeToggle />
        </div>
      </header>
      <div className="mx-auto flex w-full max-w-screen-2xl">
        <Sidebar />
        <main className="pb-main min-w-0 flex-1 px-6 py-8 lg:px-12" data-pb-main>
          <Outlet />
        </main>
      </div>
      <SearchPalette />
    </div>
  );
}
