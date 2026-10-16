import { readFileSync } from "node:fs";
import { waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeSystem } from "../__tests__/displayModeTestSupport";

type RenderResult = { svg: string };
type Render = (id: string, source: string, scratch: HTMLDivElement) => Promise<RenderResult>;
type FakeMermaid = {
  initialize: ReturnType<typeof vi.fn>;
  render: ReturnType<typeof vi.fn<Render>>;
};

const mapping = JSON.parse(readFileSync("src/__tests__/fixtures/theme/mermaid-mapping.json", "utf8")) as {
  variables: Record<string, string>;
  shadow: string;
};

function fakeMermaid(): FakeMermaid {
  return {
    initialize: vi.fn(),
    render: vi.fn<Render>(async (_id, _source, _scratch) => ({ svg: "<svg><title>diagram</title></svg>" })),
  };
}

function deferredRender() {
  let resolve!: (result: RenderResult) => void;
  const promise = new Promise<RenderResult>((done) => { resolve = done; });
  return { promise, resolve };
}

function observeTitles(container: HTMLElement) {
  const titles: string[] = [];
  const observer = new MutationObserver((records) => {
    for (const record of records) {
      for (const node of record.addedNodes) {
        if (node instanceof Element) {
          for (const title of node.querySelectorAll("svg > title")) titles.push(title.textContent ?? "");
        }
      }
    }
  });
  observer.observe(container, { childList: true, subtree: true });
  return { titles, disconnect: () => observer.disconnect() };
}

async function loadHelper(fake: FakeMermaid, onImport = () => undefined, convert = (color: string) => color) {
  vi.resetModules();
  vi.doMock("./mermaidColor", () => ({ toMermaidColor: convert }));
  vi.doMock("mermaid", () => {
    onImport();
    return { default: fake };
  });
  return import("./mermaid");
}

function diagramContainer(sources: string[]): HTMLElement {
  const container = document.createElement("article");
  sources.forEach((source) => {
    const pre = document.createElement("pre");
    const code = document.createElement("code");
    code.className = "language-mermaid";
    code.textContent = source;
    pre.append(code);
    container.append(pre);
  });
  document.body.append(container);
  return container;
}

async function waitForDiagrams(container: HTMLElement, count: number): Promise<void> {
  await waitFor(() => expect(container.querySelectorAll("[data-pb-mermaid]")).toHaveLength(count));
}

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
  document.body.innerHTML = "";
  document.documentElement.removeAttribute("data-theme");
  vi.doUnmock("mermaid");
  vi.doUnmock("./mermaidColor");
  vi.resetModules();
});

