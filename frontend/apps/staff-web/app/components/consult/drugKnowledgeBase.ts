// CLN-018: attribution of the licensed drug knowledge base, read once per browser session from
// GET /v1/clinical/drug-knowledge-base and shown as the footer "Drug data: <KB> <month>" on the Rx pad.
import { useEffect, useState } from "react";

import { attributionLine } from "./drugKbLine";

let cached: Promise<string | null> | null = null;

/** undefined while loading, null when nothing to show (not configured, no permission, network or server error). */
export function useDrugKbAttribution(authedFetch: (path: string) => Promise<Response | null>): string | null | undefined {
  const [line, setLine] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    let live = true;
    if (!cached) {
      cached = authedFetch("/clinical/drug-knowledge-base")
        .then(async (res) => (res && res.ok ? attributionLine(await res.json()) : null))
        .catch(() => null);
      cached.then((v) => { if (v === null) cached = null; }); // retry a failure on the next mount, keep a success
    }
    cached.then((v) => { if (live) setLine(v); });
    return () => { live = false; };
  }, [authedFetch]);
  return line;
}
