type MermaidInstance = typeof import("mermaid").default;

const MAX_SOURCE_LENGTH = 50000;
const MAX_EDGES = 500;

const SECURE_CONFIG_KEYS = [
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
];

let mermaidImport: Promise<MermaidInstance> | undefined;
let renderSequence = 0;
let renderQueue: Promise<void> = Promise.resolve();

function loadMermaid(): Promise<MermaidInstance> {
  if (mermaidImport) return mermaidImport;
  const loading = import("mermaid").then(({ default: mermaid }) => mermaid);
  mermaidImport = loading;
  void loading.catch(() => {
    if (mermaidImport === loading) mermaidImport = undefined;
  });
  return loading;
}

function enqueueRender(job: () => Promise<void>): Promise<void> {
  const queued = renderQueue.then(job, job);
  renderQueue = queued.then(
    () => undefined,
    () => undefined,
  );
  return queued;
}

function findMermaidBlocks(container: HTMLElement): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>("pre > code.language-mermaid")]
    .map((code) => code.parentElement)
    .filter((pre): pre is HTMLElement => pre !== null);
}

function resolveColorToken(token: string): string {
  const probe = document.createElement("span");
  probe.style.position = "absolute";
  probe.style.visibility = "hidden";
  probe.style.backgroundColor = `var(${token})`;
  document.body.appendChild(probe);
  const color = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return normalizeComputedColor(color);
}

function normalizeComputedColor(color: string): string {
  const match = color.match(/^color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s+\/\s+([\d.]+))?\)$/);
  if (!match) return color;
  const channels = match.slice(1, 4).map((channel) => Math.round(Number(channel) * 255));
  const alpha = match[4];
  return alpha === undefined ? `rgb(${channels.join(", ")})` : `rgba(${channels.join(", ")}, ${alpha})`;
}

function themeConfig(): Parameters<MermaidInstance["initialize"]>[0] {
  const fontFamily =
    getComputedStyle(document.body).fontFamily || "IBM Plex Sans, ui-sans-serif, system-ui, sans-serif";
  const fontSize = Number.parseFloat(getComputedStyle(document.body).fontSize) || 16;
  const background = resolveColorToken("--pb-surface");
  const raised = resolveColorToken("--pb-surface-raised");
  const border = resolveColorToken("--pb-border");
  const strongBorder = resolveColorToken("--pb-border-strong");
  const accent = resolveColorToken("--pb-accent");
  const text = resolveColorToken("--pb-text");

  return {
    secure: SECURE_CONFIG_KEYS,
    securityLevel: "strict",
    startOnLoad: false,
    maxTextSize: MAX_SOURCE_LENGTH,
    suppressErrorRendering: true,
    maxEdges: MAX_EDGES,
    theme: "base",
    themeCSS: "",
    themeVariables: {
      background,
      primaryColor: raised,
      primaryTextColor: text,
      primaryBorderColor: accent,
      lineColor: accent,
      secondaryColor: background,
      secondaryTextColor: text,
      secondaryBorderColor: strongBorder,
      tertiaryColor: raised,
      tertiaryTextColor: text,
      tertiaryBorderColor: border,
      mainBkg: background,
      nodeBorder: border,
      clusterBkg: background,
      clusterBorder: strongBorder,
      textColor: text,
      titleColor: text,
      edgeLabelBackground: background,
      nodeTextColor: text,
      fontFamily,
      fontSize: `${fontSize}px`,
    },
    fontFamily,
    altFontFamily: fontFamily,
    fontSize,
    htmlLabels: false,
  };
}

function clearBlock(pre: HTMLElement, rendered: Map<HTMLElement, HTMLElement>, failures: Map<HTMLElement, HTMLElement>): void {
  rendered.get(pre)?.remove();
  failures.get(pre)?.remove();
  rendered.delete(pre);
  failures.delete(pre);
  pre.hidden = false;
}

function showFailure(
  pre: HTMLElement,
  rendered: Map<HTMLElement, HTMLElement>,
  failures: Map<HTMLElement, HTMLElement>,
): void {
  rendered.get(pre)?.remove();
  rendered.delete(pre);
  pre.hidden = false;
  if (failures.has(pre)) return;
  const notice = document.createElement("p");
  notice.className = "pb-mermaid-error";
  notice.setAttribute("role", "status");
  notice.textContent = "This diagram could not be rendered; the source is shown above.";
  pre.insertAdjacentElement("afterend", notice);
  failures.set(pre, notice);
}

