// FEF-013 AC3/AC4: shared types and helpers are defined once (packages/domain or the generated api-client types),
// and staff-web reads them from there. Plain source scan over apps/* and packages/* (node_modules excluded).
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const fe = new URL("../../../../", import.meta.url).pathname; // frontend/
function sources(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (["node_modules", ".next", ".turbo", "coverage", "lint-fixtures"].includes(e.name)) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) sources(p, out);
    else if (/\.(ts|tsx|mts|mjs)$/.test(e.name)) out.push(p);
  }
  return out;
}
// Test files are scanned too: a test that redefines a production type drifts from it unseen. The one exclusion is
// test-local fixture text: string literals in *.test.* files (source a test writes to a temp dir to prove a scanner
// bites, e.g. s12qa-fef013-gates' planted duplicates) are blanked before matching. A fixture TYPE in a test must use
// its own name, never QueueEntry / PatientSummary / Problem.
const STRINGS = /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`/g;
const read = (p) => { const s = readFileSync(p, "utf8"); return /\.test\./.test(p) ? s.replace(STRINGS, '""') : s; };
const scan = (roots) => roots.flatMap((r) => sources(r)).map((p) => [p.startsWith(fe) ? p.slice(fe.length) : p, read(p)]);
const files = scan([join(fe, "apps"), join(fe, "packages")]);
const definedIn = (re, list = files) => list.filter(([, s]) => re.test(s)).map(([p]) => p).sort();
const QUEUE_ENTRY_DEF = /^\s*(export\s+)?(type|interface)\s+QueueEntry\b(?!\s*=\s*G\.\w+;)/m;

// A plain `export type X = G.Y;` alias of the generated type (FEF-014 "wired" line) is not a second definition.
test("AC3: QueueEntry, PatientSummary and Problem are each defined once", () => {
  assert.deepEqual(definedIn(QUEUE_ENTRY_DEF), ["packages/domain/src/types.ts"]);
  assert.deepEqual(definedIn(/^\s*(export\s+)?(type|interface)\s+PatientSummary\b(?!\s*=\s*G\.\w+;)/m), ["packages/domain/src/types.ts"]);
  assert.deepEqual(definedIn(/^\s*(export\s+)?(type|interface)\s+Problem\b(?!\s*=\s*G\.\w+;)\s*=/m), ["packages/api-client/src/generated/types.ts"]);
});

test("AC3 gate: a production type redefined in a *.test.* file is caught; fixture text in a test's string literal is not", () => {
  const dir = mkdtempSync(join(tmpdir(), "fef013-dup-"));
  try {
    mkdirSync(join(dir, "app"));
    writeFileSync(join(dir, "app/dup.test.ts"), "export type QueueEntry = { id: string };\n");
    writeFileSync(join(dir, "app/fixture.test.mjs"), 'const planted = ["export type QueueEntry = { id: string };"];\nexport type QueueRowFixture = { id: string };\n');
    assert.deepEqual(definedIn(QUEUE_ENTRY_DEF, scan([dir])), [join(dir, "app/dup.test.ts")]);
  } finally { rmSync(dir, { recursive: true, force: true }); }
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
