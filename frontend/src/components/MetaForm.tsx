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

  // The status pill's leading dot maps to the chunk-1 status colors (`app.css` `.pb-chip[data-pb-chip-status]`);
  // a value outside the known five carries no `data-pb-chip-status`, so the dot/pill fall back to the neutral chip.
  const knownStatus = status && KNOWN_STATUSES.includes(status as (typeof KNOWN_STATUSES)[number]) ? status : undefined;

  return (
    <div className="pb-rail-card" data-pb-meta-form>
      <div className="pb-rail-head">Page info</div>
      <div className="pb-meta">
        <FieldRow label="Status">
          {/* A styled pill wraps the native <select>: the dot + chip framing are CSS, the value-write
              behavior (incl. the unknown-value-stays-selectable handling) is untouched. */}
          <span className="pb-chip pb-status-pill" data-pb-chip-status={knownStatus}>
            <span className="pb-chip-dot" data-pb-status-dot aria-hidden="true" />
            <select
              className="pb-status-select"
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
          </span>
        </FieldRow>

        <FieldRow label="Tags">
          <TagEditor tags={tags} onChange={(next) => onChange((prev) => setFrontmatterList(prev, "tags", next))} />
        </FieldRow>

        <FieldRow label="Owner">
          {/* A circular initials avatar (1-2 uppercased letters; blank placeholder when empty) beside the input. */}
          <span className="pb-avatar" data-pb-owner-avatar aria-hidden="true">
            {ownerInitials(owner)}
          </span>
          <input
            type="text"
            className="pb-meta-input flex-1 rounded-md border border-edge bg-surface px-2 py-1 text-ink"
            data-pb-field-owner
            value={owner ?? ""}
            onChange={(event) => setScalar("owner", event.target.value)}
            placeholder="Owner"
          />
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

        <FieldRow label="Review by">
          <input
            // Reads frontmatter `review`; the same non-ISO `type=text` fallback as Updated (C2).
            type={review && !isIsoDate(review) ? "text" : "date"}
            className="pb-meta-input rounded-md border border-edge bg-surface px-2 py-1 text-ink"
            data-pb-field-review
            value={review ?? ""}
            onChange={(event) => setScalar("review", event.target.value)}
          />
        </FieldRow>
      </div>
    </div>
  );
}

/** 1-2 uppercased initials from an owner value (first letters of the first two whitespace-split words); "" when blank. */
function ownerInitials(owner: string | null): string {
  const words = (owner ?? "").trim().split(/\s+/).filter(Boolean);
  return words
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join("");
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
        // The leading `#` is supplied by `.pb-tag::before` (chunk-4) — never hardcode it in the text.
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
      {/* A dashed `+ add` control: a type-then-commit input (Enter/blur), styled as the mockup's add affordance. */}
      <input
        type="text"
        className="pb-tag-add"
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
        placeholder="+ add"
        aria-label="Add tag"
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
