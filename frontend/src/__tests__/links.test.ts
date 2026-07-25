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
    expect(clickOn('<a href="/docs/main/guides/deploy-guide#rollback">x</a>')).toBe("/docs/main/guides/deploy-guide#rollback");
  });

  it("intercepts permalink /p links", () => {
    expect(clickOn('<a href="/p/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a">x</a>')).toBe("/p/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a");
  });

  it("lets a PERCENT-ENCODED permalink through to the server, which reads the raw path we cannot", () => {
    // `/p/%65xtra/{id}`: the anchor still holds the raw escape, but the router hands the route a
    // decodeURI'd pathname, so the parse would see `extra` and render a page. The server splits the RAW
    // tail, so its root segment is `%65xtra` and it answers 400 `invalid_root` - the click would work and
    // the reload would not. Nothing legal needs the escape (root slugs and page ids are URL-unreserved),
    // so the browser gets to ask, and the reader gets the server's own verdict.
    expect(clickOn('<a href="/p/%65xtra/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a">x</a>')).toBeNull();
    // The `%2F` case the router's own guard 404s on, refused one step earlier and for the same reason.
    expect(clickOn('<a href="/p/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a%2Fstale">x</a>')).toBeNull();
    // A `/docs` path is NOT swept up by it: those segments are percent-encoded on purpose (encodeTreePath).
    expect(clickOn('<a href="/docs/main/guides/deploy%20guide">x</a>')).toBe("/docs/main/guides/deploy%20guide");
  });

  it("intercepts ROOTED permalink /p/{root}/{id} links - the form the server actually emits", () => {
    // Beside the bare row, not instead of it: the emitted permalink is rooted, so a prefix test that
    // only ever saw the bare shape would hand every real loser link to a full page load.
    expect(clickOn('<a href="/p/main/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a">x</a>')).toBe("/p/main/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a");
  });

  it("intercepts the bare / link (header logo → first-page redirect route)", () => {
    window.history.replaceState(null, "", "/docs/main/welcome");
    expect(clickOn('<a href="/">Plainbase</a>')).toBe("/");
    window.history.replaceState(null, "", "/");
  });

  it("intercepts clicks on elements nested inside an anchor", () => {
    expect(clickOn('<a href="/docs/main/welcome"><strong>x</strong></a>')).toBe("/docs/main/welcome");
    // jsdom dispatches from the anchor; re-dispatch from the nested element explicitly
    document.body.innerHTML = '<a href="/docs/main/welcome"><strong>x</strong></a>';
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
    expect(captured).toBe("/docs/main/welcome");
  });

  it("lets external links through", () => {
    expect(clickOn('<a href="https://example.com/docs/x">x</a>')).toBeNull();
  });

  it("lets /assets links through (server-served resources)", () => {
    expect(clickOn('<a href="/assets/main/infra/assets/diagram.svg">x</a>')).toBeNull();
  });

  it("lets modified clicks through (new tab etc.)", () => {
    expect(clickOn('<a href="/docs/main/welcome">x</a>', { metaKey: true })).toBeNull();
    expect(clickOn('<a href="/docs/main/welcome">x</a>', { ctrlKey: true })).toBeNull();
    expect(clickOn('<a href="/docs/main/welcome">x</a>', { button: 1 })).toBeNull();
  });

  it("lets target=_blank and download links through", () => {
    expect(clickOn('<a href="/docs/main/welcome" target="_blank">x</a>')).toBeNull();
    expect(clickOn('<a href="/docs/main/welcome" download>x</a>')).toBeNull();
  });

  it("lets same-page fragment jumps stay native", () => {
    window.history.replaceState(null, "", "/docs/main/guides/deploy-guide");
    expect(clickOn('<a href="/docs/main/guides/deploy-guide#rollback">x</a>')).toBeNull();
    window.history.replaceState(null, "", "/");
  });
});
