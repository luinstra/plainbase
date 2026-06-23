import { afterEach } from "vitest";
import { clearCsrfToken } from "./api/csrf";

// The CSRF helper caches the `/session` token in a module-level promise (a per-session value); that
// cache outlives a single test and would otherwise leak across tests/files, making the extra async
// `/session` hop a mutation now incurs fire (and resolve) non-deterministically — late enough that a
// post-save error-unmount's React work can land after jsdom teardown ("window is not defined"). Clear
// it after every test so each starts from a cold cache (first mutation re-fetches against its own stub).
afterEach(() => clearCsrfToken());

// Vitest jsdom setup: jsdom implements no matchMedia; the app uses it only for the
// system color-scheme default, so a static light-preference stub suffices.
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList;
}
