// OPS-028: what the browser may send to /v1/client-telemetry. No query strings or fragments, no ids in the path,
// no input values, no names: only the metric name, a numeric value, the route shape and a hash of the stack.
const ID = /\/(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{2,})(?=\/|$)/gi;

export type TelemetryReport = { kind: "vital" | "error"; metric: string; value?: number; route: string; message: string; stackHash: string };

export function scrubRoute(href: string): string {
  const path = href.replace(/^[a-z]+:\/\/[^/]+/i, "").split(/[?#]/, 1)[0] || "/";
  return path.replace(ID, "/:id").slice(0, 200);
}

/** Small non-cryptographic hash (FNV-1a) of the stack's function/file frames; line numbers and message text are dropped. */
export function stackHash(stack: string | undefined): string {
  const frames = (stack ?? "").split("\n").slice(1, 6).map((l) => l.replace(/:\d+:\d+\)?$/, "").replace(/[?#].*$/, "").trim()).join("|");
  let h = 0x811c9dc5;
  for (let i = 0; i < frames.length; i++) { h ^= frames.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; }
  return h.toString(16);
}

/** The error class only (TypeError), never the message: messages echo input values and names. */
export function errorReport(e: unknown, href: string): TelemetryReport {
  const err = e instanceof Error ? e : null;
  return { kind: "error", metric: (err?.name ?? "Error").replace(/[^A-Za-z0-9_.:-]/g, "").slice(0, 64) || "Error", route: scrubRoute(href), message: "", stackHash: stackHash(err?.stack) };
}

export function vitalReport(name: string, value: number, href: string): TelemetryReport {
  return { kind: "vital", metric: name.replace(/[^A-Za-z0-9_.:-]/g, "").slice(0, 64) || "metric", value: Math.round(value * 100) / 100, route: scrubRoute(href), message: "", stackHash: "" };
}
