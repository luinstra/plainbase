/**
 * Formats an ISO-8601 timestamp for display, defensively (D-8): a malformed value falls back to the raw
 * string (never "Invalid Date", never throws). Callers ride the raw ISO on a `title` / `dateTime` attr so
 * tests stay timezone-independent. Shared by History, ReviewQueue, and ReviewDetail.
 */
export function formatTime(iso: string): string {
  return Number.isNaN(Date.parse(iso)) ? iso : new Date(iso).toLocaleString();
}
