import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
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
  it("retains presentation controls when a refreshed title changes fragment placement", async () => {
    function Fixture() {
      const [title, setTitle] = useState("Other title");
      return <><button onClick={() => setTitle("Section")}>Refresh title</button><Prose title={title}
        metadata={<div data-pb-selection-chrome>Properties</div>} html={`<h2 id="section">Section</h2><p><code>${"a".repeat(80)}</code></p>`}
        reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: true }} /></>;
    }
    const { container } = renderRouted(<Fixture />);
    await screen.findByRole("button", { name: "Copy code" });
    fireEvent.click(screen.getByRole("button", { name: "Show full code" }));
    fireEvent.click(screen.getByRole("button", { name: "Refresh title" }));
    await waitFor(() => expect(container.querySelector(".pb-title-prose h2 .pb-heading-anchor")).not.toBeNull());
    expect(screen.getAllByRole("button", { name: "Copy code" })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Show full code" }).getAttribute("aria-expanded")).toBe("false");
  });
  it("uses a matching leading secondary heading once, preserving its source tag and range", async () => {
    const { container, unmount } = renderRouted(<Prose title="what's up" metadata={<div data-pb-selection-chrome>Properties</div>}
      html={'<h2 id="whats-up" data-pb-src="0-12">What\'s up</h2><p>Body</p>'} />);
    await waitFor(() => expect(container.querySelector("#whats-up")).not.toBeNull());
    expect(container.querySelectorAll("h1, h2")).toHaveLength(1);
    expect(container.querySelector(".pb-title-prose h2")?.getAttribute("data-pb-src")).toBe("0-12");
    expect(container.querySelector("[data-pb-prose] h2")).toBeNull();
    unmount();
    const other = renderRouted(<Prose title="A different title" metadata={<div data-pb-selection-chrome>Properties</div>}
      html={'<h2 id="section" data-pb-src="0-12">Section</h2><p>Body</p>'} />);
    await waitFor(() => expect(other.container.querySelector("h1")?.textContent).toBe("A different title"));
    expect(other.container.querySelector("[data-pb-prose] h2")?.firstChild?.textContent).toBe("Section");
    expect(other.container.querySelector("[data-pb-prose] h2")?.getAttribute("data-pb-src")).toBe("0-12");
  });
  it("preserves leading title markup and later authored headings around external metadata", async () => {
    const { container, unmount } = renderRouted(<Prose title="Fallback" metadata={<div data-pb-selection-chrome>Properties</div>}
      html={'<h1 id="title" data-pb-src="0-20">A <em>title</em></h1><p data-pb-src="21-30">Body</p>'} />);
    await waitFor(() => expect(container.querySelector("h1 em")?.textContent).toBe("title"));
    expect(container.querySelectorAll("#title")).toHaveLength(1);
    expect(container.querySelector("h1")?.getAttribute("data-pb-src")).toBe("0-20");
    expect(container.querySelector("[data-pb-selection-chrome]")?.closest(".pb-prose")).toBeNull();
    unmount();
    const later = renderRouted(<Prose title="Fallback" metadata={<div data-pb-selection-chrome>Properties</div>}
      html={'<p>Lead</p><h1 id="later">Later heading</h1><p>End</p>'} />);
    await waitFor(() => expect(later.container.querySelector("[data-pb-prose]")?.textContent).toContain("LeadLater heading"));
    expect(later.container.querySelector("h1")?.textContent).toBe("Fallback");
    expect(later.container.querySelector("h1")?.hasAttribute("data-pb-src")).toBe(false);
  });
  it("explains an inert missing link and offers safe current-root actions", async () => {
    const { container } = renderRouted(<Prose html={'<p data-pb-src="0-30"><a data-pb-link-error="broken_missing" data-pb-link-target="../missing.md">Missing</a></p>'}
      reading={{ root: "extra", path: "guides/source.md", url: "/extra/guides/source", editable: true }} />);
    const trigger = await screen.findByRole("button", { name: "Missing" });
    expect(trigger.hasAttribute("href")).toBe(false);
    fireEvent.focusIn(trigger);
    expect(await screen.findByText("This page doesn't exist")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Create page" }).getAttribute("href")).toBe("/new?root=extra&folder=guides");
    expect(screen.getByRole("link", { name: "Edit link" }).getAttribute("href")).toBe("/extra/guides/source?mode=edit");
    expect(container.querySelector(".pb-prose [role=dialog]")).toBeNull();
    fireEvent.keyDown(trigger, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("copies an entire long inline token without adding source text or decorating fenced code", async () => {
    const token = "0123456789abcdef".repeat(5);
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    const { container } = renderRouted(<Prose html={`<p data-pb-src="0-90"><code>${token}</code></p><pre><code>${token}</code></pre>`}
      reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: true }} />);
    const copy = await screen.findByRole("button", { name: "Copy code" });
    fireEvent.click(copy);
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(token));
    expect(container.querySelector("p")!.textContent).toBe(token);
    expect(container.querySelector("pre button")).toBeNull();
    expect(copy.hasAttribute("data-pb-selection-chrome")).toBe(true);
  });
  it.each([
    ["broken_missing", "source.kt", "Target not found"],
    ["broken_missing", "image.png", "Target not found"],
    ["broken_missing", "directory", "Target not found"],
    ["broken_missing", "broken%GG.md", "Target not found"],
    ["outside_content_root", "../../other.md", "Outside this space"],
  ])("does not offer page creation for %s target %s", async (reason, target, title) => {
    renderRouted(<Prose html={`<p><a data-pb-link-error="${reason}" data-pb-link-target="${target}">Target</a></p>`}
      reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: true }} />);
    fireEvent.focusIn(await screen.findByRole("button", { name: "Target" }));
    expect(await screen.findByRole("dialog", { name: title })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Create page" })).toBeNull();
    expect(screen.getByRole("link", { name: "Edit link" }).getAttribute("href")).toBe("/docs/source?mode=edit");
  });
  it("preserves linked inline code without nesting a copy button inside the link", async () => {
    const token = "abcdef0123456789".repeat(5);
    const { container } = renderRouted(<Prose html={`<p><a href="/docs/target"><code>${token}</code></a></p>`}
      reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: true }} />);
    const link = await screen.findByRole("link", { name: token });
    expect(link.getAttribute("href")).toBe("/docs/target");
    expect(link.querySelector("button")).toBeNull();
    expect(screen.queryByRole("button", { name: "Show full code" })).toBeNull();
    expect(container.querySelector("code")?.textContent).toBe(token);
  });
  it("copies a long fingerprint within spaced inline code without changing its source text", async () => {
    const value = `HEAD ${"a".repeat(40)}; non-Markdown content SHA-256 ${"b".repeat(64)}`;
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    const { container } = renderRouted(<Prose html={`<p data-pb-src="0-180"><code>${value}</code></p><p><code>short code with spaces</code></p>`}
      reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: true }} />);
    fireEvent.click(await screen.findByRole("button", { name: "Copy code" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(value));
    expect(container.querySelector("p")?.textContent).toBe(value);
    expect(container.querySelector("p")?.getAttribute("data-pb-src")).toBe("0-180");
    expect(container.querySelectorAll(".pb-inline-token")).toHaveLength(1);
  });

  it("reveals and collapses the original inline code without changing source text or copy behavior", async () => {
    const value = `HEAD ${"a".repeat(40)}; SHA-256 ${"b".repeat(64)}`;
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    const { container } = renderRouted(<Prose html={`<p data-pb-src="0-180"><code>${value}</code></p>`}
      reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: true }} />);
    const reveal = await screen.findByRole("button", { name: "Show full code" });
    const code = container.querySelector("code")!;
    expect(reveal.getAttribute("aria-expanded")).toBe("false");
    expect(reveal.getAttribute("aria-controls")).toBe(code.id);
    expect(reveal.hasAttribute("data-pb-selection-chrome")).toBe(true);
    fireEvent.click(reveal);
    expect(screen.getByRole("button", { name: "Collapse code" }).getAttribute("aria-expanded")).toBe("true");
    expect(container.querySelector("code")).toBe(code);
    expect(code.textContent).toBe(value);
    fireEvent.click(screen.getByRole("button", { name: "Copy code" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(value));
    expect(reveal.getAttribute("aria-expanded")).toBe("true");
    fireEvent.click(reveal);
    expect(reveal.getAttribute("aria-expanded")).toBe("false");
    expect(container.querySelector("p")?.getAttribute("data-pb-src")).toBe("0-180");
    expect(container.querySelector("p")?.textContent).toBe(value);
  });

  it("reports clipboard rejection without modifying source text", async () => {
    const token = "a".repeat(80);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error("Denied")) } });
    const { container } = renderRouted(<Prose html={`<p><code>${token}</code></p>`}
      reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: false }} />);
    fireEvent.click(await screen.findByRole("button", { name: "Copy code" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Could not copy code"));
    expect(container.querySelector("p")?.textContent).toBe(token);
  });

  it("keeps read-only and blocked target cards inert and closes on viewport changes", async () => {
    renderRouted(<Prose html={'<p><a data-pb-link-error="blocked_scheme" data-pb-link-target="javascript:alert(1)">Blocked</a></p><button>Outside</button>'}
      reading={{ root: "docs", path: "source.md", url: "/docs/source", editable: false }} />);
    const trigger = await screen.findByRole("button", { name: "Blocked" });
    fireEvent.focusIn(trigger);
    expect(await screen.findByRole("dialog", { name: "This link is blocked" })).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
    const outside = screen.getByRole("button", { name: "Outside" });
    outside.focus();
    fireEvent.keyDown(outside, { key: "Escape" });
    expect(document.activeElement).toBe(outside);
    fireEvent(window, new Event("resize"));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

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
