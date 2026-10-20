// FEF-013 AC3/AC4: shared types and helpers are defined once (packages/domain or the generated api-client types),
// and staff-web reads them from there. Plain source scan over apps/* and packages/* (node_modules excluded).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const fe = new URL("../../../../", import.meta.url).pathname; // frontend/
function sources(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (["node_modules", ".next", ".turbo", "coverage", "lint-fixtures"].includes(e.name)) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) sources(p, out);
    else if (/\.(ts|tsx|mts|mjs)$/.test(e.name) && !/\.test\./.test(e.name)) out.push(p);
  }
  return out;
}
const files = [...sources(join(fe, "apps")), ...sources(join(fe, "packages"))].map((p) => [p.slice(fe.length), readFileSync(p, "utf8")]);
const definedIn = (re) => files.filter(([, s]) => re.test(s)).map(([p]) => p).sort();

// A plain `export type X = G.Y;` alias of the generated type (FEF-014 "wired" line) is not a second definition.
test("AC3: QueueEntry, PatientSummary and Problem are each defined once", () => {
  assert.deepEqual(definedIn(/^\s*(export\s+)?(type|interface)\s+QueueEntry\b(?!\s*=\s*G\.\w+;)/m), ["packages/domain/src/types.ts"]);
  assert.deepEqual(definedIn(/^\s*(export\s+)?(type|interface)\s+PatientSummary\b(?!\s*=\s*G\.\w+;)/m), ["packages/domain/src/types.ts"]);
  assert.deepEqual(definedIn(/^\s*(export\s+)?(type|interface)\s+Problem\b(?!\s*=\s*G\.\w+;)\s*=/m), ["packages/api-client/src/generated/types.ts"]);
});

test("AC4: VISIT_LABEL, initials() and age() exist once, in packages/domain", () => {
  assert.deepEqual(definedIn(/\b(const|let|var)\s+VISIT_LABEL\b/), ["packages/domain/src/status.ts"]);
  assert.deepEqual(definedIn(/\bfunction\s+initials\s*\(|\b(const|let)\s+initials\s*=\s*(\([^)]*\)|\w+)\s*=>|\b(const|let)\s+initials\s*=\s*\w+\.split\(/), ["packages/domain/src/format.ts"]);
  assert.deepEqual(definedIn(/\bfunction\s+age\s*\(|\b(const|let)\s+age\s*=\s*\([^)]*\)\s*=>/), ["packages/domain/src/format.ts"]);
});

test("the three FEF-012 cross-feature edges are gone and the lint allow-list is deleted", () => {
  const cfg = readFileSync(new URL("../../eslint.config.mjs", import.meta.url), "utf8");
  assert.doesNotMatch(cfg, /captured: \{ name: from \}/);
  const edge = /from "\.\.\/(checkout|consult)\//;
  for (const dir of ["arrivals", "myday", "templates"]) {
    for (const [p, s] of files.filter(([p]) => p.startsWith(`apps/staff-web/app/components/${dir}/`))) assert.doesNotMatch(s, edge, p);
  }
});

test("moved pure logic is imported from @nabd/domain, not from old app paths", () => {
  const old = /(components\/consult|\.)\/(consultFlow|rxPadLogic|doseLogic|pregnancyPolicy|rxSets)"|lib\/(vitals|money)"|checkout\/paymentMethods"/;
  const hits = files.filter(([p, s]) => p.startsWith("apps/") && old.test(s)).map(([p]) => p);
  assert.deepEqual(hits, []);
  const pkg = JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8"));
  assert.equal(pkg.dependencies["@nabd/domain"], "workspace:*");
});
