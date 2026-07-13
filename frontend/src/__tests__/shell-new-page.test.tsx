import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { sessionQuery, treeQuery } from "../api/queries";
import type { TreeResponse } from "../api/types";
import { ROOT_UNAVAILABLE } from "../components/ErrorView";
import { createAppRouter } from "../router";

/**
 * C4: the chrome "New" action decides WHICH ROOT a create writes into, by carrying `?root=` from the
 * `/docs/{root}` space the reader is standing in. That answer comes from the tree, so until the tree
 * resolves there is no answer — and a `/new` link without `?root=` is not "no answer", it is `main`.
 * On an extra-root page that is a SILENT write into the wrong repository, which is why the pending
 * window disables the action instead of guessing.
 */

const HANDBOOK_PAGE = "/docs/handbook/guides/onboarding";

const tree: TreeResponse = {
  roots: [
    { root: "main", available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/main", page_count: 0, children: [] } },
    { root: "handbook", available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/handbook", page_count: 0, children: [] } },
  ],
};
const AUTHED = { authenticated: true, username: "admin", csrf_token: "c", auth_mode: "builtin" };

/** The same topology with `handbook` READ-ONLY — the default `plainbase root add` produces. */
const readOnlyHandbook: TreeResponse = {
  roots: [tree.roots[0], { ...tree.roots[1], editable: false }],
};

/** `handbook` EDITABLE but not serving (an unmounted disk). The tree still LISTS it (that is how the client
 *  tells "down" from "gone"), so `editable` alone still said yes and offered a create that can only 503. */
const unavailableHandbook: TreeResponse = {
  roots: [tree.roots[0], { ...tree.roots[1], available: false }],
};

/** [primeTree] false leaves the tree query in flight forever — the window under test. */
function renderShell(primeTree: boolean, seeded: TreeResponse = tree) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(sessionQuery.queryKey, AUTHED);
  if (primeTree) queryClient.setQueryData(treeQuery.queryKey, seeded);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url === "/api/v1/tree") return new Promise<Response>(() => {}); // never settles
      return new Response(JSON.stringify({ error: { code: "not_found", message: "no" } }), { status: 404 });
    }),
  );
  const history = createMemoryHistory({ initialEntries: [HANDBOOK_PAGE] });
  const router = createAppRouter(queryClient, history);
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("the chrome New action", () => {
  it("carries the current root once the tree has resolved", async () => {
    const view = renderShell(true);
    const action = await waitFor(() => {
      const el = view.container.querySelector("[data-pb-new-page]");
      expect(el).not.toBeNull();
      return el!;
    });
    expect(action.getAttribute("href")).toBe("/new?root=handbook");
  });

  it("is DISABLED while the tree is still in flight — never a link that would create in main", async () => {
    const view = renderShell(false);
    const action = await waitFor(() => {
      const el = view.container.querySelector("[data-pb-new-page]");
      expect(el).not.toBeNull();
      return el!;
    });
    expect(action.tagName).toBe("BUTTON");
    expect(action.hasAttribute("disabled")).toBe(true);
    // The failure this guards: an anchor to a rootless /new, from a page that lives in `handbook`.
    expect(action.getAttribute("href")).toBeNull();
  });

  it("is DISABLED on a READ-ONLY root — the create could only ever answer 403 root_not_editable", async () => {
    // `plainbase root add` defaults an extra root to `editable = false`, so this is the DEFAULT state of a
    // CLI-added root, not an exotic one. An enabled action here walks the reader into the editor, takes the
    // title they type, and fails at save. Same call as the pending window: disabled beats wrong.
    const view = renderShell(true, readOnlyHandbook);
    const action = await waitFor(() => {
      const el = view.container.querySelector("[data-pb-new-page]");
      expect(el).not.toBeNull();
      return el!;
    });
    expect(action.tagName).toBe("BUTTON");
    expect(action.hasAttribute("disabled")).toBe(true);
    expect(action.getAttribute("title")).toBe("This root is read-only");
  });

  it("is DISABLED on an UNAVAILABLE root - an editable root whose disk is not mounted can only answer 503", async () => {
    // `editable` and `available` are two bits and the gate needs BOTH: an unmounted-but-editable root passed the
    // editable check, so "New" stayed live on a root where the create could only ever 503 `root_unavailable`.
    const view = renderShell(true, unavailableHandbook);
    const action = await waitFor(() => {
      const el = view.container.querySelector("[data-pb-new-page]");
      expect(el).not.toBeNull();
      return el!;
    });
    expect(action.tagName).toBe("BUTTON");
    expect(action.hasAttribute("disabled")).toBe(true);
    // And it must not call an OUTAGE "read-only": the reader would go looking for a permission they do have.
    expect(action.getAttribute("title")).toBe(ROOT_UNAVAILABLE.headline("handbook"));
  });
});
