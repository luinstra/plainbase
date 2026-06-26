import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { ApiError } from "../api/client";
import { approveChange, rebaseChange, rejectChange } from "../api/proposals";
import { changeQuery, invalidateAfterDecision, invalidateAfterWrite } from "../api/queries";
import type { ChangeDetail } from "../api/types";
import { formatTime } from "../lib/datetime";
import { DiffView } from "./DiffView";
import { NotFoundView } from "./NotFound";

/**
 * P4 review detail (WI-5): a single proposed change — header + rationale + the SERVER unified diff rendered
 * VERBATIM (the client NEVER re-derives it, §0.13(i)) + the drift banner + the approve/reject/rebase actions.
 *
 * F8 (no client capability signal): `SessionResponse` carries no role/capability, so there is NO hard
 * approve gate — any authenticated reader opens this view (list/get need only `checkRead`). An approve/reject/
 * rebase 403 flips the no-access state (the `Admin.tsx` precedent); the server stays authoritative. The action
 * buttons are disabled per the F3 drift rules, NOT per a client-side capability probe.
 */
export function ReviewDetail({ id }: { id: string }) {
  const detail = useQuery(changeQuery(id));

  if (detail.isPending) {
    return (
      <p className="py-16 text-center text-faint" data-pb-loading>
        Loading…
      </p>
    );
  }
  if (detail.isError) {
    if (detail.error instanceof ApiError && detail.error.isNotFound) return <NotFoundView />;
    return (
      <div className="py-16 text-center" data-pb-error>
        <h1 className="text-2xl font-bold text-ink">Something went wrong</h1>
        <p className="mt-3 text-muted">{detail.error.message}</p>
      </div>
    );
  }

  // Key by id so navigating between proposals remounts with fresh action state.
  return <ReviewDetailView key={detail.data.id} change={detail.data} />;
}

