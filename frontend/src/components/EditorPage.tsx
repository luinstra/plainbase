import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags } from "@lezer/highlight";
import { Annotation, EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState } from "react";
import { ApiError, createPage, putPageRaw, type SaveResult } from "../api/client";
import { byPathKeyForUrl, encodeTreePath, invalidateAfterWrite, pageByPathQuery, previewQuery, treeQuery } from "../api/queries";
import type { WriteConflictReason } from "../api/types";
import { frontmatterValue, splitFrontmatter } from "../lib/frontmatter";
import { checkPreviewLinks } from "../lib/editorDiagnostics";
import { linkDiagnosticGutter, setLinkDiagnostics, tableSourceLayout } from "../lib/editorExtensions";
import { insertLink, toggleBold, toggleCode, toggleItalic } from "../lib/markdownCommands";
import { permalinkOf } from "../lib/permalink";
import { breadcrumbTrail } from "../lib/breadcrumbs";
import { useDebounced } from "../lib/useDebounced";
import { EditorToolbar } from "./EditorToolbar";
import { isRootUnavailable, QueryErrorView } from "./ErrorView";
import { MetaForm } from "./MetaForm";
import { NotFoundView } from "./NotFound";
import { PageViewAction } from "./PageActions";
import { Prose } from "./Prose";
import { useCreationLeaveGuard } from "./NewPageFlow";

/**
 * The `?mode=edit` editor surface (W6, D-1/D-4): a CodeMirror 6 Markdown editor over the FULL document
 * buffer (frontmatter + body as one document), a debounced server-preview pane, and a CAS save against
 * `PUT /api/v1/pages/{id}?root={root}` with `base_hash` carried as the `If-Match` ETag. The root pin
 * is REQUIRED since C5: without it a duplicated id answers 409 `ambiguous_page_id` rather than
 * picking a root, so a save can never land on the wrong disk. Owns its OWN `useQuery` for
 * the initial buffer (component-level data-fetching — no route loader). The server is the identity
 * authority: a tampered id/slug surfaces the 422 refusal, never a silent save (D-4).
 */
export function EditorPage({ path, property }: { path: string; property?: "status" | "owner" }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const page = useQuery(pageByPathQuery(path));
  const location = useRouterState({ select: (s) => ({ pathname: s.location.pathname, searchStr: s.location.searchStr, hash: s.location.hash }) });

  // Rename-stable mid-edit (D-1): mirror the read route's canonical-redirect so an alias edit URL
  // resolves to the server-issued root-qualified `<canonical>?mode=edit` URL. The replace carries the router location's search + hash
  // (so `?mode=edit` survives the path canonicalization — the verified clincher behind the route choice).
  const resolvedFor = `/${encodeTreePath(path)}`;
  const resolved = page.data;
  useEffect(() => {
    if (!resolved || location.pathname !== resolvedFor) return;
    const canonicalUrl = resolved.url;
    if (canonicalUrl && canonicalUrl !== resolvedFor) {
      const canonicalPath = byPathKeyForUrl(canonicalUrl);
      if (canonicalPath !== null) {
        queryClient.setQueryData(pageByPathQuery(canonicalPath).queryKey, resolved);
      }
      const suffix = location.hash ? `${location.searchStr}#${location.hash}` : location.searchStr;
      router.history.replace(canonicalUrl + suffix);
    }
  }, [resolved, location, resolvedFor, router, queryClient]);

  if (page.isPending) {
    return (
      <p className="py-16 text-center text-faint" data-pb-loading>
        Loading…
      </p>
    );
  }
  if (page.isError) {
    if (page.error instanceof ApiError && (page.error.isNotFound || page.error.status === 400)) return <NotFoundView />;
    return <QueryErrorView error={page.error} />;
  }

  // Key by (root, id) so a navigation to a different page remounts the editor with a fresh
  // buffer/base_hash. The ROOT is half the identity: two roots can hold the same id, props flow without
  // a remount, and an id-only key would keep root A's buffer and CAS token while `root` had flipped to
  // B - so the save lands B's disk with A's bytes, and on byte-identical copies the CAS passes.
  return (
    <Editor
      key={`${page.data.root}:${page.data.id}`}
      id={page.data.id}
      root={page.data.root}
      initialPath={page.data.path}
      initialUrl={page.data.url}
      initialBuffer={page.data.markdown}
      initialHash={page.data.content_hash}
      title={page.data.title}
      property={property}
    />
  );
}

