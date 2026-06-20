import {
  frontmatterList,
  frontmatterValue,
  removeFrontmatterKey,
  setFrontmatterList,
  setFrontmatterValue,
  splitFrontmatter,
} from "../lib/frontmatter";
import { useState } from "react";

/**
 * The `?mode=edit` metadata form (C2/D-4): a structured "Page info" editor in the editor rail that
 * surgically patches the FRONTMATTER REGION of the editor buffer, so the body CodeMirror shows prose only.
 *
 * It is a CONTROLLED surface over `buffer` — the single source of truth (D-1). Every field reads its value
 * FRESH from `splitFrontmatter(buffer)` each render (no internal field state that could drift) for DISPLAY,
 * but every WRITE composes over the LATEST buffer via a FUNCTIONAL updater (`onChange((prev) => …)`), never
 * the render-scope `buffer` prop — so two field edits in one React batch can't clobber each other (the
 * stale-closure data-loss class). The next buffer is computed by the surgical write primitives
 * (`setFrontmatterValue`/`removeFrontmatterKey`/`setFrontmatterList`, the client analogue of the server
 * `FrontmatterPatcher`). A form edit is therefore an ordinary `setBuffer` → it marks the editor dirty
 * exactly as a body edit does, and rides the unchanged save/CAS/conflict path. No policy field, no auth (D-7).
 *
 * The read-mode `DocRail` (`PageView.tsx`) stays a SEPARATE read-only surface with distinct `data-pb-*`
 * hooks; this is the edit-mode twin, reusing the same `.pb-rail*`/`.pb-meta*`/`.pb-chip`/`.pb-tag` structure.
 */

/** The five known statuses (mirrors `app.css` `.pb-chip[data-pb-chip-status]`); a value outside it still renders. */
const KNOWN_STATUSES = ["active", "draft", "review", "archived", "deprecated"] as const;

/**
 * True when [value] is a real `yyyy-mm-dd` calendar date `<input type="date">` round-trips (so it won't
 * render blank). A CALENDAR round-trip, not `Date.parse` — V8 leniently accepts impossible ISO-shaped
 * dates (`2026-02-30`) which the DOM would then silently mask to blank; mirror the server's strict
 * `LocalDate.parse` by requiring the parsed UTC components to equal the input.
 */
function isIsoDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const [, y, m, d] = match;
  const date = new Date(`${value}T00:00:00Z`);
  return date.getUTCFullYear() === Number(y) && date.getUTCMonth() + 1 === Number(m) && date.getUTCDate() === Number(d);
}

/** A functional buffer updater — every form write composes over the LATEST buffer, never a stale prop. */
type BufferUpdater = (update: (prev: string) => string) => void;

export function MetaForm({ buffer, onChange }: { buffer: string; onChange: BufferUpdater }) {
  const { frontmatter } = splitFrontmatter(buffer);
  const fm = frontmatter ?? "";
  const status = frontmatterValue(fm, "status");
  const updated = frontmatterValue(fm, "updated");
  const owner = frontmatterValue(fm, "owner");
  const review = frontmatterValue(fm, "review");
  const tags = frontmatterList(buffer, "tags");

  // WRITES compose over `prev` (the latest buffer), never the render-scope `buffer` — so a blur+save or two
  // rapid field edits in the same batch can't lose each other's change (stale-closure data-loss).
  const setScalar = (key: string, value: string) =>
    onChange((prev) => (value === "" ? removeFrontmatterKey(prev, key) : setFrontmatterValue(prev, key, value)));

  // A hand-edited file may carry a status outside the known set — keep it selectable so a save never
  // silently drops it (the round-trip discipline), while the dropdown still offers the canonical five.
  const statusOptions = status && !KNOWN_STATUSES.includes(status as (typeof KNOWN_STATUSES)[number]) ? [status, ...KNOWN_STATUSES] : KNOWN_STATUSES;

  return (
    <div className="pb-rail-card" data-pb-meta-form>
      <div className="pb-rail-head">Page info</div>
      <div className="pb-meta">
        <FieldRow label="Status">
          <select
            className="pb-meta-input rounded-md border border-edge bg-surface px-2 py-1 text-ink"
            data-pb-field-status
            value={status ?? ""}
            onChange={(event) => setScalar("status", event.target.value)}
          >
            <option value="">(none)</option>
            {statusOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </FieldRow>

        <FieldRow label="Tags">
          <TagEditor tags={tags} onChange={(next) => onChange((prev) => setFrontmatterList(prev, "tags", next))} />
        </FieldRow>

        <FieldRow label="Updated">
          <input
            // A non-ISO `updated` (a hand-authored `2026 Q1`, a prose date) would render BLANK under
            // `type="date"` and hide the user's real value → a save could silently drop it. Fall back to a
            // text input when the value isn't an `yyyy-mm-dd` date the browser's date picker accepts.
            type={updated && !isIsoDate(updated) ? "text" : "date"}
            className="pb-meta-input rounded-md border border-edge bg-surface px-2 py-1 text-ink"
            data-pb-field-updated
            value={updated ?? ""}
            onChange={(event) => setScalar("updated", event.target.value)}
          />
        </FieldRow>

        <FieldRow label="Owner">
          <input
            type="text"
            className="pb-meta-input w-full rounded-md border border-edge bg-surface px-2 py-1 text-ink"
            data-pb-field-owner
            value={owner ?? ""}
            onChange={(event) => setScalar("owner", event.target.value)}
            placeholder="Owner"
          />
        </FieldRow>

        <FieldRow label="Review">
          <input
            type="text"
            className="pb-meta-input w-full rounded-md border border-edge bg-surface px-2 py-1 text-ink"
            data-pb-field-review
            value={review ?? ""}
            onChange={(event) => setScalar("review", event.target.value)}
            placeholder="Review date"
          />
        </FieldRow>
      </div>
    </div>
  );
}

/** A chip editor over a string list: existing tags as removable `.pb-tag` chips + an input to add one. */
function TagEditor({ tags, onChange }: { tags: string[]; onChange: (next: string[]) => void }) {
  const [draft, setDraft] = useState("");

  function add() {
    const value = draft.trim();
    if (value === "" || tags.includes(value)) {
      setDraft("");
      return;
    }
    onChange([...tags, value]);
    setDraft("");
  }

  return (
    <div className="pb-meta-tags flex flex-wrap items-center gap-2" data-pb-field-tags>
      {tags.map((tag, index) => (
        // A hand-authored block list can carry duplicate tags; index-qualify the key so React doesn't warn
        // (and so removing one of a duplicate pair doesn't reconcile the wrong chip).
        <span key={`${tag}-${index}`} className="pb-tag inline-flex items-center gap-1">
          {tag}
          <button
            type="button"
            className="pb-tag-remove"
            data-pb-tag-remove={tag}
            aria-label={`Remove tag ${tag}`}
            onClick={() => onChange(tags.filter((_, i) => i !== index))}
          >
            ×
          </button>
        </span>
      ))}
      <input
        type="text"
        className="pb-meta-input rounded-md border border-edge bg-surface px-2 py-1 text-ink"
        data-pb-tag-add
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            add();
          }
        }}
        onBlur={add}
        placeholder="Add tag"
      />
    </div>
  );
}

/** A metadata row mirroring `DocRail`'s `MetaRow` structure (`.pb-meta-row`/`.pb-meta-key`/`.pb-meta-val`). */
function FieldRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="pb-meta-row">
      <span className="pb-meta-key">{label}</span>
      <span className="pb-meta-val">{children}</span>
    </div>
  );
}
