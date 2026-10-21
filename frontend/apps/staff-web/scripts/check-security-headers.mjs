// FEF-010: assert security headers on one route per route group of a running staff-web. CI (security-headers.yml) and local.
// Usage: node scripts/check-security-headers.mjs http://localhost:3000 [--enforce]
const base = process.argv[2] ?? "http://localhost:3000";
const enforce = process.argv.includes("--enforce");
const ROUTES = ["/login", "/", "/patients", "/my-day", "/billing", "/owner/workspaces", "/platform", "/platform/login"];
const CSP = enforce ? "content-security-policy" : "content-security-policy-report-only";
const EXPECT = {
  "strict-transport-security": "max-age=63072000; includeSubDomains; preload",
  "x-content-type-options": "nosniff",
  "referrer-policy": "same-origin",
  "cross-origin-opener-policy": "same-origin",
  "permissions-policy": "camera=(self), microphone=(), geolocation=(), payment=()",
};
let fail = 0;
const ok = (cond, msg) => { console.log(`${cond ? "PASS" : "FAIL"} ${msg}`); if (!cond) fail++; };
const nonces = new Set();
for (const r of ROUTES) {
  for (let i = 0; i < 2; i++) {
    const res = await fetch(base + r, { redirect: "manual" });
    const h = res.headers;
    const csp = h.get(CSP) ?? "";
    const script = csp.split(";").map((d) => d.trim()).find((d) => d.startsWith("script-src ")) ?? "";
    const nonce = script.match(/'nonce-([^']+)'/)?.[1];
    if (i === 0) {
      ok(res.status === 200 && /text\/html/.test(h.get("content-type") ?? ""), `${r}: HTML 200 (${res.status})`);
      for (const [k, v] of Object.entries(EXPECT)) ok(h.get(k) === v, `${r}: ${k}`);
      ok(/frame-ancestors 'none'/.test(csp), `${r}: frame-ancestors 'none'`);
      ok(!/unsafe-(inline|eval)/.test(script) && /'strict-dynamic'/.test(script), `${r}: script-src strict, no unsafe-*`);
      ok(!h.get(enforce ? "content-security-policy-report-only" : "content-security-policy"), `${r}: only ${CSP}`);
      const html = await res.text();
      ok(nonce && html.includes(`nonce="${nonce}"`), `${r}: Next scripts carry the header nonce`);
    }
    ok(nonce && !nonces.has(nonce), `${r}: fresh nonce (request ${i + 1})`);
    nonces.add(nonce);
  }
}
const asset = await fetch(base + "/favicon.ico");
ok(asset.headers.get("x-content-type-options") === "nosniff" && !!asset.headers.get("strict-transport-security"), "static asset: HSTS + nosniff");
console.log(fail ? `${fail} header check(s) failed` : "all header checks passed");
process.exit(fail ? 1 : 0);
