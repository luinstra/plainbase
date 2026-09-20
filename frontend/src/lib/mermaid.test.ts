import { waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

type RenderResult = { svg: string };
type Render = (id: string, source: string, scratch: HTMLDivElement) => Promise<RenderResult>;
type FakeMermaid = {
  initialize: ReturnType<typeof vi.fn>;
  render: ReturnType<typeof vi.fn<Render>>;
};

function fakeMermaid(): FakeMermaid {
  return {
    initialize: vi.fn(),
    render: vi.fn<Render>(async (_id, _source, _scratch) => ({ svg: "<svg><title>diagram</title></svg>" })),
  };
}

async function loadHelper(fake: FakeMermaid, onImport = () => undefined) {
  vi.resetModules();
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
  document.body.innerHTML = "";
  document.documentElement.removeAttribute("data-theme");
  vi.doUnmock("mermaid");
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
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const cleanup = renderMermaidBlocks(container);
    await waitForDiagrams(container, 1);
    const initialDiagram = container.querySelector("[data-pb-mermaid]");

    document.documentElement.dataset.theme = "dark";
    await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(document.body.querySelectorAll("[data-pb-mermaid-scratch]")).toHaveLength(1));
    expect(container.querySelector("[data-pb-mermaid]")).toBe(initialDiagram);
    expect(container.querySelector("pre")?.hidden).toBe(true);

    document.documentElement.setAttribute("data-theme", "dark");
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(fake.render).toHaveBeenCalledTimes(2);

    cleanup();
    expect(document.body.querySelectorAll("[data-pb-mermaid-scratch]")).toHaveLength(0);
    expect(container.querySelectorAll("[data-pb-mermaid], [role=status]")).toHaveLength(0);
    expect(container.querySelector("pre")?.hidden).toBe(false);
    resolveTheme?.({ svg: "<svg><title>stale</title></svg>" });
  });

  it("restores Mermaid source when a theme replacement fails", async () => {
    const fake = fakeMermaid();
    fake.render
      .mockImplementationOnce(async () => ({ svg: "<svg><title>light</title></svg>" }))
      .mockImplementationOnce(async () => {
        throw new Error("theme failed");
      });
    const { renderMermaidBlocks } = await loadHelper(fake);
    const container = diagramContainer(["flowchart LR\nA --> B"]);
    const cleanup = renderMermaidBlocks(container);
    await waitForDiagrams(container, 1);

    document.documentElement.dataset.theme = "dark";
    await waitFor(() => expect(fake.render).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(container.querySelector("pre")?.hidden).toBe(false));

    expect(container.querySelectorAll("[data-pb-mermaid]")).toHaveLength(0);
    expect(container.querySelector("code")?.textContent).toBe("flowchart LR\nA --> B");
    expect(container.querySelector('[role="status"]')).not.toBeNull();
    cleanup();
  });
});
