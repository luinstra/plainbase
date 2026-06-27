import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";
import { clearCsrfToken } from "./api/csrf";

// Raise the Testing-Library async-util ceiling above the 1000ms default. Our `waitFor`s poll for
// async UI (router transitions, debounced search, query settles); on a slow/contended CI runner (30
// jsdom test files in one process) a correct assertion can need >1s to settle, and the default ceiling
// surfaced as flaky "expected … not to be null" timeouts that never reproduce locally. This only widens
// how long a `waitFor` POLLS before giving up — a fast-passing test is unaffected, and a genuinely
// broken one (the node never appears) still fails — so it buys CI headroom without masking a real bug.
configure({ asyncUtilTimeout: 5000 });

// Unmount every rendered tree after each test. We do NOT set vitest `globals: true`, so RTL's
// auto-cleanup is not registered — without this, a test file that leaves a component mounted leaks its
// effects past teardown. The concrete failure: `useDebounced` (e.g. EditorPage's 300ms buffer debounce,
// the search overlay) schedules a `setTimeout(setState)`; on a slow CI runner the timer fires AFTER the
// test/jsdom tears down, surfacing as a Vitest "unhandled error" that fails the run even though every
// test passed. Unmounting runs each effect's cleanup (`clearTimeout`), so no debounce timer can outlive
// its test. Idempotent for files that already call `cleanup()`; a no-op for non-rendering tests.
afterEach(() => cleanup());

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
