import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { sessionQuery, treeQuery } from "../api/queries";
import type { TreeResponse } from "../api/types";
import { ErrorView } from "../components/ErrorView";
import { createAppRouter } from "../router";

/**
 * The branded per-match error boundary: ErrorView itself, its registration as the router's
 * `defaultErrorComponent`, and the load-bearing behavior — a route render crash shows the branded
 * surface while the Shell chrome stays mounted. The recovery link's hard-nav click handler
 * (preventDefault + stopPropagation + window.location.assign) is code-review-only coverage by
 * stated decision: jsdom cannot navigate; the `href` assert pins the target.
 */

// DocsPage throws synchronously in render (before any query fires); the rest of PageView is real.
vi.mock("../components/PageView", async (importOriginal) => {
  const original = await importOriginal<typeof import("../components/PageView")>();
  return {
    ...original,
    DocsPage: () => {
      throw new Error("render crash");
    },
  };
});

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };
const ANON_SESSION = { authenticated: false, username: null, csrf_token: null, auth_mode: "off" };

function renderAt(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  queryClient.setQueryData(sessionQuery.queryKey, ANON_SESSION);
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history, view };
}

describe("error boundary", () => {
  it("renders the branded surface: heading, message, and the /docs recovery link", () => {
    const view = render(<ErrorView error={new Error("boom")} reset={() => {}} />);

    const boundary = view.container.querySelector("[data-pb-error-boundary]");
    expect(boundary).not.toBeNull();
    expect(boundary?.querySelector("h1")?.textContent).toBe("Something went wrong");
    expect(boundary?.textContent).toContain("boom");
    expect(boundary?.querySelector("a")?.getAttribute("href")).toBe("/docs");
  });

  it("is registered as the router's defaultErrorComponent", () => {
    expect(createAppRouter(new QueryClient()).options.defaultErrorComponent).toBe(ErrorView);
  });

  it("keeps the Shell mounted when a route's render crashes", async () => {
    // React logs the caught render error; expected here, silenced for this test only.
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      const { view } = renderAt("/docs/guides/deploy-guide");

      await waitFor(() => expect(view.container.querySelector("[data-pb-error-boundary]")).not.toBeNull());
      expect(view.container.querySelector("[data-pb-shell]")).not.toBeNull();
    } finally {
      consoleError.mockRestore();
    }
  });
});
