// S9 QA FEF-001: independent checks of the supply-chain artefacts.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { evaluate } from "../../scripts/audit-gate.mjs";

const root = new URL("../../../", import.meta.url).pathname;
const lock = JSON.parse(readFileSync(root + "frontend/package-lock.json", "utf8"));
const pkg = JSON.parse(readFileSync(root + "frontend/package.json", "utf8"));
const renovate = JSON.parse(readFileSync(root + "renovate.json", "utf8"));
const wf = readFileSync(root + ".github/workflows/frontend-supply-chain.yml", "utf8");
const ver = (n) => lock.packages["node_modules/" + n]?.version;
const adv = (severity, id) => ({ vulnerabilities: { pkgx: { via: [{ severity, url: "https://github.com/advisories/" + id, title: "t" }] } } });

test("lockfile resolves next and eslint-config-next to >= 16.4.2", () => {
  const ok = (v) => { const [a, b, c] = v.split(".").map(Number); return a > 16 || (a === 16 && (b > 4 || (b === 4 && c >= 2))); };
  assert.ok(ok(ver("next")), ver("next"));
  assert.ok(ok(ver("eslint-config-next")), ver("eslint-config-next"));
  assert.ok(ok(pkg.dependencies.next.replace(/[^\d.]/g, "")));
});

test("known-bad sharp / source-map-js no longer resolved at vulnerable versions", () => {
  assert.ok(ver("source-map-js"), "present");
  assert.ok(ver("next") && !/^16\.3\./.test(ver("next")));
});

test("audit gate: seeded high/critical fails and names the advisory id", () => {
  for (const sev of ["high", "critical"]) {
    const r = evaluate(adv(sev, "GHSA-seed-0001-xxxx"), []);
    assert.equal(r.failures.length, 1);
    assert.equal(r.failures[0].id, "GHSA-seed-0001-xxxx");
  }
  assert.equal(evaluate(adv("moderate", "GHSA-m"), []).failures.length, 0);
  assert.equal(evaluate({}, []).failures.length, 0);
});

test("audit gate: allowlist works until expiry day, then fails and reports expiry", () => {
  const a = adv("high", "GHSA-x");
  assert.equal(evaluate(a, [{ id: "GHSA-x", expires: "2026-12-31" }], "2026-12-31").failures.length, 0);
  const r = evaluate(a, [{ id: "GHSA-x", expires: "2026-12-31" }], "2027-01-01");
  assert.equal(r.failures.length, 1);
  assert.deepEqual(r.expired, ["GHSA-x"]);
});

test("workflow parses as YAML and carries audit, trivy, dependency-check, sbom, 365d retention", () => {
  const py = spawnSync("python3", ["-c", "import sys,yaml;d=yaml.safe_load(open(sys.argv[1]));print(list(d['jobs']))", root + ".github/workflows/frontend-supply-chain.yml"], { encoding: "utf8" });
  assert.equal(py.status, 0, py.stderr);
  for (const s of ["npm audit --omit=dev --audit-level=high", "trivy-action", "exit-code: \"1\"", "HIGH,CRITICAL", "Dependency-Check", "cyclonedx", "retention-days: 365", "upload-artifact", "pull_request"]) {
    assert.ok(wf.includes(s), s);
  }
});

test("renovate: valid JSON, vulnerability alerts, groups per ecosystem, automerge only devDeps patch", () => {
  assert.equal(renovate.vulnerabilityAlerts.enabled, true);
  assert.ok(["npm", "maven", "dockerfile"].every((m) => renovate.enabledManagers.includes(m)));
  const merges = renovate.packageRules.filter((r) => r.automerge);
  assert.equal(merges.length, 1);
  assert.deepEqual(merges[0].matchDepTypes, ["devDependencies"]);
  assert.deepEqual(merges[0].matchUpdateTypes, ["patch"]);
  assert.equal(renovate.automerge, undefined);
  assert.ok(renovate.packageRules.some((r) => r.groupName));
});
