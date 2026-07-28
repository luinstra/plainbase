import { afterEach, describe, expect, it } from "vitest";
import type { RootTree } from "../api/types";
import { interceptableHref } from "../lib/links";

/**
 * Link-interception policy: tree-owned root URLs, `/p` permalinks, and bare `/` anchors go through the SPA router;
 * external links, assets, downloads, modified clicks, and same-page fragments stay native.
 */

const roots: RootTree[] = [
  { root: "docs", available: true, editable: true, primary: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } },
  { root: "extra", available: true, editable: true, primary: false, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } },
];

const defaultRoots = Symbol("default roots");
const noRoots = Symbol("no roots");

function clickOn(
  html: string,
  init: MouseEventInit = {},
  loadedRoots: RootTree[] | undefined | typeof defaultRoots | typeof noRoots = defaultRoots,
): string | null {
  const rootsForClick = loadedRoots === defaultRoots ? roots : loadedRoots === noRoots ? undefined : loadedRoots;
  document.body.innerHTML = html;
  const anchor = document.querySelector("a")!;
  let captured: string | null = null;
  const listener = (event: MouseEvent) => {
    captured = interceptableHref(event, rootsForClick);
    event.preventDefault(); // jsdom must never actually navigate
  };
  document.addEventListener("click", listener);
  anchor.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, ...init }));
  document.removeEventListener("click", listener);
  return captured;
}

function clickWithoutRoots(html: string, init: MouseEventInit = {}): string | null {
  return clickOn(html, init, noRoots);
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("interceptableHref", () => {
  it("intercepts internal /docs links (path + hash preserved)", () => {
    expect(clickOn('<a href="/docs/guides/deploy-guide#rollback">x</a>')).toBe("/docs/guides/deploy-guide#rollback");
  });

  it("intercepts a registered root URL but leaves an unknown first segment native", () => {
    expect(clickOn('<a href="/extra/notes/y">extra</a>')).toBe("/extra/notes/y");
    expect(clickOn('<a href="/nope/notes/y">unknown</a>')).toBeNull();
  });

  it("leaves root-content links native while the tree is unavailable", () => {
    expect(clickWithoutRoots('<a href="/docs/guides/deploy-guide">docs</a>')).toBeNull();
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
    expect(clickOn('<a href="/docs/guides/deploy%20guide">x</a>')).toBe("/docs/guides/deploy%20guide");
  });

  it("intercepts ROOTED permalink /p/{root}/{id} links - the form the server actually emits", () => {
    // Beside the bare row, not instead of it: the emitted permalink is rooted, so a prefix test that
    // only ever saw the bare shape would hand every real loser link to a full page load.
    expect(clickOn('<a href="/p/docs/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a">x</a>')).toBe("/p/docs/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a");
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
        captured = interceptableHref(e, roots);
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
    expect(clickOn('<a href="/assets/docs/infra/assets/diagram.svg">x</a>')).toBeNull();
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
