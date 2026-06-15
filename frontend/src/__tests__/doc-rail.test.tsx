import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, pageQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * Chunk-4 doc reading metadata Rail / footer (addendum §6 acceptance). The Rail renders one
 * row per PRESENT frontmatter key plus the always-present File row; the status chip carries a
 * status-keyed hook; the owner row shows initials. The Rail and footer are app chrome — never
 * descendants of `article.pb-prose`. A pending/errored frontmatter fetch never blanks the doc.
 *
 * `PageContent` is reached via the `/p/$` permalink route, which fetches the page by id and
 * renders `<PageContent>`; we prime BOTH `pageQuery` (frontmatter + permalink resolution) and
 * `pageHtmlQuery` (prose + TOC) so nothing touches the network.
 */

const PAGE_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";

function htmlResponse(id: string, headings: PageHtmlResponse["headings"]): PageHtmlResponse {
  const title = "Kubernetes";
  return {
    id,
    path: "infra/kubernetes.md",
    slug: "kubernetes",
    url: null,
    title,
    html: `<h1 id="t">${title}</h1>`,
    content_hash: "h",
    commit: null,
    headings,
    citation: { page_id: id, heading_id: null, path: "infra/kubernetes.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` },
  };
}

function pageResponse(id: string, frontmatter: Record<string, unknown>): PageResponse {
  return {
    id,
    path: "infra/kubernetes.md",
    slug: "kubernetes",
    url: null,
    title: "Kubernetes",
    markdown: "# Kubernetes",
    frontmatter,
    content_hash: "h",
    id_materialized: true,
    commit: null,
    citation: { page_id: id, heading_id: null, path: "infra/kubernetes.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` },
  };
}

/** Mounts the permalink route at `/p/{PAGE_ID}`, priming html always and (optionally) frontmatter. */
function renderRail(frontmatter: Record<string, unknown> | null, headings: PageHtmlResponse["headings"] = [{ id: "t", level: 1, text: "Kubernetes" }]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(pageHtmlQuery(PAGE_ID).queryKey, htmlResponse(PAGE_ID, headings));
  if (frontmatter) queryClient.setQueryData(pageQuery(PAGE_ID).queryKey, pageResponse(PAGE_ID, frontmatter));
  const history = createMemoryHistory({ initialEntries: [`/p/${PAGE_ID}`] });
  const router = createAppRouter(queryClient, history);
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** Reads each Rail row as [KEY-label, value-text] so absence/presence is asserted by label. */
function railRows(container: HTMLElement): Record<string, string> {
  const rows = [...container.querySelectorAll("[data-pb-rail] .pb-meta-row")];
  return Object.fromEntries(
    rows.map((row) => [row.querySelector(".pb-meta-key")?.textContent ?? "", row.querySelector(".pb-meta-val")?.textContent?.trim() ?? ""]),
  );
}

// `stubNotFound` would fire on the un-primed pageQuery case below; instead we leave fetch
// stubbed to reject so a stray network call is loud rather than silently 404-degrading.
afterEach(() => vi.unstubAllGlobals());

describe("doc reading metadata rail (chunk-4)", () => {
  it("renders one row per present frontmatter key plus the always-present File row, omitting absent keys", async () => {
    const { container } = renderRail({ owner: "ops", status: "active", tags: ["infra", "kubernetes"], updated: "2026-06-11" });

    await waitFor(() => expect(container.querySelector("[data-pb-rail-meta]")).not.toBeNull());
    const rows = railRows(container);
    expect(Object.keys(rows)).toEqual(["Owner", "Status", "Tags", "Updated", "File"]);
    expect(rows.Owner).toContain("ops");
    expect(rows.Tags).toBe("infrakubernetes"); // .pb-tag::before injects the leading # via CSS (not in textContent)
    expect(rows.Updated).toBe("2026-06-11");
    expect(rows.File).toBe("infra/kubernetes.md");
    // The absent `review` key renders no row.
    expect(Object.keys(rows)).not.toContain("Review");
  });

  it("keys the status chip color hook to the status string", async () => {
    const { container } = renderRail({ status: "active" });
    await waitFor(() => expect(container.querySelector(".pb-chip")).not.toBeNull());
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("active");
    expect(container.querySelector(".pb-chip")?.textContent).toContain("active");
  });

  it("keys the chip to a draft status too", async () => {
    const { container } = renderRail({ status: "draft" });
    await waitFor(() => expect(container.querySelector(".pb-chip")).not.toBeNull());
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("draft");
  });

  it("renders an unrecognized status without throwing (neutral chip, hook carries the raw string)", async () => {
    const { container } = renderRail({ status: "experimental" });
    await waitFor(() => expect(container.querySelector(".pb-chip")).not.toBeNull());
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("experimental");
  });

  it("derives the owner avatar initials (ops → OP)", async () => {
    const { container } = renderRail({ owner: "ops" });
    await waitFor(() => expect(container.querySelector(".pb-avatar")).not.toBeNull());
    expect(container.querySelector(".pb-avatar")?.textContent).toBe("OP");
  });

  it("renders the doc footer 'Last updated … by …' line, dropping the by-clause when owner absent", async () => {
    const withOwner = renderRail({ owner: "ops", updated: "2026-06-11" });
    await waitFor(() => expect(withOwner.container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(withOwner.container.querySelector(".pb-docfoot-updated")?.textContent).toBe("Last updated 2026-06-11 by ops");
    withOwner.unmount();

    const noOwner = renderRail({ updated: "2026-06-11" });
    await waitFor(() => expect(noOwner.container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(noOwner.container.querySelector(".pb-docfoot-updated")?.textContent).toBe("Last updated 2026-06-11");

    // No `updated` → no footer line at all.
    const noUpdated = renderRail({ owner: "ops" });
    await waitFor(() => expect(noUpdated.container.querySelector(".pb-prose h1")).not.toBeNull());
    expect(noUpdated.container.querySelector("[data-pb-docfoot]")).toBeNull();
  });

  it("keeps all chrome OUT of the rendered markdown (.pb-prose), and the rail a non-descendant", async () => {
    const { container } = renderRail({ owner: "ops", status: "active", tags: ["infra"], updated: "2026-06-11" });
    await waitFor(() => expect(container.querySelector("[data-pb-rail]")).not.toBeNull());

    // The guard: no chrome node lives under article.pb-prose.
    expect(container.querySelector(".pb-prose [data-pb-rail]")).toBeNull();
    expect(container.querySelector(".pb-prose .pb-chip")).toBeNull();
    expect(container.querySelector(".pb-prose .pb-avatar")).toBeNull();
    expect(container.querySelector(".pb-prose .pb-tag")).toBeNull();
    expect(container.querySelector(".pb-prose [data-pb-docfoot]")).toBeNull();

    // Conversely the rail exists, as a NON-descendant of .pb-prose.
    const rail = container.querySelector("[data-pb-rail]");
    expect(rail).not.toBeNull();
    expect(rail!.closest(".pb-prose")).toBeNull();
  });

  it("reuses the by-path page for the rail on /docs — no redundant /api/v1/pages/:id fetch", async () => {
    // The /docs/$ route already resolved the page (with frontmatter) via pageByPathQuery and hands
    // it to PageContent, so the rail reads that metadata directly. pageQuery(id) is left un-primed
    // and the network is stubbed to throw: any stray by-id fetch would blow up loudly. (Codex P2.)
    // Record every request URL; return a benign empty tree so Breadcrumbs (which legitimately reads
    // /api/v1/tree) doesn't error. The rail reads the PRIMED by-path cache, so it never needs the network.
    const calls: string[] = [];
    const emptyRoot = { root: { type: "folder", name: "", title: null, path: "", url: "/docs", children: [] } };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response(JSON.stringify(emptyRoot), { status: 200, headers: { "content-type": "application/json" } });
    }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    queryClient.setQueryData(pageByPathQuery("infra/kubernetes").queryKey, pageResponse(PAGE_ID, { owner: "ops", status: "active" }));
    queryClient.setQueryData(pageHtmlQuery(PAGE_ID).queryKey, htmlResponse(PAGE_ID, []));
    const history = createMemoryHistory({ initialEntries: ["/docs/infra/kubernetes"] });
    const router = createAppRouter(queryClient, history);
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    // The rail shows the by-path frontmatter…
    await waitFor(() => expect(container.querySelector("[data-pb-rail-meta]")).not.toBeNull());
    expect(railRows(container).Owner).toContain("ops");
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("active");
    // …without the redundant by-id page fetch (/api/v1/pages/:id) Codex flagged. The /html and
    // by-path endpoints are distinct paths, so this only catches the duplicate page load.
    expect(calls.some((u) => u.endsWith(`/api/v1/pages/${PAGE_ID}`))).toBe(false);
  });

  it("renders a File-only rail (doc never blank) when the page carries no frontmatter", async () => {
    // The "HTML is primary" contract: an empty/absent frontmatter degrades the rail to its
    // always-present File row, while prose + TOC render unaffected.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const headings = [
      { id: "a", level: 2, text: "Alpha" },
      { id: "b", level: 2, text: "Beta" },
    ];
    queryClient.setQueryData(pageByPathQuery("infra/kubernetes").queryKey, pageResponse(PAGE_ID, {}));
    queryClient.setQueryData(pageHtmlQuery(PAGE_ID).queryKey, htmlResponse(PAGE_ID, headings));
    const history = createMemoryHistory({ initialEntries: ["/docs/infra/kubernetes"] });
    const router = createAppRouter(queryClient, history);
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(container.querySelector(".pb-prose h1")?.textContent).toContain("Kubernetes"));
    expect(container.querySelector("[data-pb-toc]")).not.toBeNull();
    await waitFor(() => expect(railRows(container)).toEqual({ File: "infra/kubernetes.md" }));
  });
});
