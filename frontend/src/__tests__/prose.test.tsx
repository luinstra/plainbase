import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { highlightCodeBlocks, Prose } from "../components/Prose";

/**
 * `.pb-prose` stable-selector + presentation-enhancement checks. The HTML is what the
 * server renderer emits: heading ids, rewritten hrefs, and the `data-pb-link-error`
 * broken-link marker (FlexmarkRenderer) — the SPA only decorates. `Prose` now reads the
 * router hash (deep-link hook), so the tests mount it under a minimal memory router.
 */

const serverMarkdown = [
  "# Deploy Guide",
  "",
  "[Kubernetes setup](/docs/infra/kubernetes)",
  "",
  "[missing page](/docs/missing)",
  "",
  "```json",
  '{"key": "value"}',
  "```",
].join("\n");

function sourceRange(markdown: string): string {
  const start = serverMarkdown.indexOf(markdown);
  if (start < 0) throw new Error(`source text not found: ${markdown}`);
  const encoder = new TextEncoder();
  const byteStart = encoder.encode(serverMarkdown.slice(0, start)).length;
  const byteEnd = encoder.encode(serverMarkdown.slice(0, start + markdown.length)).length;
  return `${byteStart}-${byteEnd}`;
}

const serverHtml = [
  `<h1 id="deploy-guide" data-pb-src="${sourceRange("# Deploy Guide")}">Deploy Guide</h1>`,
  `<p data-pb-src="${sourceRange("[Kubernetes setup](/docs/infra/kubernetes)")}">` +
    '<a href="/docs/infra/kubernetes">Kubernetes setup</a></p>',
  `<p data-pb-src="${sourceRange("[missing page](/docs/missing)")}">` +
    '<a data-pb-link-error="broken_missing">missing page</a></p>',
  `<pre data-pb-src="${sourceRange('```json\n{"key": "value"}\n```')}"><code class="language-json">{"key": "value"}</code></pre>`,
].join("\n");

/** Renders `children` inside a minimal memory router so router hooks resolve. */
function renderRouted(children: ReactNode) {
  const rootRoute = createRootRoute({ component: () => children });
  const router = createRouter({ routeTree: rootRoute, history: createMemoryHistory({ initialEntries: ["/"] }) });
  return render(<RouterProvider router={router as never} />);
}

afterEach(cleanup);

describe("Prose", () => {
  it("renders server html under the stable .pb-prose selector", async () => {
    const { container } = renderRouted(<Prose html={serverHtml} />);
    await waitFor(() => expect(container.querySelector("article.pb-prose")).not.toBeNull());
    const article = container.querySelector("article.pb-prose")!;
    expect(article.hasAttribute("data-pb-prose")).toBe(true);
    expect(article.querySelector("p[data-pb-src]")?.getAttribute("data-pb-src")).toBe(
      sourceRange("[Kubernetes setup](/docs/infra/kubernetes)"),
    );
    // The server's broken-link marker passes through untouched — CSS styles it.
    expect(container.querySelector('[data-pb-link-error="broken_missing"]')).not.toBeNull();
  });

  it("injects heading anchor links on server-emitted ids", async () => {
    const { container } = renderRouted(<Prose html={serverHtml} />);
    await waitFor(() => expect(container.querySelector("h1 a.pb-heading-anchor")).not.toBeNull());
    const heading = container.querySelector("h1")!;
    expect(heading.getAttribute("data-pb-src")).toBe(sourceRange("# Deploy Guide"));
    expect(heading.querySelector("a.pb-heading-anchor")!.getAttribute("href")).toBe("#deploy-guide");
  });

  it("highlights fenced code blocks via highlight.js", async () => {
    const { container } = renderRouted(<Prose html={serverHtml} />);
    await waitFor(() => expect(container.querySelector("pre code.hljs")).not.toBeNull());
    const code = container.querySelector("pre code")!;
    expect(code.classList.contains("hljs")).toBe(true);
    expect(code.querySelectorAll("span[class^='hljs-']").length).toBeGreaterThan(0);
    expect(code.parentElement?.getAttribute("data-pb-src")).toBe(sourceRange('```json\n{"key": "value"}\n```'));
  });

  it("leaves canonical Mermaid fences untouched for the async renderer", () => {
    const container = document.createElement("article");
    const pre = document.createElement("pre");
    const code = document.createElement("code");
    code.className = "language-mermaid";
    code.textContent = "flowchart LR\nA --> B";
    pre.append(code);
    container.append(pre);

    highlightCodeBlocks(container);

    expect(code.classList.contains("hljs")).toBe(false);
    expect(code.textContent).toBe("flowchart LR\nA --> B");
  });
});
