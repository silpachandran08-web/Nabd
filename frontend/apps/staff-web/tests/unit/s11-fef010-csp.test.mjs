// FEF-010: CSP builder, nonce, security headers, report scrubbing and the /api/csp-report sink. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { newNonce, buildCsp, cspHeaderName, apiOrigin, STATIC_HEADERS } from "../../app/security/csp.ts";
import { scrubUrl, toViolations, allow, MAX_REPORT_BYTES } from "../../app/security/cspReport.ts";
import { POST } from "../../app/api/csp-report/route.ts";

const directive = (csp, name) => csp.split("; ").find((d) => d.split(" ")[0] === name);

test("nonce is fresh, 128-bit base64, and generated in < 1 ms", () => {
  const seen = new Set();
  const t0 = performance.now();
  for (let i = 0; i < 1000; i++) seen.add(newNonce());
  const perCall = (performance.now() - t0) / 1000;
  assert.equal(seen.size, 1000);
  for (const n of seen) assert.match(n, /^[A-Za-z0-9+/]{22}==$/);
  assert.ok(perCall < 1, `nonce took ${perCall} ms`);
});

test("CSP has every directive from the spec and no unsafe-inline/unsafe-eval for scripts", () => {
  const csp = buildCsp("abc", { connect: "https://api.example" });
  assert.equal(directive(csp, "script-src"), "script-src 'self' 'nonce-abc' 'strict-dynamic'");
  assert.equal(directive(csp, "style-src"), "style-src 'self' 'nonce-abc'");
  for (const d of ["default-src 'self'", "img-src 'self' data: blob:", "font-src 'self'", "frame-ancestors 'none'", "base-uri 'none'", "form-action 'self'", "object-src 'none'", "report-to csp", "report-uri /api/csp-report"])
    assert.ok(csp.split("; ").includes(d), d);
  assert.equal(directive(csp, "connect-src"), "connect-src 'self' https://api.example");
  assert.equal(directive(buildCsp("abc"), "connect-src"), "connect-src 'self'");
  assert.doesNotMatch(directive(csp, "script-src"), /unsafe/);
  assert.doesNotMatch(directive(csp, "default-src"), /unsafe/);
  assert.match(directive(buildCsp("abc", { dev: true }), "script-src"), /'unsafe-eval'/, "dev only");
});

test("report-only unless NABD_CSP_MODE=enforce", () => {
  assert.equal(cspHeaderName(undefined), "Content-Security-Policy-Report-Only");
  assert.equal(cspHeaderName("report-only"), "Content-Security-Policy-Report-Only");
  assert.equal(cspHeaderName("ENFORCE"), "Content-Security-Policy-Report-Only");
  assert.equal(cspHeaderName("enforce"), "Content-Security-Policy");
});

test("API origin for connect-src", () => {
  assert.equal(apiOrigin(undefined), "http://localhost:8080");
  assert.equal(apiOrigin("https://nabd-f0zb.onrender.com/v1"), "https://nabd-f0zb.onrender.com");
  assert.equal(apiOrigin("/api/v1"), null);
});

test("static security headers", () => {
  assert.equal(STATIC_HEADERS["Strict-Transport-Security"], "max-age=63072000; includeSubDomains; preload");
  assert.equal(STATIC_HEADERS["X-Content-Type-Options"], "nosniff");
  assert.equal(STATIC_HEADERS["Referrer-Policy"], "same-origin");
  assert.equal(STATIC_HEADERS["Cross-Origin-Opener-Policy"], "same-origin");
  assert.equal(STATIC_HEADERS["Permissions-Policy"], "camera=(self), microphone=(), geolocation=(), payment=()");
  assert.equal(STATIC_HEADERS["Reporting-Endpoints"], 'csp="/api/csp-report"');
});

