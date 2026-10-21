// Independent QA (S11) for OPS-046: the k6 harness artefacts (AC3 report/gate, AC4 one command + synthetic credentials).
// The load runs themselves (AC1, AC2) need k6 and staging-in and are NOT run here.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, readdirSync, existsSync, copyFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const PERF = new URL("../../../../../perf/", import.meta.url).pathname;
const tmp = () => mkdtempSync(join(tmpdir(), "qa4-perf-"));

function summary(profile, { p95 = 120, errorRate = 0, thresholdsOk = true, endpoint = true } = {}) {
  return {
    profile, generatedAt: "2026-10-19T00:00:00Z", env: { base: "http://x.invalid" },
    endpoints: endpoint ? { [profile]: { p50: p95 / 2, p95, p99: p95 * 1.5, count: 1000 } } : {},
    overall: { p50: p95 / 2, p95, p99: p95 * 1.5, requests: 1000, errorRate },
    thresholds: { [`http_req_duration{name:${profile}}`]: { "p(95)<300": thresholdsOk } }, custom: {},
  };
}

function report(sum, { cpu, baseline } = {}) {
  const dir = tmp();
  const s = join(dir, "s.json");
  writeFileSync(s, JSON.stringify(sum));
  const args = [join(PERF, "report.mjs"), s];
  if (cpu) { writeFileSync(join(dir, "db.csv"), cpu.map((c, i) => `${1000 + i},${c},3,10`).join("\n") + "\n"); args.push("--samples", join(dir, "db.csv")); }
  if (baseline) { writeFileSync(join(dir, "base.json"), JSON.stringify(baseline)); args.push("--baseline", join(dir, "base.json")); }
  const r = spawnSync(process.execPath, args, { encoding: "utf8" });
  const json = JSON.parse(readFileSync(join(dir, `${sum.profile}-report.json`), "utf8"));
  const html = readFileSync(join(dir, `${sum.profile}-report.html`), "utf8");
  return { code: r.status, json, html, out: r.stdout };
}

test("AC3: a passing run writes JSON + HTML with p50/p95/p99, DB CPU and connection usage", () => {
  const r = report(summary("registry"), { cpu: [40, 55, 61] });
  assert.equal(r.code, 0, r.out);
  assert.equal(r.json.pass, true);
  for (const k of ["p50", "p95", "p99"]) assert.ok(r.json.summary.overall[k] > 0, k);
  assert.equal(r.json.db.dbCpuPct.max, 61);
  assert.equal(r.json.db.dbActiveConnections.max, 3);
  assert.match(r.html, /p50 ms.*p95 ms.*p99 ms/s);
  assert.match(r.html, /dbCpuPct/);
});

test("AC3 gate: DB CPU >= 70%, error rate >= 0.1%, a failed k6 threshold or an aborted run each fail the gate", () => {
  assert.equal(report(summary("registry"), { cpu: [50, 70] }).code, 1);
  assert.equal(report(summary("registry"), { cpu: [69.9] }).code, 0);
  assert.equal(report(summary("registry", { errorRate: 0.001 })).code, 1);
  assert.equal(report(summary("registry", { errorRate: 0.0009 })).code, 0);
  assert.equal(report(summary("checkin", { thresholdsOk: false })).code, 1);
  assert.equal(report(summary("checkin", { endpoint: false })).code, 1);
});

test("AC3 baseline: p95 more than 10% above the stored baseline fails, within 10% passes", () => {
  const base = summary("registry", { p95: 100 });
  assert.equal(report(summary("registry", { p95: 110.5 }), { baseline: base }).code, 1);
  assert.equal(report(summary("registry", { p95: 109.5 }), { baseline: base }).code, 0);
});

test("AC4: one command per profile; it refuses to start without target, synthetic PIN and peak rate", () => {
  execFileSync("bash", ["-n", join(PERF, "run.sh")]);
  for (const missing of ["BASE_URL", "PERF_PIN", "PEAK_RPS"]) {
    const env = { PATH: process.env.PATH, BASE_URL: "http://x.invalid", PERF_PIN: "x", PEAK_RPS: "1" };
    delete env[missing];
    const r = spawnSync("bash", [join(PERF, "run.sh"), "registry"], { env, encoding: "utf8" });
    assert.notEqual(r.status, 0, missing);
    assert.match(r.stderr, new RegExp(missing), missing);
  }
  for (const p of ["registry", "checkin"]) assert.ok(existsSync(join(PERF, "k6", `${p}.js`)), p);
});

test("AC1/AC2 profiles: gates are encoded as k6 thresholds (p95 < 300 ms, < 0.1% errors, 0 duplicate tokens) and parse", () => {
  const reg = readFileSync(join(PERF, "k6/registry.js"), "utf8");
  const chk = readFileSync(join(PERF, "k6/checkin.js"), "utf8");
  assert.match(reg, /p\(95\)<300/);
  assert.match(reg, /rate<0\.001/);
  assert.match(chk, /p\(95\)<300/);
  assert.match(chk, /duplicate_tokens: \['count==0'\]/);
  const dir = tmp();
  for (const f of readdirSync(join(PERF, "k6"))) {
    copyFileSync(join(PERF, "k6", f), join(dir, f.replace(/\.js$/, ".mjs")));
    execFileSync(process.execPath, ["--check", join(dir, f.replace(/\.js$/, ".mjs"))]); // syntax only; k6 modules are not resolved
  }
});

test("AC4: synthetic credentials only: no PIN, token or real mailbox committed under perf/", () => {
  const files = [];
  const walk = (d) => { for (const n of readdirSync(d, { withFileTypes: true })) { const p = join(d, n.name); if (n.isDirectory()) { if (n.name !== "out") walk(p); } else files.push(p); } };
  walk(PERF);
  for (const f of files) {
    const src = readFileSync(f, "utf8");
    assert.doesNotMatch(src, /PERF_PIN=["']?\d{4,}/, f);
    assert.doesNotMatch(src, /\$argon2id\$v=19\$m=\d+/, f);
    assert.doesNotMatch(src, /Bearer\s+eyJ/, f);
    assert.doesNotMatch(src, /@(gmail|yahoo|outlook|hotmail)\.com/, f);
  }
  const seed = readFileSync(join(PERF, "data/seed.sql"), "utf8");
  const mailLines = seed.split("\n").filter((l) => l.includes("@") && !l.trim().startsWith("--"));
  assert.ok(mailLines.length > 0);
  for (const l of mailLines) assert.match(l, /\.invalid/, l); // RFC 2606 reserved domain: never a real mailbox
});
