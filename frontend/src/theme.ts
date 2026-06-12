/**
 * Dark mode = a `data-theme` attribute swap on <html>, nothing else — the semantic token
 * tier in tokens.css does all the work (§5.9). System preference is the default; an
 * explicit toggle persists to localStorage. index.html carries a tiny inline bootstrap
 * copy of resolveTheme() so the first paint never flashes the wrong theme.
 */

export type Theme = "light" | "dark";

const STORAGE_KEY = "pb-theme";

// Storage-disabled contexts (sandboxed iframes, some privacy modes) make localStorage
// THROW on access — the inline bootstrap in index.html try/catches the same read, and so
// must we. On failure the choice lives here for the session; persistence is silently off.
let sessionTheme: string | null = null;

function readStored(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return sessionTheme;
  }
}

function writeStored(theme: Theme): void {
  sessionTheme = theme;
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // sessionTheme carries the choice until reload
  }
}

export function resolveTheme(): Theme {
  const stored = readStored();
  if (stored === "light" || stored === "dark") return stored;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export function applyTheme(theme: Theme): void {
  if (theme === "dark") {
    document.documentElement.dataset.theme = "dark";
  } else {
    delete document.documentElement.dataset.theme;
  }
}

export function toggleTheme(): Theme {
  const next: Theme = resolveTheme() === "dark" ? "light" : "dark";
  writeStored(next);
  applyTheme(next);
  return next;
}
