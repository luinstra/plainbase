import { afterEach, describe, expect, it, vi } from "vitest";
import { applyTheme, resolveTheme, toggleTheme } from "../theme";

/** Dark mode is a pure data-theme attribute swap — nothing else may change. */

afterEach(() => {
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

describe("theme", () => {
  it("applyTheme swaps only the data-theme attribute", () => {
    applyTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    applyTheme("light");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("a stored preference overrides the system default", () => {
    localStorage.setItem("pb-theme", "dark");
    expect(resolveTheme()).toBe("dark");
    localStorage.setItem("pb-theme", "light");
    expect(resolveTheme()).toBe("light");
  });

  it("toggleTheme persists the explicit choice", () => {
    localStorage.setItem("pb-theme", "light");
    expect(toggleTheme()).toBe("dark");
    expect(localStorage.getItem("pb-theme")).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(toggleTheme()).toBe("light");
    expect(localStorage.getItem("pb-theme")).toBe("light");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("survives a storage-disabled context: system fallback, toggle works in-memory", async () => {
    // Sandboxed iframes / privacy modes make localStorage THROW on any access.
    const denied = () => {
      throw new DOMException("denied", "SecurityError");
    };
    vi.stubGlobal("localStorage", { getItem: denied, setItem: denied } as unknown as Storage);
    vi.resetModules();
    try {
      const theme = await import("../theme");
      expect(theme.resolveTheme()).toBe("light"); // matchMedia stub: system prefers light
      expect(theme.toggleTheme()).toBe("dark");
      expect(document.documentElement.dataset.theme).toBe("dark");
      expect(theme.toggleTheme()).toBe("light");
      expect(document.documentElement.dataset.theme).toBeUndefined();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
