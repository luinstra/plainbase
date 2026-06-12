import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Prose } from "../components/Prose";

/**
 * `.pb-prose` stable-selector + presentation-enhancement checks. The HTML is what the
 * server renderer emits: heading ids, rewritten hrefs, and the `data-pb-link-error`
 * broken-link marker (FlexmarkRenderer) — the SPA only decorates.
 */

const serverHtml = [
  '<h1 id="deploy-guide">Deploy Guide</h1>',
  '<p><a href="/docs/infra/kubernetes">Kubernetes setup</a></p>',
  '<p><a data-pb-link-error="broken_missing">missing page</a></p>',
  '<pre><code class="language-json">{"key": "value"}</code></pre>',
].join("\n");

describe("Prose", () => {
  it("renders server html under the stable .pb-prose selector", () => {
    const { container } = render(<Prose html={serverHtml} />);
    const article = container.querySelector("article.pb-prose");
    expect(article).not.toBeNull();
    expect(article!.hasAttribute("data-pb-prose")).toBe(true);
    // The server's broken-link marker passes through untouched — CSS styles it.
    expect(container.querySelector('[data-pb-link-error="broken_missing"]')).not.toBeNull();
  });

  it("injects heading anchor links on server-emitted ids", () => {
    const { container } = render(<Prose html={serverHtml} />);
    const anchor = container.querySelector("h1 a.pb-heading-anchor");
    expect(anchor).not.toBeNull();
    expect(anchor!.getAttribute("href")).toBe("#deploy-guide");
  });

  it("highlights fenced code blocks via highlight.js", () => {
    const { container } = render(<Prose html={serverHtml} />);
    const code = container.querySelector("pre code");
    expect(code!.classList.contains("hljs")).toBe(true);
    expect(code!.querySelectorAll("span[class^='hljs-']").length).toBeGreaterThan(0);
  });
});
