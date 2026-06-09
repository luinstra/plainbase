import { useEffect, useState } from "react";

type Health = { status: string; version: string };

export function App() {
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch("/healthz")
      .then((r) => r.json() as Promise<Health>)
      .then(setHealth)
      .catch((e: unknown) => setError(String(e)));
  }, []);

  return (
    <div className="pb-shell" data-pb-shell>
      <header className="pb-header">
        <span className="pb-logo">Plainbase</span>
      </header>
      <main className="pb-main">
        <h1>Plainbase</h1>
        <p>
          Internal docs that humans enjoy using and agents can actually work
          with.
        </p>
        <p className="pb-health">
          Server status:{" "}
          {health ? (
            <code>
              {health.status} (v{health.version})
            </code>
          ) : error ? (
            <code>unreachable</code>
          ) : (
            <code>checking…</code>
          )}
        </p>
      </main>
    </div>
  );
}