describe("renderMermaidBlocks", () => {
  it("does not import Mermaid for ordinary prose", async () => {
    let imported = false;
    const fake = fakeMermaid();
    const { renderMermaidBlocks } = await loadHelper(fake, () => {
      imported = true;
    });
    const container = document.createElement("article");
    container.innerHTML = '<pre><code class="language-json">{"ok":true}</code></pre>';
    document.body.append(container);

    const cleanup = renderMermaidBlocks(container);
    await Promise.resolve();

    expect(imported).toBe(false);
    expect(fake.render).not.toHaveBeenCalled();
    cleanup();
  });

  it("serializes multiple diagrams, preserves source, and locks Mermaid configuration", async () => {
    const fake = fakeMermaid();
    const { renderMermaidBlocks } = await loadHelper(fake);
    const container = diagramContainer(["flowchart LR\nA --> B", "sequenceDiagram\nAlice->>Bob: hi"]);

    const cleanup = renderMermaidBlocks(container);
    await waitForDiagrams(container, 2);

    expect(container.querySelectorAll("pre code.language-mermaid")).toHaveLength(2);
    expect(container.querySelectorAll("pre[hidden]")).toHaveLength(2);
    expect(container.querySelectorAll("[data-pb-mermaid-scratch]")).toHaveLength(0);
    expect(container.querySelector("[data-pb-mermaid]")?.getAttribute("tabindex")).toBe("0");
    expect(fake.render).toHaveBeenCalledTimes(2);
    expect(fake.initialize).toHaveBeenCalledTimes(2);
    expect(fake.initialize.mock.calls[0]?.[0]).toMatchObject({
      securityLevel: "strict",
      startOnLoad: false,
      maxTextSize: 50000,
      maxEdges: 500,
      htmlLabels: false,
      suppressErrorRendering: true,
      theme: "base",
    });
    expect(fake.initialize.mock.calls[0]?.[0].themeVariables.fontSize).toBe("16px");
    expect(fake.initialize.mock.calls[0]?.[0].secure).toEqual([
      "secure",
      "securityLevel",
      "startOnLoad",
      "maxTextSize",
      "suppressErrorRendering",
      "maxEdges",
      "theme",
      "themeCSS",
      "themeVariables",
      "fontFamily",
      "altFontFamily",
      "fontSize",
      "htmlLabels",
      "dompurifyConfig",
    ]);
    cleanup();
  });

  it("keeps a failed block readable while a later sibling renders", async () => {
    const fake = fakeMermaid();
    fake.render.mockImplementation(async (_id: string, source: string) => {
      if (source === "bad") throw new Error("invalid");
      return { svg: "<svg><title>valid</title></svg>" };
    });
    const { renderMermaidBlocks } = await loadHelper(fake);
    const container = diagramContainer(["bad", "flowchart LR\nA --> B"]);

    const cleanup = renderMermaidBlocks(container);
    await waitForDiagrams(container, 1);
    await waitFor(() => expect(container.querySelectorAll('[role="status"]')).toHaveLength(1));

    expect(container.querySelector("pre")?.hidden).toBe(false);
    expect(container.querySelector("pre code")?.textContent).toBe("bad");
    expect(container.querySelector('[role="status"]')?.textContent).toContain("source is shown");
    cleanup();
  });

  it("retries a failed dynamic import on a later document", async () => {
    const fake = fakeMermaid();
    let attempts = 0;
    vi.resetModules();
    vi.doMock("./mermaidColor", () => ({ toMermaidColor: (color: string) => color }));
    vi.doMock("mermaid", () => {
      attempts += 1;
      if (attempts === 1) throw new Error("load failed");
      return { default: fake };
    });
    const { renderMermaidBlocks } = await import("./mermaid");
    const failedContainer = diagramContainer(["flowchart LR\nA --> B"]);

    const failedCleanup = renderMermaidBlocks(failedContainer);
    await waitFor(() => expect(failedContainer.querySelector('[role="status"]')).not.toBeNull());

    expect(failedContainer.querySelector("pre")?.hidden).toBe(false);
    failedCleanup();

    const retryContainer = diagramContainer(["flowchart LR\nA --> B"]);
    const retryCleanup = renderMermaidBlocks(retryContainer);
    await waitForDiagrams(retryContainer, 1);

    expect(attempts).toBe(2);
    retryCleanup();
  });

  it("keeps oversized source visible without calling Mermaid", async () => {
    const fake = fakeMermaid();
    const { renderMermaidBlocks } = await loadHelper(fake);
    const source = "x".repeat(50001);
    const container = diagramContainer([source]);

    const cleanup = renderMermaidBlocks(container);
    await waitFor(() => expect(container.querySelector('[role="status"]')).not.toBeNull());

    expect(container.querySelector("pre")?.hidden).toBe(false);
    expect(container.querySelector("code")?.textContent).toBe(source);
    expect(fake.render).not.toHaveBeenCalled();
    cleanup();
  });

  it("does not commit a stale render after HTML replacement", async () => {
    const fake = fakeMermaid();
    let resolveFirst: ((result: RenderResult) => void) | undefined;
    fake.render.mockImplementationOnce(
      () => new Promise<RenderResult>((resolve) => {
        resolveFirst = resolve;
      }),
    );
    const { renderMermaidBlocks } = await loadHelper(fake);
    const container = diagramContainer(["old"]);
    const oldCleanup = renderMermaidBlocks(container);
    await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(1));

    container.innerHTML = "";
    const replacement = document.createElement("pre");
    const replacementCode = document.createElement("code");
    replacementCode.className = "language-mermaid";
    replacementCode.textContent = "new";
    replacement.append(replacementCode);
    container.append(replacement);
    oldCleanup();
    const newCleanup = renderMermaidBlocks(container);
    resolveFirst?.({ svg: "<svg><title>old</title></svg>" });
    await waitForDiagrams(container, 1);

    expect(container.textContent).toContain("new");
    expect(container.textContent).not.toContain("old");
    expect(container.querySelector("svg title")?.textContent).toBe("diagram");
    newCleanup();
  });

  it("keeps the successful diagram until a theme replacement is ready and cleans pending scratch on unmount", async () => {
    const system = fakeSystem();
    vi.stubGlobal("matchMedia", system.matchMedia);
    const fake = fakeMermaid();
    let resolveTheme: ((result: RenderResult) => void) | undefined;
    fake.render
      .mockImplementationOnce(async () => ({ svg: "<svg><title>light</title></svg>" }))
      .mockImplementationOnce(
        () => new Promise<RenderResult>((resolve) => {
          resolveTheme = resolve;
        }),
      );
    const { renderMermaidBlocks } = await loadHelper(fake);
    const { toggleDisplayMode } = await import("../theme");
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const cleanup = renderMermaidBlocks(container);
    await waitForDiagrams(container, 1);
    const initialDiagram = container.querySelector("[data-pb-mermaid]");

    toggleDisplayMode();
    await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(document.body.querySelectorAll("[data-pb-mermaid-scratch]")).toHaveLength(1));
    expect(container.querySelector("[data-pb-mermaid]")).toBe(initialDiagram);
    expect(container.querySelector("pre")?.hidden).toBe(true);

    cleanup();
    expect(document.body.querySelectorAll("[data-pb-mermaid-scratch]")).toHaveLength(0);
    expect(container.querySelectorAll("[data-pb-mermaid], [role=status]")).toHaveLength(0);
    expect(container.querySelector("pre")?.hidden).toBe(false);
    resolveTheme?.({ svg: "<svg><title>stale</title></svg>" });
  });

  it("restores Mermaid source when a theme replacement fails", async () => {
    const system = fakeSystem();
    vi.stubGlobal("matchMedia", system.matchMedia);
    const fake = fakeMermaid();
    fake.render
      .mockImplementationOnce(async () => ({ svg: "<svg><title>light</title></svg>" }))
      .mockImplementationOnce(async () => {
        throw new Error("theme failed");
      });
    const { renderMermaidBlocks } = await loadHelper(fake);
    const { toggleDisplayMode } = await import("../theme");
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const cleanup = renderMermaidBlocks(container);
    await waitForDiagrams(container, 1);

    toggleDisplayMode();
    await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(container.querySelector("pre")?.hidden).toBe(false));

    expect(container.querySelectorAll("[data-pb-mermaid]")).toHaveLength(0);
    expect(container.querySelector("code")?.textContent).toBe("flowchart LR\nA --> B");
    expect(container.querySelector('[role="status"]')).not.toBeNull();
    cleanup();
  });

  it("never inserts an obsolete theme render while a newer render is queued", async () => {
    const system = fakeSystem();
    vi.stubGlobal("matchMedia", system.matchMedia);
    const pending = deferredRender();
    const latest = deferredRender();
    const fake = fakeMermaid();
    fake.render
      .mockResolvedValueOnce({ svg: "<svg><title>A</title></svg>" })
      .mockImplementationOnce(() => pending.promise)
      .mockImplementationOnce(() => latest.promise);
    const { renderMermaidBlocks } = await loadHelper(fake);
    const { toggleDisplayMode } = await import("../theme");
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const log = observeTitles(container);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitFor(() => expect(log.titles).toEqual(["A"]));
      toggleDisplayMode();
      await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(2));
      toggleDisplayMode();
      await waitFor(() => expect(document.documentElement.dataset.theme).toBeUndefined());

      pending.resolve({ svg: "<svg><title>B</title></svg>" });
      await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(3));
      expect(log.titles).toEqual(["A"]);
      latest.resolve({ svg: "<svg><title>C</title></svg>" });
      await waitFor(() => expect(log.titles).toEqual(["A", "C"]));

      expect(log.titles).not.toContain("B");
      expect(container.querySelector("svg > title")?.textContent).toBe("C");
    } finally {
      cleanup();
      log.disconnect();
    }
  });

  it("inserts a completed replacement after one display mode change", async () => {
    const system = fakeSystem();
    vi.stubGlobal("matchMedia", system.matchMedia);
    const pending = deferredRender();
    const fake = fakeMermaid();
    fake.render
      .mockResolvedValueOnce({ svg: "<svg><title>A</title></svg>" })
      .mockImplementationOnce(() => pending.promise);
    const { renderMermaidBlocks } = await loadHelper(fake);
    const { toggleDisplayMode } = await import("../theme");
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const log = observeTitles(container);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitFor(() => expect(log.titles).toEqual(["A"]));
      toggleDisplayMode();
      await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(2));
      pending.resolve({ svg: "<svg><title>B</title></svg>" });

      await waitFor(() => expect(log.titles).toEqual(["A", "B"]));
      expect(container.querySelector("svg > title")?.textContent).toBe("B");
    } finally {
      cleanup();
      log.disconnect();
    }
  });

  it("skips an obsolete queued block before rendering it in the current display mode", async () => {
    const system = fakeSystem();
    vi.stubGlobal("matchMedia", system.matchMedia);
    const oldFirst = deferredRender();
    const currentFirst = deferredRender();
    const fake = fakeMermaid();
    fake.render
      .mockImplementationOnce(() => oldFirst.promise)
      .mockImplementationOnce(() => currentFirst.promise)
      .mockResolvedValueOnce({ svg: "<svg><title>second</title></svg>" });
    const { renderMermaidBlocks } = await loadHelper(fake);
    const { toggleDisplayMode } = await import("../theme");
    const firstSource = "flowchart LR\nA --> B";
    const secondSource = "sequenceDiagram\nAlice->>Bob: hi";
    const container = diagramContainer([firstSource, secondSource]);
    const log = observeTitles(container);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(1));
      toggleDisplayMode();
      await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
      oldFirst.resolve({ svg: "<svg><title>obsolete</title></svg>" });
      await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(2));
      currentFirst.resolve({ svg: "<svg><title>first</title></svg>" });
      await waitForDiagrams(container, 2);
      await waitFor(() => expect(log.titles).toEqual(["first", "second"]));

      expect(fake.render.mock.calls.filter(([, source]) => source === secondSource)).toHaveLength(1);
      expect(fake.render.mock.calls.filter(([, source]) => source === firstSource)).toHaveLength(2);
      expect(fake.render).toHaveBeenCalledTimes(3);
      expect(container.querySelectorAll('[data-pb-mermaid-theme="dark"]')).toHaveLength(2);
    } finally {
      cleanup();
      log.disconnect();
    }
  });

  it("listens to display mode while diagrams are mounted and stops on cleanup", async () => {
    const system = fakeSystem();
    vi.stubGlobal("matchMedia", system.matchMedia);
    const pending = deferredRender();
    const fake = fakeMermaid();
    fake.render.mockImplementationOnce(() => pending.promise);
    const { renderMermaidBlocks } = await loadHelper(fake);
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const log = observeTitles(container);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(1));
      pending.resolve({ svg: "<svg><title>diagram</title></svg>" });
      await waitFor(() => expect(log.titles).toEqual(["diagram"]));

      expect.soft(system.activeListeners, "mounted system listeners").toBe(1);
      cleanup();
      expect.soft(system.activeListeners, "system listeners after cleanup").toBe(0);
    } finally {
      cleanup();
      log.disconnect();
    }
  });

  it("stops listening to display mode when a rerender finds no diagram blocks", async () => {
    const system = fakeSystem();
    vi.stubGlobal("matchMedia", system.matchMedia);
    const fake = fakeMermaid();
    const { renderMermaidBlocks } = await loadHelper(fake);
    const { toggleDisplayMode } = await import("../theme");
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitForDiagrams(container, 1);
      expect.soft(system.activeListeners, "mounted system listeners").toBe(1);

      container.replaceChildren();
      toggleDisplayMode();

      expect.soft(system.activeListeners, "system listeners when no blocks remain").toBe(0);
      expect(fake.render).toHaveBeenCalledTimes(1);
      cleanup();
      expect.soft(system.activeListeners, "system listeners after cleanup").toBe(0);
    } finally {
      cleanup();
    }
  });

  it("removes the sampling probe and scratch when conversion fails and keeps the source readable", async () => {
    const fake = fakeMermaid();
    const convert = vi.fn((color: string) => {
      if (convert.mock.calls.length === 3) throw new Error("conversion failed");
      return color;
    });
    const { renderMermaidBlocks } = await loadHelper(fake, undefined, convert);
    const source = "flowchart LR\nA --> B";
    const container = diagramContainer([source]);
    const log = observeTitles(container);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitFor(() => expect(container.querySelector('[role="status"]')).not.toBeNull());

      expect.soft(document.querySelectorAll("[data-pb-mermaid-probe]")).toHaveLength(0);
      expect.soft(document.querySelectorAll("[data-pb-mermaid-scratch]")).toHaveLength(0);
      expect.soft(container.querySelector("pre")?.hidden).toBe(false);
      expect.soft(container.querySelector("code")?.textContent).toBe(source);
      expect.soft(container.querySelector('[role="status"]')?.textContent).toContain("source is shown");
      expect(convert).toHaveBeenCalledTimes(3);
      expect(fake.render).not.toHaveBeenCalled();
      expect(log.titles).toEqual([]);
    } finally {
      cleanup();
      log.disconnect();
    }
  });

  it("reuses one sampling probe for every token and removes it after conversion succeeds", async () => {
    const probes: Element[][] = [];
    const convert = vi.fn((color: string) => {
      probes.push([...document.querySelectorAll("[data-pb-mermaid-probe]")]);
      return color;
    });
    const pending = deferredRender();
    const fake = fakeMermaid();
    fake.render.mockImplementationOnce(() => pending.promise);
    const { renderMermaidBlocks } = await loadHelper(fake, undefined, convert);
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const log = observeTitles(container);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(1));
      pending.resolve({ svg: "<svg><title>diagram</title></svg>" });
      await waitFor(() => expect(log.titles).toEqual(["diagram"]));

      expect(convert).toHaveBeenCalledTimes(10);
      expect(probes).toHaveLength(10);
      expect(probes.every((sample) => sample.length === 1)).toBe(true);
      expect(new Set(probes.flat()).size).toBe(1);
      expect(document.querySelectorAll("[data-pb-mermaid-probe]")).toHaveLength(0);
    } finally {
      cleanup();
      log.disconnect();
    }
  });

  it("maps every Mermaid color to its theme role", async () => {
    const fake = fakeMermaid();
    const { renderMermaidBlocks } = await loadHelper(fake);
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const cleanup = renderMermaidBlocks(container);
    try {
      await waitForDiagrams(container, 1);
      const variables = fake.initialize.mock.calls[0]?.[0].themeVariables;
      const nonColors = ["darkMode", "dropShadow", "useGradient", "fontFamily", "fontSize"];
      const colorKeys = Object.keys(variables).filter((key) => !nonColors.includes(key));
      expect.soft(colorKeys.sort(), "color variable keys").toEqual(Object.keys(mapping.variables).sort());
      for (const [key, role] of Object.entries(mapping.variables)) {
        expect.soft(variables[key], key).toBe(["var(", role, ")"].join(""));
      }
      expect.soft(variables.dropShadow, "dropShadow").toContain(["var(", mapping.shadow, ")"].join(""));
    } finally {
      cleanup();
    }
  });
});
