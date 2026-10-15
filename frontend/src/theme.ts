export type DisplayMode = "light" | "dark";

const STORAGE_KEY = "pb-theme";
const SYSTEM_QUERY = "(prefers-color-scheme: dark)";

let sessionMode: DisplayMode | undefined;
let currentMode: DisplayMode | undefined;
const subscribers = new Set<() => void>();
let stopSystemListener: (() => void) | undefined;

/**
 * Resolves fresh: an explicit session choice wins even when persistence failed.
 * index.html carries a pre-paint twin of this rule; display-mode-parity.test.ts keeps the two in step.
 */
export function resolveDisplayMode(): DisplayMode {
  if (sessionMode !== undefined) return sessionMode;
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "light" || stored === "dark") return stored;
  } catch {
    // Unreadable storage still permits the system preference.
  }
  try {
    return window.matchMedia(SYSTEM_QUERY).matches ? "dark" : "light";
  } catch {
    return "light";
  }
}

/** The stable snapshot changes only through an owner update after its first read. */
export function getDisplayMode(): DisplayMode {
  return currentMode ??= resolveDisplayMode();
}

export function applyDisplayMode(mode: DisplayMode): void {
  if (mode === "dark") {
    document.documentElement.dataset.theme = "dark";
  } else {
    delete document.documentElement.dataset.theme;
  }
}

function updateDisplayMode(projectUnchanged = false): void {
  const previous = getDisplayMode();
  const next = resolveDisplayMode();
  if (next === previous) {
    if (projectUnchanged) applyDisplayMode(next);
    return;
  }
  currentMode = next;
  // Subscribers sample computed styles, so projection precedes notification.
  applyDisplayMode(next);
  for (const subscriber of [...subscribers]) subscriber();
}

function handleSystemChange(): void {
  updateDisplayMode();
}

function listenToSystem(): (() => void) | undefined {
  try {
    const media = window.matchMedia(SYSTEM_QUERY);
    media.addEventListener("change", handleSystemChange);
    return () => media.removeEventListener("change", handleSystemChange);
  } catch {
    // Listener availability cannot override a stored or session choice.
  }
}

export function subscribeDisplayMode(callback: () => void): () => void {
  const notify = () => callback();
  const first = subscribers.size === 0;
  if (first) {
    // Resync before registering the new callback: subscribing must not call it.
    updateDisplayMode(true);
    stopSystemListener = listenToSystem();
  }
  subscribers.add(notify);
  let active = true;
  return () => {
    if (!active) return;
    active = false;
    subscribers.delete(notify);
    if (subscribers.size === 0) {
      stopSystemListener?.();
      stopSystemListener = undefined;
    }
  };
}

export function toggleDisplayMode(): DisplayMode {
  const next: DisplayMode = getDisplayMode() === "dark" ? "light" : "dark";
  sessionMode = next;
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // The session choice survives until reload when storage is unavailable.
  }
  updateDisplayMode();
  return getDisplayMode();
}
