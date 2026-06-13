import { useRouterState } from "@tanstack/react-router";
import { useEffect } from "react";

/**
 * Deep-link landing (Resolution 1): scroll the `#fragment` target into view and pulse it.
 *
 * Replaces the old private `scrollToLocationHash` in `Prose`. It depends on BOTH `ready`
 * (the page DOM that should contain the target now exists) AND the reactive router
 * `location.hash`, so a hash-only change on an already-mounted page re-fires (the gap the
 * mount-only handler never closed). Consumed inside the same `Prose` effect that injects
 * `html`, so the target heading is provably in the DOM on first commit.
 *
 * `ready` MUST be a synchronous ref/derived value (e.g. `html.length > 0`), NOT a
 * `useState` flag — a state setter would push the scroll a render past content commit and
 * make "scroll after commit" race-dependent. `htmlKey` is the `Prose` `html` identity: a
 * cross-page navigation that lands on the same fragment string still re-scrolls because the
 * injected content (and thus the key) changed.
 *
 * Reconciliation with `scrollRestoration: true` (router.tsx): the hook acts only when the
 * hash is non-empty and scrolls AFTER content commit, so a deliberate fragment jump lands
 * last and wins; it never writes scroll position or history, so plain-nav restoration
 * offsets stay intact.
 */
export function useDeepLinkHighlight(ready: boolean, htmlKey?: string): void {
  const hash = useRouterState({ select: (s) => s.location.hash });

  useEffect(() => {
    if (!ready || !hash) return;
    const id = decodeURIComponent(hash.startsWith("#") ? hash.slice(1) : hash);
    const el = document.getElementById(id);
    if (!el) return; // stale/missing fragment: leave the natural (top) position, no throw

    el.scrollIntoView(); // argument-less ⇒ always instant; never { behavior: "smooth" }

    // Reduced motion: scroll still happens; the pulse class is simply never added.
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    el.classList.add("pb-deeplink-pulse");
    const clear = () => el.classList.remove("pb-deeplink-pulse");
    el.addEventListener("animationend", clear, { once: true });
    // Cosmetic fallback in case `animationend` never fires (detached node, backgrounded
    // tab). Keep ≈100 ms over the `pb-deeplink-pulse-kf` 1s keyframe in app.css — if that
    // duration changes, bump this so the class is not removed before the animation ends.
    const timer = window.setTimeout(clear, 1100);
    return () => {
      el.removeEventListener("animationend", clear);
      window.clearTimeout(timer);
    };
  }, [ready, hash, htmlKey]);
}