/** A 409 conflict the editor is showing the user — the buffer is ALWAYS preserved beside it (D-5). */
interface ConflictView {
  reason: WriteConflictReason;
  message: string;
  currentContent: string | null;
  currentPath: string | null;
}

/** The save's terminal outcome banner — at most one at a time, by construction (was four
 *  interacting useStates + clearOutcomeBanners ordering; the union deletes the masking class). */
type SaveOutcome =
  | { kind: "notice"; message: string }
  | { kind: "conflict"; conflict: ConflictView }
  | { kind: "refusal"; field: string; message: string }
  | { kind: "deleted" };

function Editor({
  id,
  root,
  initialPath,
  initialUrl,
  initialBuffer,
  initialHash,
  property,
  title,
}: {
  id: string;
  /** The page's OWN root. The preview must resolve its links against that root's space, never the primary root's. */
  root: string;
  initialPath: string;
  initialUrl: string | null;
  initialBuffer: string;
  initialHash: string;
  property?: "status" | "owner";
  title: string;
}) {
  const queryClient = useQueryClient();
  const router = useRouter();

  const [buffer, setBuffer] = useState(initialBuffer);
  // The latest buffer, readable SYNCHRONOUSLY from an event handler that fires before the next render
  // (the tag-input blur commits a new buffer, then a Save click reads it — the render hasn't flushed, so a
  // closed-over `buffer` is stale; this ref is not). EVERY buffer write goes through `commitBuffer`, which
  // updates the ref in the same tick it schedules the setState — so the ref always leads the rendered state.
  const bufferRef = useRef(initialBuffer);
  const commitBuffer = useRef((update: (prev: string) => string) => {
    bufferRef.current = update(bufferRef.current);
    setBuffer(bufferRef.current);
  }).current;
  // The CAS token. ALWAYS server-issued — the initial GET, or a 200/409 response — never recomputed.
  const [baseHash, setBaseHash] = useState(initialHash);
  // The page's live content-relative path. page_moved updates it so the editor never drifts (D-5).
  const [docPath, setDocPath] = useState(initialPath);
  // The LAST-SAVED buffer — the dirty baseline. It starts as the GET payload and advances on every
  // successful save, so a saved buffer reads clean (Save disabled, no redundant PUT) until the user
  // edits again. Tracked separately from `baseHash` (the CAS token), which advances independently.
  const [savedBuffer, setSavedBuffer] = useState(initialBuffer);
  const [savedHash, setSavedHash] = useState(initialHash);
  const savePending = useRef(false);
  const editorRoot = useRef<HTMLDivElement>(null);
  const [metadataReset, setMetadataReset] = useState(0);
  const [metadataDraft, setMetadataDraft] = useState(false);
  const dirty = buffer !== savedBuffer;

  const [outcome, setOutcome] = useState<SaveOutcome | null>(null);
  // Keeping the source mounted preserves its selection and undo while Preview covers it.
  const [showPreview, setShowPreview] = useState(false);
  // The live body `EditorView`, lifted out of `CodeMirrorEditor` (private there) so the formatting
  // toolbar can run commands against it (D-3). A callback prop (not a forwarded ref) so this `useState`
  // re-renders the toolbar the moment the view mounts — and re-fires with the fresh view on a key-remount.
  const [editorView, setEditorView] = useState<EditorView | null>(null);

  // Byte-fidelity guard (W6): the read path serves `markdown` as a lossy UTF-8 decode of the
  // SAME bytes it hashes into `content_hash` (server IndexBuilder: `String(bytes, UTF_8)` +
  // `sha256(bytes)`, no NFC/EOL transform on content). So for a valid-UTF-8 page the identity
  // `sha256(utf8(markdown)) == content_hash` holds exactly; a mismatch means the seeded text is
  // NOT a faithful view of the on-disk bytes (invalid UTF-8 → U+FFFD), and re-encoding it on save
  // would silently corrupt bytes the user never touched. A text editor can't byte-faithfully
  // round-trip non-UTF-8, so we DETECT and refuse to save (reading is never blocked).
  const editable = useEditableGuard(initialBuffer, initialHash);

  const debounced = useDebounced(buffer, 300);
  // Diagnostics and Preview share the server's root-scoped link resolver and one request.
  const previewOptions = previewQuery(debounced, docPath, root);
  const preview = useQuery(previewOptions);
  const previewCurrent = buffer === debounced && preview.isSuccess;
  const check = useMemo(() => previewCurrent ? checkPreviewLinks(buffer, preview.data.html) : { markers: [], unmapped: false }, [buffer, previewCurrent, preview.data]);
  const diagnostics = check.markers;
  const linkStatus = buffer !== debounced || preview.isFetching ? "Checking links…"
    : preview.isError ? "Link checks unavailable" : check.unmapped ? "Some broken links could not be located" : diagnostics.length ? `${diagnostics.length} ${diagnostics.length === 1 ? "line needs" : "lines need"} attention` : "No broken links";
  useEffect(() => {
    editorView?.dispatch({ effects: setLinkDiagnostics.of(diagnostics) });
  }, [editorView, diagnostics]);

  // Split-view (C2/D-3): the body CodeMirror holds the BODY SLICE only — the `---` fence and metadata
  // lines never enter the CM doc, so the body editor shows prose only and the metadata form owns the
  // frontmatter region. `body` is the exact tail `buffer.slice(bodyStart)`, so the frontmatter prefix is
  // the head before it; a body edit recombines by splicing the body region (preserving the frontmatter
  // region byte-for-byte). A FORM edit leaves `body` unchanged → CodeMirror's reconcile guard short-
  // circuits → the body view/cursor/undo are untouched for free.
  const { body } = splitFrontmatter(buffer);
  // Recombine via a FUNCTIONAL updater over the LATEST buffer (not the render-scope `buffer`/`body`): a
  // body edit and a form edit can land in the same React batch, so re-derive the frontmatter prefix from
  // `prev` each time rather than the stale closed-over slice.
  const recombineBody = (nextBody: string) => commitBuffer((prev) => prev.slice(0, prev.length - splitFrontmatter(prev).body.length) + nextBody);

  const save = useMutation({
    // Read the LATEST buffer (bufferRef), not the render-scope `buffer`: a tag-input blur commits its draft
    // via setBuffer and a Save click can fire before that re-render, so the closed-over `buffer` would be
    // stale (the just-typed tag lost, the editor left dirty). The ref always leads the rendered state.
    // Capture the exact sent bytes so the saved baseline advances to them on success (even mid-request).
    mutationFn: (): Promise<{ result: SaveResult; sent: string }> => {
      const sent = bufferRef.current;
      return putPageRaw(id, root, sent, baseHash).then((result) => ({ result, sent }));
    },
    onSuccess: ({ result, sent }) => applySaveResult(result, sent),
    onSettled: () => { savePending.current = false; },
  });

  function applySaveResult(result: SaveResult, sent: string) {
    switch (result.kind) {
      case "saved": {
        setBaseHash(result.written.content_hash);
        // Advance the dirty baseline to the saved bytes — the editor reads clean (Save disabled, no
        // redundant PUT) until the user edits again.
        setSavedBuffer(sent);
        setSavedHash(result.written.content_hash);
        setOutcome({ kind: "notice", message: "warning" in result.written ? result.written.warning.message : "Saved." });
        // ONE invalidation point (queries.ts): tree, search (full-text goes stale on any edit), this page's
        // id-keyed + by-path reads. The by-path leg uses the mounted URL splat (NOT the `.md` file path); a
        // page_moved changes that key and the 200 doesn't carry the new URL, so the helper also clears the
        // whole by-path namespace to leave neither the old nor the new location stale.
        invalidateAfterWrite(queryClient, { id, url: initialUrl });
        return;
      }
      case "conflict": {
        const { reason } = result.conflict;
        // A deliberate re-save targets the new base — the hash is ALWAYS the server's `current_hash`.
        if (result.conflict.current_hash) setBaseHash(result.conflict.current_hash);
        if (reason === "page_deleted") {
          setOutcome({ kind: "deleted" });
          return;
        }
        if (reason === "page_moved" && result.conflict.current_path) setDocPath(result.conflict.current_path);
        setOutcome({
          kind: "conflict",
          conflict: {
            reason,
            message: result.conflict.message,
            currentContent: result.conflict.current_content,
            currentPath: result.conflict.current_path,
          },
        });
        return;
      }
      case "degraded":
        // P5: an agent COMMIT write outside `agentDirectCommit.globs` was filed as a proposal, NOT applied.
        // Do NOT advance the saved baseline (the editor stays dirty — these bytes are not on disk) and surface a
        // clear non-"Saved" notice. Unreachable from the Human/cookie-auth SPA, but the result kind is exhaustive.
        setOutcome({ kind: "notice", message: "Submitted as a proposal for review." });
        return;
      case "unsupported":
        setOutcome({ kind: "refusal", field: result.unsupported.field, message: result.unsupported.message });
        return;
      case "too-large":
        setOutcome({ kind: "notice", message: `Document exceeds ${result.maxBytes} bytes — trim it and try again.` });
        return;
      case "error": {
        // A 503 is retryable when it is a transient FS fault (`content_unreadable`, `written_but_unindexed`), and NOT
        // when the ROOT is not serving: that one lasts until an operator restores the path AND restarts, so "please
        // retry" would send the author looping against a disk that is not coming back on its own. The outage
        // envelope's own message names the root and the remedy, so it is the one to show.
        const transient = result.error.status === 503 && !isRootUnavailable(result.error);
        setOutcome({ kind: "notice", message: transient ? "Couldn't save (transient) — please retry." : result.error.message });
        return;
      }
    }
  }

  useCreationLeaveGuard(() => !save.isPending && (bufferRef.current === savedBuffer
    || window.confirm("Discard unsaved changes and create another page?")));

  function viewPage() {
    if (save.isPending) return;
    // Metadata inputs can commit on blur immediately before this click. Read the live
    // buffer, as Save does, so that a just-entered value is included in the discard guard.
    if (bufferRef.current !== savedBuffer && !window.confirm("Discard unsaved changes and view this page?")) return;
    router.history.push(initialUrl ?? permalinkOf(root, id));
  }

  function flushMetadata() {
    const active = document.activeElement;
    if (active instanceof HTMLElement && active.closest("[data-pb-meta-form]")) active.blur();
  }
  function requestSave() {
    flushMetadata();
    if (savePending.current || save.isPending || !editable || bufferRef.current === savedBuffer) return;
    savePending.current = true;
    save.mutate();
  }
  function discard() {
    flushMetadata();
    if (savePending.current || save.isPending || bufferRef.current === savedBuffer) return;
    if (!window.confirm("Discard unsaved changes?")) return;
    commitBuffer(() => savedBuffer);
    setBaseHash(savedHash);
    setOutcome(null);
    setMetadataReset((value) => value + 1);
    setMetadataDraft(false);
  }

  return (
    <div ref={editorRoot} className="pb-editor flex min-w-0 flex-1" data-pb-editor onKeyDownCapture={(event) => {
      if ((event.metaKey || event.ctrlKey) && !event.altKey && event.key.toLowerCase() === "s"
        && !(event.target as HTMLElement).closest("dialog")) {
        event.preventDefault(); event.stopPropagation(); requestSave();
      }
    }}>
      <div className="flex min-w-0 flex-1 flex-col gap-3">
        <div className="pb-editor-bar flex items-center justify-between gap-3">
          <Breadcrumb root={root} path={docPath} title={title} />
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted" role="status" aria-label="Save state" aria-live="polite" data-pb-save-state>{save.isPending ? "Saving" : dirty || metadataDraft ? "Unsaved changes" : "Saved"}</span>
            <div className="pb-editor-modes" role="group" aria-label="Editor mode">
            <button type="button" aria-pressed={!showPreview} onClick={() => { setShowPreview(false); requestAnimationFrame(() => editorView?.focus()); }}>Write</button>
            <button
              type="button"
              data-pb-preview-toggle
              aria-pressed={showPreview}
              onClick={() => setShowPreview(true)}
            >
              Preview
            </button>
            </div>
            <button type="button" className="pb-editor-discard text-sm text-muted" disabled={save.isPending || (!dirty && !metadataDraft)} onClick={discard}>Discard</button>
            <button
              type="button"
              className="pb-editor-save rounded-md border border-primary-edge bg-primary px-3 py-1.5 text-sm font-medium text-primary-ink"
              data-pb-save
              disabled={save.isPending || (!dirty && !metadataDraft) || !editable}
              onClick={requestSave}
            >
              {save.isPending ? "Saving…" : "Save"}<kbd className="ml-2 font-mono text-xs" aria-hidden="true" data-pb-save-hint>⌘S</kbd>
            </button>
            <div role="group" aria-label="Finish editing" className="ml-2 border-l border-edge pl-3">
              <PageViewAction onView={viewPage} disabled={save.isPending} />
            </div>
          </div>
        </div>

        <MetaForm key={metadataReset} buffer={buffer} onChange={commitBuffer} focusProperty={property} onDraftChange={setMetadataDraft} />

        {!editable && <UneditableBanner />}
        {outcome?.kind === "conflict" && <ConflictBanner conflict={outcome.conflict} />}
        {/* The narrowed outcome is a SUPERSET of the prop's {field, message} — legal only because TS
            skips excess-property checks on non-literal args; don't assume the prop type is exact. */}
        {outcome?.kind === "refusal" && <RefusalBanner refusal={outcome} />}
        {outcome?.kind === "deleted" && <DeletedBanner buffer={buffer} root={root} initialPath={initialPath} />}
        {outcome?.kind === "notice" && (
          <p className="text-sm text-muted" data-pb-editor-notice>
            {outcome.message}
          </p>
        )}

        <EditorToolbar view={editorView} disabled={showPreview} />
        <p className="pb-editor-link-status text-xs text-muted" role="status" aria-label="Link checks" aria-live="polite">{linkStatus}</p>

        {/* The CodeMirror region is the positioning context for the preview overlay: CM stays mounted
            (preserving cursor/scroll/undo) and the preview, when shown, covers it with an opaque surface. */}
        <div className="relative min-h-0 flex-1">
          <div inert={showPreview}><CodeMirrorEditor value={body} onChange={recombineBody} onViewChange={setEditorView} /></div>
          {showPreview && (
            <div className="absolute inset-0 overflow-y-auto rounded-md bg-surface" data-pb-preview>
              {previewCurrent ? <Prose html={preview.data.html} /> : <p className="text-sm text-muted">{preview.isError && buffer === debounced ? "Preview unavailable. Your draft is kept." : buffer.length ? "Preparing preview…" : "Your page is empty."}</p>}
            </div>
          )}
        </div>
      </div>

    </div>
  );
}

