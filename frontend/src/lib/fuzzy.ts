import type { TreePage } from "../api/types";

/**
 * Client-side fuzzy quick-switcher scorer (master D11 — hand-rolled, no fuzzy library).
 * Pure functions; non-contract UI, freely evolvable (the goldens pin behavior, not a wire
 * surface). Subsequence matching with consecutive-run and word-boundary bonuses.
 */

export interface FuzzyCandidate {
  node: TreePage;
  /** node.title — the primary match target. */
  label: string;
  /** node.path — shown dimmed; also matched against. */
  hint: string;
}

export interface FuzzyMatch {
  candidate: FuzzyCandidate;
  /** Higher = better; non-matches are dropped, never returned. */
  score: number;
}

const SEPARATORS = new Set([" ", "/", "-", "_", "."]);

/**
 * Subsequence match of `query` against `text`, case-insensitive: every query char must
 * appear in order in `text`, gaps allowed. Returns null on no match. Score rewards
 * consecutive runs (no gap between matched chars) and word-boundary starts (text start or
 * just after a separator) so initials and path-segment starts rank highest.
 */
export function fuzzyScore(query: string, text: string): number | null {
  if (query.length === 0) return 0;
  const q = query.toLowerCase();
  const t = text.toLowerCase();

  let score = 0;
  let qi = 0;
  let prevMatch = -2; // index of the previous matched char in `t` (-2 ⇒ none yet)
  for (let ti = 0; ti < t.length && qi < q.length; ti++) {
    if (t[ti] !== q[qi]) continue;
    let charScore = 1;
    if (ti === prevMatch + 1) charScore += 3; // consecutive run
    const atBoundary = ti === 0 || SEPARATORS.has(t[ti - 1]);
    if (atBoundary) charScore += 2; // word-boundary / segment start
    score += charScore;
    prevMatch = ti;
    qi++;
  }
  return qi === q.length ? score : null;
}

/**
 * Ranks candidates: scores `label` and `hint` independently, keeps the better, drops
 * non-matches, sorts by score descending. Ties break by title length ascending then title
 * lexicographic — a deterministic order so the rendered list (and its selection index) is
 * stable across recomputes.
 */
export function fuzzyRank(query: string, candidates: FuzzyCandidate[]): FuzzyMatch[] {
  const matches: FuzzyMatch[] = [];
  for (const candidate of candidates) {
    const labelScore = fuzzyScore(query, candidate.label);
    const hintScore = fuzzyScore(query, candidate.hint);
    if (labelScore === null && hintScore === null) continue;
    matches.push({ candidate, score: Math.max(labelScore ?? -Infinity, hintScore ?? -Infinity) });
  }
  matches.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    if (a.candidate.label.length !== b.candidate.label.length) return a.candidate.label.length - b.candidate.label.length;
    return a.candidate.label < b.candidate.label ? -1 : a.candidate.label > b.candidate.label ? 1 : 0;
  });
  return matches;
}
