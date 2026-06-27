import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { changesQuery } from "../api/queries";
import type { ChangeSummary } from "../api/types";
import { formatTime } from "../lib/datetime";

/**
 * P4 review queue (WI-4): the list of proposed changes. `GET /api/v1/changes` returns ALL statuses — the server
 * decides nothing about presentation (F1) — so PENDING items sort FIRST and carry the actionable affordance.
 *
 * F8 (no client capability signal): the queue renders for ANY authenticated reader (list/get need only
 * `checkRead`); there is no hard approve gate here. The chrome "Review" nav that links here is gated on
 * `session.authenticated` (Shell); an approve/reject/rebase 403 becomes the no-access state in the DETAIL view.
 */
export function ReviewQueue() {
  const changes = useQuery(changesQuery);

  if (changes.isPending) {
    return (
      <p className="py-16 text-center text-faint" data-pb-loading>
        Loading…
      </p>
    );
  }
  if (changes.isError) {
    return (
      <div className="py-16 text-center" data-pb-error>
        <h1 className="text-2xl font-bold text-ink">Something went wrong</h1>
        <p className="mt-3 text-muted">{changes.error.message}</p>
      </div>
    );
  }

  // Stable sort (PENDING first), preserving the server's order within each group.
  const proposals = [...changes.data.proposals].sort((a, b) => rank(a) - rank(b));

  return (
    <div className="pb-review" data-pb-review-queue>
      <h1 className="pb-review-title text-2xl font-bold text-ink">Review queue</h1>
      {proposals.length === 0 ? (
        <p className="pb-review-empty py-16 text-center text-muted" data-pb-review-empty>
          No proposed changes.
        </p>
      ) : (
        <ol className="pb-review-list" data-pb-review-list>
          {proposals.map((change) => (
            <ReviewRow key={change.id} change={change} />
          ))}
        </ol>
      )}
    </div>
  );
}

/** PENDING (actionable) sorts before every terminal/in-flight status; ties keep the server order. */
function rank(change: ChangeSummary): number {
  return change.status === "PENDING" ? 0 : 1;
}

function ReviewRow({ change }: { change: ChangeSummary }) {
  // Two drift signals collapse to one chip (F3): a LIVE-drifted PENDING or a post-failed-apply CONFLICTED.
  const drifted = change.base_drifted || change.status === "CONFLICTED";
  return (
    <li
      className="pb-review-row"
      data-pb-review-row
      data-pb-review-row-status={change.status}
      data-pb-review-row-drifted={drifted ? "" : undefined}
    >
      <Link to="/review/$id" params={{ id: change.id }} className="pb-review-row-link" data-pb-review-row-link>
        <span className="pb-review-row-head">
          <span className="pb-review-row-op" data-pb-review-row-op={change.operation}>
            {change.operation}
          </span>
          <span className="pb-review-row-path">{change.target_path}</span>
        </span>
        <span className="pb-review-row-meta">
          <span className="pb-review-row-author">{change.author_label}</span>
          <time dateTime={change.created_at} title={change.created_at}>
            {formatTime(change.created_at)}
          </time>
          <span className="pb-review-row-status" data-pb-review-row-status-chip={change.status}>
            {change.status}
          </span>
          {drifted && (
            <span className="pb-review-chip-drift" data-pb-review-chip-drift>
              drift
            </span>
          )}
        </span>
      </Link>
    </li>
  );
}
