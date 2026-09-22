import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { diagramSourceQuery, sessionQuery, treeQuery } from "../api/queries";
import type { TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

const mermaidMock = vi.hoisted(() => ({
  initialize: vi.fn(),
  render: vi.fn(async () => ({ svg: "<svg><title>viewer</title></svg>" })),
}));

vi.mock("mermaid", () => ({ default: mermaidMock }));

const tree: TreeResponse = {
  roots: [
    {
      root: "docs",
      available: true,
      editable: true,
      primary: true,
      tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] },
    },
  ],
};

const session = { authenticated: false, username: null, csrf_token: null, auth_mode: "off" };

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function sourceResponse(source: string): Response {
  return new Response(source, { status: 200, headers: { "content-type": "application/octet-stream" } });
}

function renderAt(pathname: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, tree);
  queryClient.setQueryData(sessionQuery.queryKey, session);
  const history = createMemoryHistory({ initialEntries: [pathname] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { queryClient, history, view };
}

afterEach(() => {
  vi.unstubAllGlobals();
  mermaidMock.initialize.mockClear();
  mermaidMock.render.mockClear();
});

describe("standalone Mermaid viewer", () => {
  it("cleans up on a background refetch error and renders identical recovered source", async () => {
    const source = "flowchart LR\n  A --> B";
    const responses = [sourceResponse(source), jsonResponse({ error: { code: "root_unavailable", message: "down" } }, 503), sourceResponse(source)];
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes("/assets/")) return responses.shift() ?? sourceResponse(source);
      return jsonResponse({}, 200);
    });
    vi.stubGlobal("fetch", fetchSpy);

    const { queryClient, view } = renderAt("/browse/docs/flow.mmd");
    const query = diagramSourceQuery("docs", "flow.mmd");
    await waitFor(() => expect(view.container.querySelector("[data-pb-mermaid] svg")).not.toBeNull());
    expect(mermaidMock.render).toHaveBeenCalledTimes(1);

    await queryClient.invalidateQueries({ queryKey: query.queryKey });
    await waitFor(() => expect(view.container.querySelector("[data-pb-root-unavailable]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-mermaid]")).toBeNull();
    expect(view.container.querySelector("[data-pb-mermaid-scratch]")).toBeNull();

    await queryClient.refetchQueries({ queryKey: query.queryKey });
    await waitFor(() => expect(view.container.querySelector("[data-pb-mermaid] svg")).not.toBeNull());
    expect(mermaidMock.render).toHaveBeenCalledTimes(2);
    view.unmount();
  });

  it("uses the routed decoded root/path, keeps read-only controls absent, and rejects scoped unsafe paths", async () => {
    const source = "flowchart LR\n  A --> B";
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes("/assets/") ? sourceResponse(source) : jsonResponse({}, 200),
    );
    vi.stubGlobal("fetch", fetchSpy);

    const { view } = renderAt("/browse/docs/diagrams/space%20name.mmd?mode=edit");
    await waitFor(() => expect(view.container.querySelector("[data-pb-diagram]")).not.toBeNull());
    expect(view.container.querySelector("h1")?.textContent).toBe("space name.mmd");
    expect(view.container.querySelector("[data-pb-diagram-source]")?.getAttribute("href")).toBe(
      "/assets/docs/diagrams/space%20name.mmd",
    );
    expect(view.container.querySelector("[data-pb-editor], [data-pb-history], [data-pb-proposal]")).toBeNull();
    expect(fetchSpy).toHaveBeenCalledWith("/assets/docs/diagrams/space%20name.mmd", expect.any(Object));
    view.unmount();

    const invalid = renderAt("/browse/docs/diagrams/a%5Cb.mmd");
    await waitFor(() => expect(invalid.view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    expect(invalid.queryClient.getQueryData(diagramSourceQuery("docs", "diagrams/a\\b.mmd").queryKey)).toBeUndefined();
    invalid.view.unmount();
  });

  it("does not commit a late Mermaid render after route navigation", async () => {
    const oldSource = "flowchart LR\n  A --> B";
    const newSource = "flowchart LR\n  C --> D";
    let resolveOld: ((result: { svg: string }) => void) | undefined;
    mermaidMock.render.mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveOld = resolve;
      }),
    );
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (!url.includes("/assets/")) return jsonResponse({}, 200);
      return sourceResponse(url.includes("next.mmd") ? newSource : oldSource);
    }));

    const { history, view } = renderAt("/browse/docs/old.mmd");
    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(1));
    history.push("/browse/docs/next.mmd");
    await waitFor(() => expect(view.container.querySelector("h1")?.textContent).toBe("next.mmd"));

    resolveOld?.({ svg: "<svg><title>stale</title></svg>" });
    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.container.querySelector("[data-pb-mermaid] svg title")?.textContent).toBe("viewer"));
    expect(view.container.textContent).not.toContain("stale");
    expect(view.container.querySelector("[data-pb-mermaid-scratch]")).toBeNull();
    view.unmount();
  });

  it.each([
    [401, "unauthorized", "[data-pb-error]"],
    [404, "not_found", "[data-pb-not-found]"],
    [503, "root_unavailable", "[data-pb-root-unavailable]"],
  ] as const)("keeps the %s guarded source surface reachable", async (status, code, selector) => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes("/assets/")
        ? jsonResponse({ error: { code, message: code } }, status)
        : jsonResponse({}, 200),
    ));
    const { view } = renderAt("/browse/docs/missing.mmd");
    await waitFor(() => expect(view.container.querySelector(selector)).not.toBeNull());
    view.unmount();
  });
});
