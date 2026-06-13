import { Outlet, useRouter } from "@tanstack/react-router";
import type { MouseEvent } from "react";
import { interceptableHref } from "../lib/links";
import { Sidebar } from "./Sidebar";
import { ThemeToggle } from "./ThemeToggle";

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
        <ThemeToggle />
      </header>
      <div className="mx-auto flex w-full max-w-screen-2xl">
        <Sidebar />
        <main className="pb-main min-w-0 flex-1 px-6 py-8 lg:px-12" data-pb-main>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
