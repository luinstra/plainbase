import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeSystem } from "./displayModeTestSupport";
import type { DisplayMode } from "../theme";

const html = readFileSync("index.html", "utf8");
const inline = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
  .filter((match) => !/(?:^|\s)src\s*=/i.test(match[1]));
if (inline.length !== 1) throw new Error("Expected exactly one inline bootstrap");
const body = inline[0][2];

const storedValues = ["absent", "light", "dark", "bogus"] as const;
const storageModes = ["readable", "getItem throws", "getter throws"] as const;
const systemModes = ["light", "dark", "throws", "absent"] as const;
const systemFallback = ["light", "dark", "light", "light"] as const;
const oracle: Record<typeof storageModes[number], Record<typeof storedValues[number], readonly DisplayMode[]>> = {
  readable: {
    absent: systemFallback,
    light: ["light", "light", "light", "light"],
    dark: ["dark", "dark", "dark", "dark"],
    bogus: systemFallback,
  },
  "getItem throws": { absent: systemFallback, light: systemFallback, dark: systemFallback, bogus: systemFallback },
  "getter throws": { absent: systemFallback, light: systemFallback, dark: systemFallback, bogus: systemFallback },
};
const cells = storageModes.flatMap((storage) => storedValues.flatMap((stored) =>
  systemModes.map((system, index) => ({ storage, stored, system, expected: oracle[storage][stored][index] }))));

type BootstrapWindow = Window & typeof globalThis & { stored?: unknown; dark?: unknown; __pbScopeControl?: boolean };
let frame: HTMLIFrameElement | undefined;

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
  frame?.contentWindow?.close();
  frame?.remove();
  frame = undefined;
});

function denyStorage(): never {
  throw new DOMException("denied", "SecurityError");
}

describe("display-mode bootstrap parity", () => {
  it.each(cells)("bootstrap and runtime agree with $storage storage, $stored stored, $system system", async ({
    storage, stored, system, expected,
  }) => {
    vi.resetModules();
    frame = document.createElement("iframe");
    document.body.appendChild(frame);
    const view = frame.contentWindow as BootstrapWindow;
    const store = view.localStorage;
    store.clear();
    if (stored !== "absent") store.setItem("pb-theme", stored);
    const preference = fakeSystem();
    preference.matches = system === "dark";
    preference.throws = system === "throws";
    const matchMedia = system === "absent" ? undefined : preference.matchMedia;
    Object.defineProperty(view, "matchMedia", { configurable: true, value: matchMedia });
    vi.stubGlobal("window", view);
    vi.stubGlobal("document", view.document);
    vi.stubGlobal("localStorage", store);
    vi.stubGlobal("matchMedia", matchMedia);
    if (storage === "getItem throws") {
      vi.spyOn(view.Storage.prototype, "getItem").mockImplementation(denyStorage);
    } else if (storage === "getter throws") {
      vi.spyOn(view, "localStorage", "get").mockImplementation(denyStorage);
      Object.defineProperty(globalThis, "localStorage", { configurable: true, get: denyStorage });
    }

    delete view.document.documentElement.dataset.theme;
    new Function(body)();
    const functionMode = view.document.documentElement.dataset.theme ?? "light";

    // Global execution makes a missing bootstrap wrapper observable on the window.
    view.eval("var __pbScopeControl = true;");
    expect(view.__pbScopeControl, "fresh-window global execution").toBe(true);
    delete view.__pbScopeControl;
    delete view.document.documentElement.dataset.theme;
    view.eval(body);
    const globalMode = view.document.documentElement.dataset.theme ?? "light";
    expect.soft([functionMode, globalMode], "bootstrap projection").toEqual([expected, expected]);
    expect.soft(view.stored, "bootstrap stored stays private").toBeUndefined();
    expect.soft(view.dark, "bootstrap dark stays private").toBeUndefined();

    delete view.document.documentElement.dataset.theme;
    const { resolveDisplayMode } = await import("../theme");
    expect.soft(resolveDisplayMode(), "runtime resolution").toBe(expected);
  });
});
