import { StrictMode } from "react";
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeSystem } from "./displayModeTestSupport";

let ThemeToggle: typeof import("../components/ThemeToggle").ThemeToggle;
let theme: typeof import("../theme");
let system: ReturnType<typeof fakeSystem>;
let observer: MutationObserver | undefined;

beforeEach(async () => {
  vi.resetModules();
  system = fakeSystem();
  vi.stubGlobal("matchMedia", system.matchMedia);
  theme = await import("../theme");
  ({ ThemeToggle } = await import("../components/ThemeToggle"));
});

afterEach(() => {
  cleanup();
  observer?.disconnect();
  observer = undefined;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

function renderToggles(count = 2) {
  return render(
    <StrictMode>
      {Array.from({ length: count }, (_, index) => <ThemeToggle key={index} />)}
    </StrictMode>,
  );
}

function denyStorage(): never {
  throw new DOMException("denied", "SecurityError");
}

describe("display mode controls", () => {
  it("shares one system listener across mounted toggles", () => {
    renderToggles();
    expect(system.activeListeners).toBe(1);
  });

  it("projects one root attribute record for a system mode change", async () => {
    const records: MutationRecord[] = [];
    observer = new MutationObserver((delivered) => records.push(...delivered));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    renderToggles();
    observer.takeRecords();
    records.length = 0;
    system.matches = true;
    await act(async () => { system.dispatch(); });
    expect.soft(records.length + observer.takeRecords().length).toBe(1);
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("updates every toggle label after either control is clicked", async () => {
    const view = renderToggles();
    const [first, second] = view.getAllByRole("button");
    await act(async () => { fireEvent.click(first); });
    expect.soft(first.getAttribute("aria-label")).toBe("Switch to light mode");
    expect.soft(second.getAttribute("aria-label")).toBe("Switch to light mode");
  });

  it("keeps an explicit choice through later system changes after a failed write", async () => {
    system.matches = true;
    localStorage.setItem("pb-theme", "light");
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(denyStorage);
    const view = renderToggles();
    await act(async () => { fireEvent.click(view.getAllByRole("button")[0]); });
    expect(document.documentElement.dataset.theme).toBe("dark");
    system.matches = false;
    await act(async () => { system.dispatch(); });
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("removes every system listener when the toggles unmount", () => {
    const view = renderToggles();
    expect(system.activeListeners).toBeGreaterThan(0);
    expect(system.adds).toBeGreaterThan(0);
    view.unmount();
    expect(system.activeListeners).toBe(0);
    expect(system.removes).toBe(system.adds);
  });

  it("renders a light toggle when the system preference query throws", () => {
    system.throws = true;
    const view = renderToggles(1);
    expect(view.getByRole("button").getAttribute("aria-label")).toBe("Switch to dark mode");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("renders a stored dark choice when the system listener cannot attach", () => {
    localStorage.setItem("pb-theme", "dark");
    system.throws = true;
    const view = renderToggles(1);
    expect(view.getByRole("button").getAttribute("aria-label")).toBe("Switch to light mode");
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("retains an in-memory choice across remounts when storage and system queries fail", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(denyStorage);
    system.throws = true;
    const first = renderToggles(1);
    expect(first.getByRole("button").getAttribute("aria-label")).toBe("Switch to dark mode");
    await act(async () => { fireEvent.click(first.getByRole("button")); });
    expect(document.documentElement.dataset.theme).toBe("dark");
    first.unmount();
    const remounted = renderToggles(1);
    expect(remounted.getByRole("button").getAttribute("aria-label")).toBe("Switch to light mode");
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("projects the new mode before notifying subscribers", async () => {
    const projected: Array<string | undefined> = [];
    const unsubscribe = theme.subscribeDisplayMode(() => projected.push(document.documentElement.dataset.theme));
    try {
      system.matches = true;
      await act(async () => { system.dispatch(); });
      expect(projected).toEqual(["dark"]);
    } finally {
      unsubscribe();
    }
  });

  it("ignores system events when a stored mode keeps the effective mode unchanged", async () => {
    localStorage.setItem("pb-theme", "dark");
    const records: MutationRecord[] = [];
    observer = new MutationObserver((delivered) => records.push(...delivered));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    const notify = vi.fn();
    const unsubscribe = theme.subscribeDisplayMode(notify);
    try {
      notify.mockClear();
      observer.takeRecords();
      records.length = 0;
      system.matches = true;
      await act(async () => { system.dispatch(); });
      system.matches = false;
      await act(async () => { system.dispatch(); });
      expect.soft(notify).toHaveBeenCalledTimes(0);
      expect.soft(records.length + observer.takeRecords().length).toBe(0);
    } finally {
      unsubscribe();
    }
  });

  it("resynchronizes the first subscriber without calling its callback during subscription", () => {
    expect(theme.getDisplayMode()).toBe("light");
    system.matches = true;
    const notify = vi.fn();
    const unsubscribe = theme.subscribeDisplayMode(notify);
    try {
      expect.soft(notify).toHaveBeenCalledTimes(0);
      expect.soft(theme.getDisplayMode()).toBe("dark");
      expect.soft(document.documentElement.dataset.theme).toBe("dark");
    } finally {
      unsubscribe();
    }
  });

  it("delivers the published notification even if another subscriber unsubscribes it", async () => {
    let unsubscribeNext = () => {};
    const unsubscribeFirst = theme.subscribeDisplayMode(() => unsubscribeNext());
    const next = vi.fn();
    unsubscribeNext = theme.subscribeDisplayMode(next);
    try {
      system.matches = true;
      await act(async () => { system.dispatch(); });
      expect(next).toHaveBeenCalledTimes(1);
    } finally {
      unsubscribeFirst();
      unsubscribeNext();
    }
  });
});
