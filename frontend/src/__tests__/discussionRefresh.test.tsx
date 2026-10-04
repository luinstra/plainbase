import { QueryClient, QueryClientProvider, useQuery, useInfiniteQuery } from "@tanstack/react-query";
import { act, render, fireEvent } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { pageDiscussionsQuery } from "../api/discussions";
import { uniqueDiscussionItems } from "../components/DiscussionRead";
import { useDiscussionRefresh } from "../lib/useDiscussionRefresh";

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it("pauses automatic dispatch immediately but keeps explicit write refresh alive", async () => {
  vi.useFakeTimers();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
  const read = vi.fn(async () => ({ discussions_available: true }));
  let pending = false;
  function View({ active }: { active: boolean }) {
    const query = useQuery({ queryKey: ["discussions", "test"], queryFn: read, enabled: active });
    useDiscussionRefresh(query, active, () => pending);
    return <span>{query.data ? "Loaded" : "Loading"}</span>;
  }
  const view = render(<QueryClientProvider client={client}><View active /></QueryClientProvider>);
  await act(() => vi.advanceTimersByTimeAsync(1));
  expect(read).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(15_000));
  expect(read).toHaveBeenCalledTimes(2);
  // No render occurs between acquiring the write lock and automatic events.
  pending = true;
  await act(async () => { window.dispatchEvent(new Event("focus")); await vi.advanceTimersByTimeAsync(15_000); });
  expect(read).toHaveBeenCalledTimes(2);
  await act(async () => { await client.invalidateQueries({ queryKey: ["discussions"] }); });
  expect(read).toHaveBeenCalledTimes(3);
  pending = false;
  await act(async () => { window.dispatchEvent(new Event("focus")); await vi.advanceTimersByTimeAsync(1); });
  expect(read).toHaveBeenCalledTimes(4);
  view.rerender(<QueryClientProvider client={client}><View active={false} /></QueryClientProvider>);
  await act(async () => { window.dispatchEvent(new Event("online")); await vi.advanceTimersByTimeAsync(30_000); });
  expect(read).toHaveBeenCalledTimes(4);
  view.unmount();
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(read).toHaveBeenCalledTimes(4);
  client.clear();
});

it("updates rendered data on return from a hidden document", async () => {
  vi.useFakeTimers();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
  let hidden = false;
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
  let generation = 0;
  const read = vi.fn(async () => ({ generation: ++generation }));
  function View() {
    const query = useQuery({ queryKey: ['discussions', 'hidden'], queryFn: read });
    useDiscussionRefresh(query, true);
    return <span>{query.data?.generation}</span>;
  }
  const view = render(<QueryClientProvider client={client}><View /></QueryClientProvider>);
  await act(() => vi.advanceTimersByTimeAsync(1));
  expect(view.container.textContent).toBe('1');
  hidden = true;
  await act(async () => { window.dispatchEvent(new Event('focus')); await vi.advanceTimersByTimeAsync(45_000); });
  expect(read).toHaveBeenCalledTimes(1);
  hidden = false;
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')); await vi.advanceTimersByTimeAsync(1); });
  expect(read).toHaveBeenCalledTimes(2);
  expect(view.container.textContent).toBe('2');
  view.unmount(); client.clear();
});

it("refreshes every loaded cursor window without dropping rows or duplicating a boundary", async () => {
  vi.useFakeTimers();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const reads: string[] = [];
  let label = 'Before';
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input); reads.push(url);
    const later = url.includes('cursor=next');
    return Response.json({ discussions: later ? [{ id: 'one', quote: label }, { id: 'two', quote: 'Second' }] : [{ id: 'one', quote: label }],
      next: later ? null : 'next', discussions_available: true, reason: null });
  }));
  function View() {
    const query = useInfiniteQuery(pageDiscussionsQuery('docs', 'page'));
    useDiscussionRefresh(query, true);
    return <><button onClick={() => void query.fetchNextPage()}>More</button>
      <p>{uniqueDiscussionItems(query.data?.pages ?? []).map((item) => item.quote).join('|')}</p></>;
  }
  const view = render(<QueryClientProvider client={client}><View /></QueryClientProvider>);
  await act(() => vi.advanceTimersByTimeAsync(1));
  fireEvent.click(view.getByText('More'));
  await act(() => vi.advanceTimersByTimeAsync(1));
  expect(view.container.querySelector('p')?.textContent).toBe('Before|Second');
  label = 'After';
  await act(() => vi.advanceTimersByTimeAsync(15_000));
  expect(view.container.querySelector('p')?.textContent).toBe('After|Second');
  expect(reads).toEqual([
    '/api/v1/pages/page/discussions?root=docs&limit=200', '/api/v1/pages/page/discussions?root=docs&limit=200&cursor=next',
    '/api/v1/pages/page/discussions?root=docs&limit=200', '/api/v1/pages/page/discussions?root=docs&limit=200&cursor=next',
  ]);
  view.unmount(); client.clear();
});
