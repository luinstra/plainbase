import { useQuery } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { createContext, useCallback, useContext, useEffect, useRef, type ComponentProps, type ReactNode } from "react";
import { ApiError } from "../api/client";
import { byPathKeyForUrl, pageByPathQuery, pageQuery, treeQuery } from "../api/queries";
import { parseDiagramPath } from "../lib/diagramPath";
import { parsePermalink, permalinkSplat } from "../lib/permalink";
import { entryFor, folderByUrl, folderForLanding, primaryEntry, rootAcceptsWrites, rootOfLocation } from "../lib/tree";
import { NewPageDialog } from "./NewPageDialog";

interface CreationEntry { sourceHref: string; root: string; folder: string; sessionKey: string }
declare module "@tanstack/history" { interface HistoryState { pbNewPage?: CreationEntry } }
interface CreationContext {
  isOpen: boolean;
  ready: boolean;
  root: string | undefined;
  folder: string;
  open: (root: string, folder: string, opener: HTMLElement) => void;
  registerLeaveGuard: (guard: () => boolean) => () => void;
}
const Creation = createContext<CreationContext | null>(null);
export function useNewPageFlow() { return useContext(Creation); }

export function useCreationLeaveGuard(guard: () => boolean) {
  const flow = useNewPageFlow();
  const latest = useRef(guard);
  latest.current = guard;
  const register = flow?.registerLeaveGuard;
  useEffect(() => register?.(() => latest.current()), [register]);
}

function validEntry(value: unknown, href: string): value is CreationEntry {
  if (!value || typeof value !== "object") return false;
  const entry = value as Partial<CreationEntry>;
  return entry.sourceHref === href && typeof entry.root === "string" && entry.root.length > 0
    && typeof entry.folder === "string" && typeof entry.sessionKey === "string" && entry.sessionKey.length > 0;
}
const parentFolder = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf("/")));

export function NewPageProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const location = useRouterState({ select: (state) => state.location });
  const tree = useQuery(treeQuery);
  const roots = tree.data?.roots ?? [];
  const addressedRoot = rootOfLocation(roots, location.pathname);
  const folderEntry = folderByUrl(roots, location.pathname);
  const diagram = parseDiagramPath(location.pathname);
  const splat = permalinkSplat(location.pathname);
  const permalink = splat === null ? null : parsePermalink(splat);
  let path = "";
  try { path = byPathKeyForUrl(location.pathname) ?? ""; } catch { /* Rejected route segments never become a page lookup. */ }
  // Observe the active route's existing read without issuing another request. An alias must
  // finish canonicalizing before a same-location modal entry can safely be retained.
  const byId = useQuery({ ...pageQuery(permalink?.id ?? "", permalink?.root ?? null), enabled: false });
  const byPath = useQuery({ ...pageByPathQuery(path), enabled: false });
  const page = permalink ? byId : byPath;
  const rootHome = roots.some((root) => root.tree.url === location.pathname || `${root.tree.url}/` === location.pathname);
  const needsPage = !diagram && !rootHome && (splat !== null || addressedRoot !== null && location.pathname !== "/new" && !location.pathname.startsWith("/discussions"));
  const landing = page.data ? folderForLanding(roots, page.data.root, page.data.id) : null;
  const canonical = location.search.mode === "edit" ? page.data?.url : landing?.folder.url ?? page.data?.url;
  const ready = !needsPage || (page.isSuccess && (!canonical || canonical === location.pathname))
    || (page.isError && page.error instanceof ApiError && page.error.isNotFound && folderEntry !== null);
  const root = addressedRoot ?? page.data?.root ?? primaryEntry(roots)?.root;
  const folder = folderEntry?.folder.path ?? (diagram ? parentFolder(diagram.path) : page.data ? parentFolder(page.data.path) : "");
  const marker = validEntry(location.state.pbNewPage, location.href) ? location.state.pbNewPage : null;
  const direct = location.pathname === "/new";
  const directRoot = typeof location.search.root === "string" && location.search.root ? location.search.root : primaryEntry(roots)?.root;
  const directFolder = typeof location.search.folder === "string" ? location.search.folder : "";
  const isOpen = direct || marker !== null;
  const opener = useRef<{ element: HTMLElement; href: string } | null>(null);
  const opening = useRef(false);
  const leaveGuard = useRef<(() => boolean) | null>(null);
  const registerLeaveGuard = useCallback((guard: () => boolean) => {
    leaveGuard.current = guard;
    return () => { if (leaveGuard.current === guard) leaveGuard.current = null; };
  }, []);
  useEffect(() => {
    opening.current = false;
    if (!isOpen && opener.current) {
      const previous = opener.current;
      opener.current = null;
      if (previous.href === location.href && previous.element.isConnected) previous.element.focus();
    }
  }, [isOpen, location.href, location.state]);
  function open(targetRoot: string, targetFolder: string, element: HTMLElement) {
    if (!ready || isOpen || opening.current || !rootAcceptsWrites(roots, targetRoot)) return;
    opening.current = true;
    opener.current = { element, href: location.href };
    // Commit the already-parsed location so accepted trailing slashes and query bytes
    // survive unchanged. Rebuilding it as a route would normalize its pathname.
    void router.commitLocation({ ...location, resetScroll: false,
      hashScrollIntoView: false, state: { ...location.state, pbNewPage: {
        sourceHref: location.href, root: targetRoot, folder: targetFolder, sessionKey: crypto.randomUUID(),
      } } });
    queueMicrotask(() => { opening.current = false; });
  }
  function close() {
    if (marker) router.history.back();
    else if (direct) {
      const home = directRoot ? entryFor(roots, directRoot)?.tree.url : null;
      void router.navigate({ to: home ?? "/", replace: true });
    }
  }
  return <Creation.Provider value={{ isOpen, ready, root, folder, open, registerLeaveGuard }}>
    {children}
    {isOpen && <NewPageDialog key={marker?.sessionKey ?? location.state.__TSR_key ?? "direct"}
      root={marker?.root ?? directRoot} initialFolder={marker?.folder ?? directFolder} onClose={close}
      canLeave={() => leaveGuard.current?.() ?? true} />}
  </Creation.Provider>;
}

export function NewPageLink({ root, folder = "", onClick, ...props }: Omit<ComponentProps<"a">, "href"> & { root?: string; folder?: string }) {
  const flow = useNewPageFlow();
  const params = new URLSearchParams({ ...(root ? { root } : {}), ...(folder ? { folder } : {}) });
  return <a {...props} href={`/new${params.size ? `?${params}` : ""}`} onClick={(event) => {
    onClick?.(event);
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey
      || event.currentTarget.target === "_blank" || event.currentTarget.hasAttribute("download")) return;
    if (flow && root) { event.preventDefault(); flow.open(root, folder, event.currentTarget); }
  }} />;
}
