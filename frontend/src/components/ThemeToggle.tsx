import { useSyncExternalStore } from "react";
import { getDisplayMode, subscribeDisplayMode, toggleDisplayMode } from "../theme";

/**
 * Display-mode controls share the owner's snapshot and system subscription.
 */
export function ThemeToggle() {
  const mode = useSyncExternalStore(subscribeDisplayMode, getDisplayMode);
  const dark = mode === "dark";
  return (
    <button
      type="button"
      data-pb-theme-toggle
      onClick={() => toggleDisplayMode()}
      aria-label={dark ? "Switch to light mode" : "Switch to dark mode"}
      title={dark ? "Switch to light mode" : "Switch to dark mode"}
      className="rounded border border-edge p-1.5 text-muted hover:bg-hovered hover:text-ink"
    >
      {dark ? <MoonIcon /> : <SunIcon />}
    </button>
  );
}

function SunIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </svg>
  );
}