/**
 * The header path as a breadcrumb: dimmed parent folder segments + ` / ` separators + the bright filename
 * (the last segment). Derived from the live content-relative `docPath` (e.g. `infra/kubernetes.md` →
 * `infra / kubernetes.md`). Monospace, matching the C2 bare-path look; `data-pb-editor-path` is preserved
 * as the stable hook (now wrapping the breadcrumb rather than the bare string).
 */
function Breadcrumb({ root, path, title }: { root: string; path: string; title: string }) {
  const tree = useQuery(treeQuery);
  const folders = breadcrumbTrail(tree.data?.roots ?? [], root, path);
  return (
    <span className="flex min-w-0 items-center text-sm" data-pb-editor-path title={`${root}/${path}`}>
      {folders.map((folder, index) => (
        <span key={index} className="flex items-center text-muted">
          {folder.label}
          <span className="px-1.5 text-faint" aria-hidden="true">
            /
          </span>
        </span>
      ))}
      <span className="truncate text-ink">{title}</span>
    </span>
  );
}

/** content_changed / page_moved — the dirty buffer is kept; the server's current content is shown alongside (no auto-merge). */
function ConflictBanner({ conflict }: { conflict: ConflictView }) {
  return (
    <div className="pb-conflict rounded-md border border-edge p-3 text-sm" data-pb-conflict data-pb-conflict-reason={conflict.reason}>
      <p className="font-medium text-ink">{conflict.message}</p>
      <p className="mt-1 text-muted">Your edits are kept. Review the current server version below, then Save again to overwrite it.</p>
      {conflict.reason === "page_moved" && conflict.currentPath && (
        <p className="mt-1 text-muted">This page moved to {conflict.currentPath} — you are now editing it there.</p>
      )}
      {conflict.currentContent !== null && (
        <details className="mt-2" data-pb-conflict-current>
          <summary className="cursor-pointer text-muted">Current server version</summary>
          <pre className="mt-2 overflow-x-auto whitespace-pre-wrap font-mono text-xs text-muted">{conflict.currentContent}</pre>
        </details>
      )}
    </div>
  );
}

