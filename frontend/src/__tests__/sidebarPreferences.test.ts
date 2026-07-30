import { afterEach, describe, expect, it, vi } from "vitest";
import { readSidebarPreferences, writeSidebarPreferences } from "../lib/sidebarPreferences";

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe("sidebar preferences", () => {
  it("round-trips the selected root and independent folder paths", () => {
    writeSidebarPreferences({
      selectedRoot: "extra",
      openFolders: {
        docs: ["guides"],
        extra: ["notes", "notes/archive"],
      },
    });

    expect(readSidebarPreferences()).toEqual({
      selectedRoot: "extra",
      openFolders: {
        docs: ["guides"],
        extra: ["notes", "notes/archive"],
      },
    });
  });

  it("discards a stored value with the wrong shape", () => {
    sessionStorage.setItem("pb-sidebar-state-v1", JSON.stringify({ selectedRoot: 42, openFolders: { docs: "guides" } }));

    expect(readSidebarPreferences()).toEqual({ selectedRoot: null, openFolders: {} });
  });

  it("keeps preferences in memory when session storage is denied", async () => {
    const denied = () => {
      throw new DOMException("denied", "SecurityError");
    };
    vi.stubGlobal("sessionStorage", { getItem: denied, setItem: denied } as unknown as Storage);
    vi.resetModules();

    const preferences = await import("../lib/sidebarPreferences");
    const value = { selectedRoot: "extra", openFolders: { extra: ["notes"] } };
    preferences.writeSidebarPreferences(value);

    expect(preferences.readSidebarPreferences()).toEqual(value);
  });
});
