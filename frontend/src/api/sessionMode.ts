import { createContext, createElement, useContext, useRef, type ReactNode } from "react";
import { ApiError } from "./client";

/** Every read of the session's auth mode lives here; a source gate enforces it. */
type Session = { auth_mode: string; authenticated?: boolean } | null | undefined;
type SessionQuery = { data: Session; error: unknown; isFetched: boolean };
const SessionMode = createContext<SessionQuery | null>(null);

/** Only data, an OFF 404, or a query reset can replace the Shell's established mode evidence. */
export function SessionModeProvider({ session, children }: { session: SessionQuery; children: ReactNode }) {
  // This evidence lasts for one Shell mount; a remount starts without the previous OFF settlement.
  const offError = useRef<unknown>(null);
  if (session.data != null || !session.isFetched || session.error instanceof ApiError && session.error.isNotFound) {
    offError.current = session.error;
  }
  const snapshot = { ...session, error: offError.current ?? session.error };
  return createElement(SessionMode.Provider, { value: snapshot }, children);
}

/** Passive consumers share the Shell's last settlement, including when they mount during a refetch. */
export function useSessionMode() {
  const session = useContext(SessionMode);
  if (!session) throw new Error("Session mode requires its shell provider");
  return session;
}

export function isOffSession(session: SessionQuery): boolean {
  return session.data != null ? isOff(session.data) :
    session.isFetched && session.error instanceof ApiError && session.error.isNotFound;
}

/** Other settled errors remain unknown until a successful response or the endpoint's OFF 404. */
export function isSessionKnown(session: SessionQuery): boolean {
  return session.data != null || isOffSession(session);
}

export function isWorkspaceSession(session: SessionQuery): boolean {
  return isSessionKnown(session) && !isReader(session.data);
}

export function isReader(session: Session): boolean {
  return session?.auth_mode === "reader";
}

export function isBuiltin(session: Session): boolean {
  return session?.auth_mode === "builtin";
}

export function isOff(session: Session): boolean {
  return session?.auth_mode === "off";
}

export function signInRequired(session: Session): boolean {
  return session?.authenticated === false && (session.auth_mode === "builtin" || session.auth_mode === "proxy");
}
