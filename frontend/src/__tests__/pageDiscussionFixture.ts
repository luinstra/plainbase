import type { QueryClient } from "@tanstack/react-query";
import { pageDiscussionsQuery } from "../api/discussions";
import type { PageHtmlResponse } from "../api/types";

export const emptyDiscussionList = { discussions: [], next: null, discussions_available: true, reason: null };

/** Cached read-view fixtures explicitly prime the now-default discussion read as well. */
export function primePageDiscussionLists(client: QueryClient) {
  for (const query of client.getQueryCache().findAll({ queryKey: ["page", "html"] })) {
    const html = query.state.data as PageHtmlResponse | undefined;
    if (html) {
      const key = pageDiscussionsQuery(html.root, html.id).queryKey;
      client.setQueryDefaults(key, { staleTime: Infinity });
      client.setQueryData(key, { pages: [emptyDiscussionList], pageParams: [null] });
    }
  }
}