function renderBlock(
  pre: HTMLElement,
  isCurrent: () => boolean,
  rendered: Map<HTMLElement, HTMLElement>,
  failures: Map<HTMLElement, HTMLElement>,
  scratches: Set<HTMLElement>,
): Promise<void> {
  let scratch: HTMLDivElement | undefined;
  return (async () => {
    if (!isCurrent()) return;
    const source = pre.querySelector<HTMLElement>("code.language-mermaid")?.textContent ?? "";
    if (source.length > MAX_SOURCE_LENGTH) {
      showFailure(pre, rendered, failures);
      return;
    }
    try {
      const mermaid = await loadMermaid();
      if (!isCurrent()) return;

      scratch = document.createElement("div");
      scratch.dataset.pbMermaidScratch = "true";
      scratch.style.position = "absolute";
      scratch.style.left = "-100000px";
      scratch.style.top = "0";
      scratch.style.width = "1024px";
      scratch.style.visibility = "hidden";
      document.body.appendChild(scratch);
      scratches.add(scratch);

      mermaid.initialize(themeConfig());
      if (!isCurrent()) return;
      const { svg } = await mermaid.render(`pb-mermaid-${++renderSequence}`, source, scratch);
      if (!isCurrent()) return;

      const diagram = document.createElement("div");
      diagram.className = "pb-mermaid";
      diagram.tabIndex = 0;
      diagram.dataset.pbMermaid = "true";
      diagram.dataset.pbMermaidTheme = document.documentElement.dataset.theme === "dark" ? "dark" : "light";
      diagram.innerHTML = svg;
      diagram.setAttribute("role", "group");
      diagram.setAttribute("aria-label", diagram.querySelector("svg > title")?.textContent?.trim() || "Mermaid diagram");
      const previous = rendered.get(pre);
      pre.hidden = true;
      if (previous) previous.replaceWith(diagram);
      else pre.insertAdjacentElement("afterend", diagram);
      rendered.set(pre, diagram);
      failures.get(pre)?.remove();
      failures.delete(pre);
    } catch {
      if (isCurrent()) showFailure(pre, rendered, failures);
    } finally {
      scratch?.remove();
      if (scratch) scratches.delete(scratch);
    }
  })().catch(() => undefined);
}

/** Enhances canonical Mermaid fences and returns the lifecycle cleanup for the owning Prose effect. */
export function renderMermaidBlocks(container: HTMLElement): () => void {
  let cancelled = false;
  let generation = 0;
  let blocks: HTMLElement[] = [];
  const rendered = new Map<HTMLElement, HTMLElement>();
  const failures = new Map<HTMLElement, HTMLElement>();
  const scratches = new Set<HTMLElement>();
  let currentTheme = document.documentElement.dataset.theme === "dark" ? "dark" : "light";
  const observer =
    typeof MutationObserver === "undefined"
      ? undefined
      : new MutationObserver(() => {
          const nextTheme = document.documentElement.dataset.theme === "dark" ? "dark" : "light";
          if (nextTheme === currentTheme) return;
          currentTheme = nextTheme;
          rerender();
        });

  function current(generationAtSchedule: number, pre: HTMLElement): boolean {
    return !cancelled && generation === generationAtSchedule && container.isConnected && container.contains(pre);
  }

  function clearAll(): void {
    blocks.forEach((pre) => clearBlock(pre, rendered, failures));
    rendered.clear();
    failures.clear();
    scratches.forEach((scratch) => scratch.remove());
    scratches.clear();
  }

  function rerender(): void {
    if (cancelled) return;
    generation += 1;
    failures.forEach((notice) => notice.remove());
    failures.clear();
    blocks = findMermaidBlocks(container);
    if (blocks.length === 0) {
      observer?.disconnect();
      return;
    }
    observer?.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    const generationAtSchedule = generation;
    blocks.forEach((pre) => {
      void enqueueRender(() =>
        renderBlock(pre, () => current(generationAtSchedule, pre), rendered, failures, scratches),
      );
    });
  }

  rerender();
  return () => {
    cancelled = true;
    generation += 1;
    observer?.disconnect();
    clearAll();
  };
}
