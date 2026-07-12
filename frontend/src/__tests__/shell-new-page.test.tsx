import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { sessionQuery, treeQuery } from "../api/queries";
import type { TreeResponse } from "../api/types";
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
    { root: "main", available: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/main", page_count: 0, children: [] } },
    { root: "handbook", available: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/handbook", page_count: 0, children: [] } },
  ],
};
const AUTHED = { authenticated: true, username: "admin", csrf_token: "c", auth_mode: "builtin" };

/** [primeTree] false leaves the tree query in flight forever — the window under test. */
function renderShell(primeTree: boolean) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(sessionQuery.queryKey, AUTHED);
  if (primeTree) queryClient.setQueryData(treeQuery.queryKey, tree);
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
});
