import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeSystem } from "./displayModeTestSupport";

let theme: typeof import("../theme");
let system: ReturnType<typeof fakeSystem>;

beforeEach(async () => {
  vi.resetModules();
  system = fakeSystem();
  vi.stubGlobal("matchMedia", system.matchMedia);
  theme = await import("../theme");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

function denyStorage(): never {
  throw new DOMException("denied", "SecurityError");
}

describe("display mode resolution", () => {
  it("applyDisplayMode swaps only the data-theme attribute", () => {
    theme.applyDisplayMode("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    theme.applyDisplayMode("light");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("a stored preference overrides the system default", () => {
    localStorage.setItem("pb-theme", "dark");
    expect(theme.resolveDisplayMode()).toBe("dark");
    system.matches = true;
    localStorage.setItem("pb-theme", "light");
    expect(theme.resolveDisplayMode()).toBe("light");
  });

  it("follows a dark system preference without a stored choice", () => {
    system.matches = true;
    expect(theme.resolveDisplayMode()).toBe("dark");
  });

  it("follows the dark system preference when reading storage fails", () => {
    system.matches = true;
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(denyStorage);
    expect(theme.resolveDisplayMode()).toBe("dark");
  });

  it("keeps a toggled choice when a failed write leaves older storage readable", () => {
    localStorage.setItem("pb-theme", "light");
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(denyStorage);
    expect.soft(theme.toggleDisplayMode()).toBe("dark");
    expect.soft(theme.resolveDisplayMode()).toBe("dark");
    expect(localStorage.getItem("pb-theme")).toBe("light");
  });

  it("alternates repeated toggles when persistence fails", () => {
    localStorage.setItem("pb-theme", "light");
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(denyStorage);
    expect.soft(theme.toggleDisplayMode()).toBe("dark");
    expect.soft(theme.toggleDisplayMode()).toBe("light");
  });

  it("falls back to light when the system preference query throws", () => {
    system.throws = true;
    expect(theme.resolveDisplayMode()).toBe("light");
  });

  it("falls back to light when the system preference API is absent", () => {
    vi.stubGlobal("matchMedia", undefined);
    expect(theme.resolveDisplayMode()).toBe("light");
  });

  it("keeps a stored dark choice when the system preference query throws", () => {
    localStorage.setItem("pb-theme", "dark");
    system.throws = true;
    expect(theme.resolveDisplayMode()).toBe("dark");
  });

  it("persists explicit toggles when storage is writable", () => {
    localStorage.setItem("pb-theme", "light");
    expect(theme.toggleDisplayMode()).toBe("dark");
    expect(localStorage.getItem("pb-theme")).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(theme.toggleDisplayMode()).toBe("light");
    expect(localStorage.getItem("pb-theme")).toBe("light");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("keeps toggles in memory when storage is disabled", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(denyStorage);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(denyStorage);
    expect(theme.resolveDisplayMode()).toBe("light");
    expect(theme.toggleDisplayMode()).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(theme.toggleDisplayMode()).toBe("light");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });
});
