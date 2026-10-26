"use client";
// OPS-028: web vitals and uncaught errors, scrubbed (lib/telemetryScrub.ts), to the in-region /v1/client-telemetry sink.
import { useEffect } from "react";
import { useReportWebVitals } from "next/web-vitals";
import { api } from "./lib/api.ts";
import { errorReport, vitalReport, type TelemetryReport } from "./lib/telemetryScrub.ts";

function send(r: TelemetryReport) {
  if (!localStorage.getItem("nabd_access_token")) return; // endpoint needs a session; never queue reports for later
  if (location.pathname.startsWith("/platform")) return; // operator pages run on the operator session; never send a clinic token there
  api.post("/client-telemetry", r).catch(() => {}); // best effort: telemetry must never raise its own error
}

export default function TelemetryReporter() {
  useReportWebVitals((m) => send(vitalReport(m.name, m.value, window.location.href)));
  useEffect(() => {
    const onError = (ev: ErrorEvent) => send(errorReport(ev.error, window.location.href));
    const onRejection = (ev: PromiseRejectionEvent) => send(errorReport(ev.reason, window.location.href));
    window.addEventListener("error", onError);
    window.addEventListener("unhandledrejection", onRejection);
    return () => { window.removeEventListener("error", onError); window.removeEventListener("unhandledrejection", onRejection); };
  }, []);
  return null;
}
