"use client";

import { useEffect } from "react";
import { send } from "./TelemetryReporter";
import { boundaryReport } from "./lib/telemetryScrub";
import en from "@nabd/i18n/messages/en/shell.json";
import hi from "@nabd/i18n/messages/hi/shell.json";

// FEF-029: the root layout itself failed, so there are no providers, fonts or tokens: self-contained markup only.
// FEF-040: no next-intl provider either, so the two catalogues are read directly (nabd_locale cookie, English otherwise).
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => { send(boundaryReport(error, window.location.pathname, "global", process.env.NEXT_PUBLIC_RELEASE)); }, [error]);
  const hindi = typeof document !== "undefined" && /(?:^|; )nabd_locale=hi(?:;|$)/.test(document.cookie);
  const m = (hindi ? hi : en).globalError;
  return (
    <html lang={hindi ? "hi" : "en"}>
      <body style={{ fontFamily: "system-ui, sans-serif", padding: 24 }}>
        <main role="alert">
          <h1>{m.title}</h1>
          <p>{m.body}</p>
          <button type="button" onClick={reset} style={{ minBlockSize: 44, paddingInline: 16 }}>{m.retry}</button>
        </main>
      </body>
    </html>
  );
}