/** 422 id/slug/redirect_from — a rename, not a save; the editor stays dirty/unsaved (D-4). */
function RefusalBanner({ refusal }: { refusal: { field: string; message: string } }) {
  return (
    <div className="pb-refusal rounded-md border border-edge p-3 text-sm" data-pb-refusal data-pb-refusal-field={refusal.field}>
      <p className="font-medium text-ink">Changing the page {refusal.field} isn’t a save — it’s a move, which isn’t supported yet.</p>
      <p className="mt-1 text-muted">{refusal.message}</p>
    </div>
  );
}

/**
 * Byte-fidelity refusal: the page's on-disk bytes aren't valid UTF-8, so the seeded text is a lossy
 * decode (U+FFFD) and saving would re-encode it — corrupting bytes the user never edited. Reading is
 * fine; Save is disabled. The fix is to edit the file with a byte-faithful tool, externally.
 */
function UneditableBanner() {
  return (
    <div className="pb-uneditable rounded-md border border-edge p-3 text-sm" data-pb-uneditable>
      <p className="font-medium text-ink">This page isn’t valid UTF-8 and can’t be safely edited here.</p>
      <p className="mt-1 text-muted">Saving would change bytes you never touched. Edit it externally with a byte-faithful tool.</p>
    </div>
  );
}

