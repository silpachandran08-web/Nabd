// FEF-010: public CSP violation sink (report-to and report-uri). 204 always for accepted input; 413 over 8 KB; 429 when rate-limited.
import { MAX_REPORT_BYTES, allow, toViolations } from "../../security/cspReport.ts";

async function readCapped(req: Request): Promise<string | null> {
  if (Number(req.headers.get("content-length") ?? 0) > MAX_REPORT_BYTES) return null;
  const reader = req.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_REPORT_BYTES) { await reader.cancel(); return null; }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

export async function POST(req: Request) {
  const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || "unknown";
  if (!allow(ip)) return new Response(null, { status: 429, headers: { "Retry-After": "60" } });
  const text = await readCapped(req);
  if (text === null) return new Response(null, { status: 413 });
  let body: unknown;
  try { body = JSON.parse(text); } catch { return new Response(null, { status: 204 }); } // garbage is dropped, never echoed
  for (const v of toViolations(body)) console.info(JSON.stringify({ event: "csp.violation", ...v }));
  return new Response(null, { status: 204 });
}
