import type { QueryClient } from "@tanstack/react-query";
import { pageDiscussionsQuery } from "../api/discussions";
import { treeQuery } from "../api/queries";
import type { PageHtmlResponse, TreeResponse } from "../api/types";

export const emptyDiscussionList = { discussions: [], next: null, discussions_available: true, reason: null };

/** Cached read-view fixtures explicitly prime the now-default discussion read as well. */
export function primePageDiscussionLists(client: QueryClient) {
  for (const query of client.getQueryCache().findAll({ queryKey: ["page", "html"] })) {
    const html = query.state.data as PageHtmlResponse | undefined;
    if (html) {
      const tree = client.getQueryData<TreeResponse>(treeQuery.queryKey);
      if (!tree?.roots.some((entry) => entry.root === html.root)) client.setQueryData(treeQuery.queryKey, { roots: [
        ...(tree?.roots ?? []), { root: html.root, primary: html.root === "docs", available: true, editable: true,
          tree: { type: "folder", name: "", title: null, description: null, path: "", url: `/${html.root}`, page_count: 0, children: [] } },
      ] });
      const key = pageDiscussionsQuery(html.root, html.id).queryKey;
      client.setQueryDefaults(key, { staleTime: Infinity });
      client.setQueryData(key, { pages: [emptyDiscussionList], pageParams: [null] });
    }
  }
}
