import { useEffect, useRef } from "react";

type Read = { isFetching: boolean; isError: boolean; refetch: (options: { cancelRefetch: boolean }) => Promise<unknown> };

/** Automatic reads check live write locks without disabling explicit mutation invalidation. */
export function useDiscussionRefresh(query: Read, active: boolean, paused: () => boolean = () => false) {
  const current = useRef({ query, active, paused });
  current.current = { query, active, paused };
  useEffect(() => {
    if (!active) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const refresh = () => {
      const latest = current.current;
      if (!stopped && latest.active && !document.hidden && !latest.paused() && !latest.query.isFetching)
        void latest.query.refetch({ cancelRefetch: false });
    };
    const schedule = () => {
      timer = setTimeout(() => { refresh(); if (!stopped) schedule(); }, current.current.query.isError ? 30_000 : 15_000);
    };
    schedule();
    window.addEventListener("focus", refresh);
    window.addEventListener("online", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      stopped = true; clearTimeout(timer);
      window.removeEventListener("focus", refresh);
      window.removeEventListener("online", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [active]);
}
