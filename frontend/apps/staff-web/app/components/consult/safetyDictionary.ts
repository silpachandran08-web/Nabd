// CLN-004: which safety dictionary produced the Rx warnings, read once per browser session from
// GET /v1/clinical/safety-dictionary and shown under each warning ("Source: Nabd CSO dictionary IN v1").
import { useEffect, useState } from "react";

export type SafetyDictionaryInfo = { region: string; version: number; label: string; source: string };

/** "Source: <source> <label>", or null when the response lacks either part. */
export function sourceLine(d: Partial<SafetyDictionaryInfo> | null | undefined): string | null {
  return d && d.source && d.label ? `Source: ${d.source} ${d.label}` : null;
}

let cached: Promise<string | null> | null = null;

/** undefined while loading, null when unavailable (no permission, network or server error), else the line. */
export function useSafetyDictionarySource(authedFetch: (path: string) => Promise<Response | null>): string | null | undefined {
  const [line, setLine] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    let live = true;
    if (!cached) {
      cached = authedFetch("/clinical/safety-dictionary")
        .then(async (res) => (res && res.ok ? sourceLine(await res.json()) : null))
        .catch(() => null);
      cached.then((v) => { if (v === null) cached = null; }); // retry a failure on the next mount, keep a success
    }
    cached.then((v) => { if (live) setLine(v); });
    return () => { live = false; };
  }, [authedFetch]);
  return line;
}
