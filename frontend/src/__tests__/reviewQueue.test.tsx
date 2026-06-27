import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { sessionQuery, treeQuery } from "../api/queries";
import type { ChangeSummary, ListChangesResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * P4 review queue (WI-4). `GET /api/v1/changes` returns ALL statuses (F1); the queue sorts PENDING first and
 * marks drifted/conflicted rows. Mirrors the history.test harness: full router, primed tree + session, a
 * per-test fetch stub. Each row links to `/review/$id`.
 */

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };
const AUTHED = { authenticated: true, username: "admin", csrf_token: "c", auth_mode: "builtin" };

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function summary(over: Partial<ChangeSummary>): ChangeSummary {
  return {
    id: "id-1",
    operation: "edit",
    status: "PENDING",
    target_path: "guides/deploy-guide.md",
    page_id: "page-1",
    base_drifted: false,
    author_label: "agent: ci",
    created_at: "2026-06-26T10:00:00Z",
    rationale: "tighten the rollback steps",
    ...over,
  };
}

function renderQueue(list: ListChangesResponse) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  queryClient.setQueryData(sessionQuery.queryKey, AUTHED);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url === "/api/v1/changes") return jsonResponse(list);
      return jsonResponse({});
    }),
  );
  const history = createMemoryHistory({ initialEntries: ["/review"] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { view };
}

afterEach(() => vi.unstubAllGlobals());

describe("review queue", () => {
  it("lists the proposals, links each to its detail, and marks drifted/conflicted rows", async () => {
    const { view } = renderQueue({
      proposals: [
        summary({ id: "clean", status: "PENDING", base_drifted: false }),
        summary({ id: "drifted", status: "PENDING", base_drifted: true }),
        summary({ id: "conflicted", status: "CONFLICTED", base_drifted: false }),
        summary({ id: "applied", status: "APPLIED", base_drifted: false }),
      ],
    });

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-review-row]").length).toBe(4));

    // A clean PENDING row is not drift-marked; the drifted PENDING and the CONFLICTED both are.
    const clean = view.container.querySelector('[data-pb-review-row][data-pb-review-row-status="PENDING"]:not([data-pb-review-row-drifted])');
    expect(clean).not.toBeNull();
    const drifted = view.container.querySelectorAll("[data-pb-review-row][data-pb-review-row-drifted]");
    expect(drifted.length).toBe(2); // the drifted PENDING + the CONFLICTED

    // Each row links to its detail.
    const links = view.container.querySelectorAll<HTMLAnchorElement>("[data-pb-review-row-link]");
    expect(links.length).toBe(4);
    expect([...links].some((a) => a.getAttribute("href")?.includes("/review/clean"))).toBe(true);
  });

  it("sorts PENDING (actionable) rows ahead of terminal ones", async () => {
    const { view } = renderQueue({
      proposals: [
        summary({ id: "applied", status: "APPLIED" }),
        summary({ id: "pending", status: "PENDING" }),
      ],
    });

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-review-row]").length).toBe(2));
    const rows = view.container.querySelectorAll("[data-pb-review-row]");
    expect(rows[0].getAttribute("data-pb-review-row-status")).toBe("PENDING");
  });

  it("renders the empty notice when there are no proposals", async () => {
    const { view } = renderQueue({ proposals: [] });
    await waitFor(() => expect(view.container.querySelector("[data-pb-review-empty]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-review-row]")).toBeNull();
  });
});
