import { afterEach, describe, expect, it } from "vitest";
import { interceptableHref } from "../lib/links";

/**
 * Link-interception policy: internal `/docs` + `/p` + bare `/` anchors go through the SPA router;
 * external links, assets, downloads, modified clicks, and same-page fragments stay native.
 */

function clickOn(html: string, init: MouseEventInit = {}): string | null {
  document.body.innerHTML = html;
  const anchor = document.querySelector("a")!;
  let captured: string | null = null;
  const listener = (event: MouseEvent) => {
    captured = interceptableHref(event);
    event.preventDefault(); // jsdom must never actually navigate
  };
  document.addEventListener("click", listener);
  anchor.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, ...init }));
  document.removeEventListener("click", listener);
  return captured;
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("interceptableHref", () => {
  it("intercepts internal /docs links (path + hash preserved)", () => {
    expect(clickOn('<a href="/docs/guides/deploy-guide#rollback">x</a>')).toBe("/docs/guides/deploy-guide#rollback");
  });

  it("intercepts permalink /p links", () => {
    expect(clickOn('<a href="/p/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a">x</a>')).toBe("/p/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a");
  });

  it("intercepts the bare / link (header logo → first-page redirect route)", () => {
    window.history.replaceState(null, "", "/docs/welcome");
    expect(clickOn('<a href="/">Plainbase</a>')).toBe("/");
    window.history.replaceState(null, "", "/");
  });

  it("intercepts clicks on elements nested inside an anchor", () => {
    expect(clickOn('<a href="/docs/welcome"><strong>x</strong></a>')).toBe("/docs/welcome");
    // jsdom dispatches from the anchor; re-dispatch from the nested element explicitly
    document.body.innerHTML = '<a href="/docs/welcome"><strong>x</strong></a>';
    const strong = document.querySelector("strong")!;
    let captured: string | null = null;
    document.addEventListener(
      "click",
      (e) => {
        captured = interceptableHref(e);
        e.preventDefault();
      },
      { once: true },
    );
    strong.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    expect(captured).toBe("/docs/welcome");
  });

  it("lets external links through", () => {
    expect(clickOn('<a href="https://example.com/docs/x">x</a>')).toBeNull();
  });

  it("lets /assets links through (server-served resources)", () => {
    expect(clickOn('<a href="/assets/infra/assets/diagram.svg">x</a>')).toBeNull();
  });

  it("lets modified clicks through (new tab etc.)", () => {
    expect(clickOn('<a href="/docs/welcome">x</a>', { metaKey: true })).toBeNull();
    expect(clickOn('<a href="/docs/welcome">x</a>', { ctrlKey: true })).toBeNull();
    expect(clickOn('<a href="/docs/welcome">x</a>', { button: 1 })).toBeNull();
  });

  it("lets target=_blank and download links through", () => {
    expect(clickOn('<a href="/docs/welcome" target="_blank">x</a>')).toBeNull();
    expect(clickOn('<a href="/docs/welcome" download>x</a>')).toBeNull();
  });

  it("lets same-page fragment jumps stay native", () => {
    window.history.replaceState(null, "", "/docs/guides/deploy-guide");
    expect(clickOn('<a href="/docs/guides/deploy-guide#rollback">x</a>')).toBeNull();
    window.history.replaceState(null, "", "/");
  });
});
