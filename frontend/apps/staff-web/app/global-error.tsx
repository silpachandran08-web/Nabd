"use client";

import { useEffect } from "react";
import { send } from "./TelemetryReporter";
import { boundaryReport } from "./lib/telemetryScrub";

// FEF-029: the root layout itself failed, so there are no providers, fonts or tokens: self-contained markup only.
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => { send(boundaryReport(error, window.location.pathname, "global", process.env.NEXT_PUBLIC_RELEASE)); }, [error]);
  return (
    <html lang="en">
      <body style={{ fontFamily: "system-ui, sans-serif", padding: 24 }}>
        <main role="alert">
          <h1>Something went wrong</h1>
          <p>Nothing you typed was lost: drafts stay on this device.</p>
          <button type="button" onClick={reset} style={{ minBlockSize: 44, paddingInline: 16 }}>Retry</button>
        </main>
      </body>
    </html>
  );
}
