import { useEffect, useState } from "react";

/**
 * Debounces a value by `delayMs` (master D11 — hand-rolled, no debounce library). The
 * search overlay holds the raw input in state and feeds the debounced value to the query,
 * so the FETCH is never manually debounced — only which `q` becomes active is delayed, and
 * TanStack Query handles dedupe/cache/staleness from there (Resolution 3).
 */
export function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