function ReviewDetailView({ change }: { change: ChangeDetail }) {
  const queryClient = useQueryClient();
  const [comment, setComment] = useState("");
  const [noAccess, setNoAccess] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // F3, pinned exactly: the banner shows on EITHER drift signal; approve is enabled ONLY on a clean PENDING;
  // rebase is offered ONLY for CONFLICTED (the rebase endpoint 409s `not_conflicted` on anything else — a
  // live-drifted PENDING is not UI-rebasable, its only recovery is the agent re-proposing).
  const drifted = change.base_drifted || change.status === "CONFLICTED";
  const approvable = change.status === "PENDING" && change.base_drifted === false;
  const rejectable = change.status === "PENDING";
  const rebasable = change.status === "CONFLICTED";

  const refetchDetail = () => void queryClient.invalidateQueries({ queryKey: changeQuery(change.id).queryKey });

  // The shared mutation error router: a 403 is the no-access state (F8). Anything else (a 409 `conflicted`
  // discovered at apply, `apply_failed`, `not_pending`, …) shows a stable inline message AND refetches the
  // detail — a refetched CONFLICTED + base_drifted then lights up the banner/rebase affordances.
  const onActionError = (error: unknown) => {
    if (error instanceof ApiError && error.status === 403) {
      setNoAccess(true);
      return;
    }
    setActionError(error instanceof ApiError ? error.message : "Request failed.");
    refetchDetail();
  };

  const onDecided = () => {
    setActionError(null);
    invalidateAfterDecision(queryClient, change.id);
  };

  const approve = useMutation({
    mutationFn: () => approveChange(change.id),
    onSuccess: () => {
      onDecided();
      // An apply moved the content tree — refresh the affected page via the one write funnel. `page_id` is null
      // for a create (which 422s before a successful approve), so narrow + skip it (F4/WI-2).
      if (change.page_id !== null) invalidateAfterWrite(queryClient, { id: change.page_id });
    },
    onError: onActionError,
  });

  const reject = useMutation({
    mutationFn: () => rejectChange(change.id, comment.trim() === "" ? null : comment),
    onSuccess: onDecided,
    onError: onActionError,
  });

  const rebase = useMutation({
    mutationFn: () => rebaseChange(change.id),
    onSuccess: onDecided,
    onError: onActionError,
  });

  const busy = approve.isPending || reject.isPending || rebase.isPending;

  return (
    <div className="pb-review pb-review-detail" data-pb-review-detail>
      <Link to="/review" className="pb-review-back" data-pb-review-back>
        ← Back to the review queue
      </Link>

      <header className="pb-review-header" data-pb-review-header>
        <h1 className="pb-review-title text-2xl font-bold text-ink">
          <span className="pb-review-op" data-pb-review-op={change.operation}>
            {change.operation}
          </span>{" "}
          {change.target_path}
        </h1>
        <div className="pb-review-meta">
          <span className="pb-review-status" data-pb-review-status={change.status}>
            {change.status}
          </span>
          <span className="pb-review-author">{change.author_label}</span>
          <time dateTime={change.created_at} title={change.created_at}>
            {formatTime(change.created_at)}
          </time>
        </div>
        {(change.approver_external_id || change.decision_comment || change.decided_at || change.applied_commit || change.status_reason) && (
          <dl className="pb-review-decision" data-pb-review-decision>
            {change.approver_external_id && (
              <div>
                <dt>Decided by</dt>
                <dd>{change.approver_external_id}</dd>
              </div>
            )}
            {change.decided_at && (
              <div>
                <dt>Decided at</dt>
                <dd>
                  <time dateTime={change.decided_at} title={change.decided_at}>
                    {formatTime(change.decided_at)}
                  </time>
                </dd>
              </div>
            )}
            {change.decision_comment && (
              <div>
                <dt>Comment</dt>
                <dd data-pb-review-decision-comment>{change.decision_comment}</dd>
              </div>
            )}
            {change.applied_commit && (
              <div>
                <dt>Commit</dt>
                <dd className="font-mono">{change.applied_commit}</dd>
              </div>
            )}
            {change.status_reason && (
              <div>
                <dt>Reason</dt>
                <dd data-pb-review-status-reason>{change.status_reason}</dd>
              </div>
            )}
          </dl>
        )}
      </header>

      {/* The rationale is a plain reviewer string — rendered as escaped TEXT, never markdown (F7). */}
      <p className="pb-review-rationale" data-pb-review-rationale>
        {change.rationale}
      </p>

      {drifted && (
        <div className="pb-review-drift" data-pb-review-drift-banner>
          <p className="pb-review-drift-text">The base has drifted since this change was proposed.</p>
          {rebasable && (
            <button
              type="button"
              className="pb-review-action pb-review-action-rebase"
              data-pb-review-rebase
              disabled={busy}
              onClick={() => rebase.mutate()}
            >
              Rebase onto current
            </button>
          )}
        </div>
      )}

      {/* The SERVER diff, rendered verbatim — never re-derived (§0.13(i)). */}
      <DiffView unifiedDiff={change.unified_diff} path={change.target_path} />

      {noAccess && (
        <p className="pb-review-no-access" data-pb-review-no-access>
          You don't have access to review changes.
        </p>
      )}
      {actionError && (
        <p className="pb-review-action-error" data-pb-review-action-error>
          {actionError}
        </p>
      )}

      <div className="pb-review-actions" data-pb-review-actions>
        <button
          type="button"
          className="pb-review-action pb-review-action-approve"
          data-pb-review-approve
          disabled={!approvable || busy}
          onClick={() => approve.mutate()}
        >
          Approve
        </button>
        <div className="pb-review-reject">
          <textarea
            className="pb-review-comment"
            data-pb-review-comment
            placeholder="Reason for rejecting (optional)"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
          />
          <button
            type="button"
            className="pb-review-action pb-review-action-reject"
            data-pb-review-reject
            disabled={!rejectable || busy}
            onClick={() => reject.mutate()}
          >
            Reject
          </button>
        </div>
      </div>
    </div>
  );
}