test("scrubUrl drops query, fragment, ids and content", () => {
  assert.equal(scrubUrl("https://app.example/patients/8f3a1c2e-1111-4222-8333-444455556666/visits/42?name=Asha#x"), "https://app.example/patients/:id/visits/:id");
  assert.equal(scrubUrl("inline"), "inline");
  assert.equal(scrubUrl("eval"), "eval");
  assert.equal(scrubUrl("data:text/html;base64,PHNjcmlwdD4="), "data");
  assert.equal(scrubUrl("not a url with spaces"), "invalid");
  assert.equal(scrubUrl(undefined), "");
});

test("toViolations: Reporting API and legacy report-uri bodies, samples never kept", () => {
  const api = toViolations([
    { type: "csp-violation", url: "https://app.example/patients?q=Asha", body: { documentURL: "https://app.example/patients?q=Asha", blockedURL: "inline", effectiveDirective: "script-src-elem", disposition: "enforce", sample: "alert('Asha Verma')", sourceFile: "https://app.example/_next/static/chunks/1.js?v=1", lineNumber: 3 } },
    { type: "deprecation", body: {} },
  ]);
  assert.deepEqual(api, [{ directive: "script-src-elem", blocked: "inline", document: "https://app.example/patients", source: "https://app.example/_next/static/chunks/:id", disposition: "enforce" }]);
  const legacy = toViolations({ "csp-report": { "document-uri": "https://app.example/x?token=t", "blocked-uri": "https://evil.example/a.js?c=1", "violated-directive": "script-src", "script-sample": "secret", disposition: "report" } });
  assert.deepEqual(legacy, [{ directive: "script-src", blocked: "https://evil.example/a.js", document: "https://app.example/x", source: "", disposition: "report" }]);
  assert.deepEqual(toViolations("nope"), []);
  assert.deepEqual(toViolations(null), []);
  assert.ok(!JSON.stringify(api).includes("Asha"));
});

test("rate limiter: 60 per minute per key, then resets", () => {
  const t = 1_000_000;
  for (let i = 0; i < 60; i++) assert.ok(allow("k1", t));
  assert.equal(allow("k1", t), false);
  assert.ok(allow("k2", t), "other keys unaffected");
  assert.ok(allow("k1", t + 60_000));
});

const post = (body, headers = {}) => POST(new Request("http://x/api/csp-report", { method: "POST", body, headers: { "content-type": "application/reports+json", ...headers } }));

test("POST /api/csp-report: 204, logs scrubbed line, 413 over 8 KB (header or streamed), garbage dropped", async () => {
  const lines = [];
  const orig = console.info;
  console.info = (s) => lines.push(s);
  try {
    const r = await post(JSON.stringify([{ type: "csp-violation", body: { documentURL: "https://a.example/p/12?x=1", blockedURL: "inline", effectiveDirective: "script-src-elem", disposition: "enforce", sample: "PHI" } }]), { "x-forwarded-for": "10.0.0.1" });
    assert.equal(r.status, 204);
    assert.equal(lines.length, 1);
    assert.deepEqual(JSON.parse(lines[0]), { event: "csp.violation", directive: "script-src-elem", blocked: "inline", document: "https://a.example/p/:id", source: "", disposition: "enforce" });
    assert.equal((await post("x".repeat(MAX_REPORT_BYTES + 1), { "x-forwarded-for": "10.0.0.2" })).status, 413);
    assert.equal((await post("x".repeat(MAX_REPORT_BYTES), { "x-forwarded-for": "10.0.0.2" })).status, 204, "exactly 8 KB is accepted (boundary)");
    assert.equal((await post("{", { "x-forwarded-for": "10.0.0.2" })).status, 204);
    assert.equal(lines.length, 1);
  } finally { console.info = orig; }
});

test("POST /api/csp-report: 429 after 60 reports from one client", async () => {
  let last;
  for (let i = 0; i < 61; i++) last = await post("[]", { "x-forwarded-for": "10.9.9.9" });
  assert.equal(last.status, 429);
});
