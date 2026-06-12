import type { ErrorEnvelope } from "./types";

/** A non-2xx API answer, carrying the frozen error-envelope code (§A4). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { accept: "application/json" } });
  if (!response.ok) {
    let code = "unknown_error";
    let message = `Request failed with status ${response.status}`;
    try {
      const envelope = (await response.json()) as ErrorEnvelope;
      code = envelope.error.code;
      message = envelope.error.message;
    } catch {
      // non-envelope body (proxy error page etc.) — keep the status-derived message
    }
    throw new ApiError(response.status, code, message);
  }
  return response.json() as Promise<T>;
}