/**
 * Recomputes `sha256(utf8(buffer))` via Web Crypto and compares it to the server's `content_hash`
 * (sans the `sha256:` prefix). Returns `true` (editable) until a mismatch is CONFIRMED — so a normal
 * page, and the brief async window before the digest resolves, never block the user. A digest failure
 * (no `crypto.subtle`) also leaves the page editable rather than false-blocking a valid one.
 */
function useEditableGuard(buffer: string, contentHash: string): boolean {
  const [editable, setEditable] = useState(true);
  useEffect(() => {
    let live = true;
    sha256Hex(buffer)
      .then((hex) => {
        if (live) setEditable(hex === null || hex === stripHashPrefix(contentHash));
      })
      .catch(() => {
        if (live) setEditable(true);
      });
    return () => {
      live = false;
    };
  }, [buffer, contentHash]);
  return editable;
}

/** Lowercase-hex SHA-256 of the UTF-8 bytes of [text], or null when Web Crypto is unavailable. */
async function sha256Hex(text: string): Promise<string | null> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  const digest = await subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

const stripHashPrefix = (hash: string): string => (hash.startsWith("sha256:") ? hash.slice("sha256:".length) : hash);

/** page_deleted — no rebase target; offer "save as new page" prefilled with the buffer (no dead-end, D-5). */
function DeletedBanner({ buffer, root, initialPath }: { buffer: string; root: string; initialPath: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const folder = initialPath.includes("/") ? initialPath.slice(0, initialPath.lastIndexOf("/")) : "";
  const fallbackTitle = stripExtension(initialPath.slice(initialPath.lastIndexOf("/") + 1)) || "Untitled";

  async function saveAsNew() {
    setSaving(true);
    setError(null);
    // The server PREPENDS its own freshly-minted frontmatter and appends `body` verbatim. Send the BODY
    // ONLY (the old frontmatter — incl. the now-defunct id — must not survive, or the file gets two
    // frontmatter blocks). The title comes from the user's possibly-edited frontmatter, else the filename.
    const { frontmatter, body } = splitFrontmatter(buffer);
    const title = (frontmatter && frontmatterValue(frontmatter, "title")) || fallbackTitle;
    // The recovered page is re-created in the root it was deleted FROM. The wire root is required (an omitted
    // one is a 400 `invalid_root`, never a silent primary-root write), and a rescue path is the last place to fumble the
    // user's tree - so it is threaded from the page, never re-derived.
    const result = await createPage({ root, folder, title, body });
    setSaving(false);
    if (result.kind === "created") {
      // Invalidate the DESTINATION url's by-path/page cache BEFORE navigating — save-as-new can reuse a
      // recovered root-qualified URL whose by-path entry still points at the deleted old id, so the read route
      // would otherwise render that stale id for up to its staleTime. (A permalink url no-ops: it is not
      // a root landing address, so it has no by-path key. The id leg still clears every root spelling.)
      invalidateAfterWrite(queryClient, { id: result.created.id, url: result.created.url });
      if (result.created.warning || !result.created.url) {
        // Unindexed (or, defensively, no canonical url yet): the page is unpublished, so navigating
        // could land on a not-yet-resolvable route. Surface the warning and stay put.
        setError((result.created.warning ?? { message: "Saved, but not yet indexed." }).message);
        return;
      }
      await router.navigate({ to: result.created.url });
      return;
    }
    if (result.kind === "degraded") {
      // P5: the create degraded to a proposal (agent write outside the direct-commit globs) — nothing landed
      // on disk to navigate to. Unreachable from the Human/cookie-auth SPA, but the kind is exhaustive.
      setError("Submitted as a proposal for review.");
      return;
    }
    setError(result.kind === "exists" ? `A page already exists at ${result.exists.path}.` : result.error.message);
  }

  return (
    <div className="pb-conflict rounded-md border border-edge p-3 text-sm" data-pb-conflict data-pb-conflict-reason="page_deleted">
      <p className="font-medium text-ink">This page no longer exists on disk.</p>
      <p className="mt-1 text-muted">Your edits are kept. Save them as a new page so nothing is lost.</p>
      <button
        type="button"
        className="pb-editor-save mt-2 rounded-md border border-primary-edge bg-primary px-3 py-1.5 text-sm font-medium text-primary-ink disabled:opacity-50"
        data-pb-save-as-new
        disabled={saving}
        onClick={() => void saveAsNew()}
      >
        {saving ? "Saving…" : "Save as new page"}
      </button>
      {error && <p className="mt-1 text-muted">{error}</p>}
    </div>
  );
}

/** The §5.9-token Markdown highlight style — only `var(--pb-*)` references, so dark mode swaps for free. */
const pbHighlightStyle = HighlightStyle.define([
  { tag: tags.heading, color: "var(--pb-syntax-title)", fontWeight: "bold" },
  { tag: tags.strong, color: "var(--pb-text)", fontWeight: "bold" },
  { tag: tags.emphasis, color: "var(--pb-text)", fontStyle: "italic" },
  { tag: tags.link, color: "var(--pb-link)" },
  { tag: tags.url, color: "var(--pb-link)" },
  { tag: tags.monospace, color: "var(--pb-code-text)" },
  { tag: tags.keyword, color: "var(--pb-syntax-keyword)" },
  { tag: tags.string, color: "var(--pb-syntax-string)" },
  { tag: tags.comment, color: "var(--pb-syntax-comment)" },
  { tag: tags.meta, color: "var(--pb-text-muted)" },
  { tag: tags.processingInstruction, color: "var(--pb-text-faint)" },
]);

/** Editor chrome theme — all colors are `var(--pb-*)` references (placed here, never as hex), per the token gate. */
const pbEditorTheme = EditorView.theme({
  "&": { color: "var(--pb-text)", backgroundColor: "var(--pb-surface)" },
  ".cm-content": { fontFamily: "var(--font-mono)", caretColor: "var(--pb-accent)" },
  ".cm-activeLine": { backgroundColor: "var(--pb-surface-raised)" },
  "&.cm-focused": { outline: "none" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--pb-accent)" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": { backgroundColor: "var(--pb-selection-bg)" },
  ".cm-content ::selection": { backgroundColor: "var(--pb-selection-bg)", color: "var(--pb-selection-text)" },
  ".cm-scroller": { fontFamily: "var(--font-mono)" },
  ".cm-gutters": { backgroundColor: "var(--pb-surface)", color: "var(--pb-text-faint)", borderRight: "1px solid var(--pb-border)" },
  ".cm-lineNumbers .cm-gutterElement": { padding: "0 8px" },
});

/**
 * The C3 formatting keymap (D-2). PREPENDED before `defaultKeymap` in the extensions array so CM6's
 * `runFor` reaches these first: `Mod-i` IS bound by default to `selectParentSyntax`, so the prepend plus
 * `toggleItalic` returning `true` whenever it acts is what stops the default from clobbering italic.
 * Link precedes the default Shift-Mod-k delete-line binding. `Mod-` resolves to Cmd on macOS, Ctrl elsewhere.
 */
const formattingKeymap = keymap.of([
  { key: "Mod-b", run: toggleBold },
  { key: "Mod-i", run: toggleItalic },
  { key: "Mod-e", run: toggleCode },
  { key: "Mod-Shift-k", run: insertLink },
]);

/** Mounts a CodeMirror 6 Markdown EditorView over a ref; the React state is the source of truth for the buffer. */
const externalReconcile = Annotation.define<boolean>();

function CodeMirrorEditor({
  value,
  onChange,
  onViewChange,
}: {
  value: string;
  onChange: (next: string) => void;
  onViewChange?: (view: EditorView | null) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  // The latest onChange, read inside the (mount-once) update listener without re-creating the view.
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  // The latest onViewChange, lifted the same way so the mount-once effect never re-runs on a new callback.
  const onViewChangeRef = useRef(onViewChange);
  onViewChangeRef.current = onViewChange;

  useEffect(() => {
    if (!host.current) return;
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          history(),
          formattingKeymap,
          keymap.of([...defaultKeymap, ...historyKeymap]),
          markdown({ base: markdownLanguage }),
          lineNumbers(),
          linkDiagnosticGutter,
          tableSourceLayout,
          syntaxHighlighting(pbHighlightStyle),
          pbEditorTheme,
          EditorView.lineWrapping,
          EditorView.updateListener.of((update) => {
            if (update.docChanged && !update.transactions.some((transaction) => transaction.annotation(externalReconcile))) {
              onChangeRef.current(update.state.doc.toString());
            }
          }),
        ],
      }),
    });
    // CodeMirror hides its decorative gutters; link marks are interactive diagnostics.
    editor.dom.querySelector(".pb-editor-link-gutter")?.parentElement?.removeAttribute("aria-hidden");
    editor.dom.querySelector(".cm-lineNumbers")?.setAttribute("aria-hidden", "true");
    view.current = editor;
    onViewChangeRef.current?.(editor);
    return () => {
      onViewChangeRef.current?.(null);
      editor.destroy();
      view.current = null;
    };
    // Mount once; external value pushes are reconciled by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reconcile an EXTERNAL value change (e.g. a programmatic reset) without clobbering local typing.
  useEffect(() => {
    const editor = view.current;
    if (editor && !editor.state.doc.eq(editor.state.toText(value))) {
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value }, annotations: externalReconcile.of(true) });
    }
  }, [value]);

  return <div ref={host} className="pb-codemirror min-h-[60vh] rounded-md border border-edge" data-pb-codemirror />;
}

function stripExtension(name: string): string {
  return name.endsWith(".md") ? name.slice(0, -3) : name;
}
