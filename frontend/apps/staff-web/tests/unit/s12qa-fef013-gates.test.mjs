// S12 QA-6 (independent) FEF-013: coverage gate (and that it bites), mutation checks that the Vitest suite catches
// broken sig/quantity and consult-flow logic, single-definition scans (and that a duplicate is caught), and the
// consult-flow migration. Mutations run on a temp copy of packages/domain, never on the tree.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync, appendFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const FE = fileURLToPath(new URL("../../../../", import.meta.url)); // frontend/
const REPO = join(FE, "..");
const DOMAIN = join(FE, "packages/domain");

// <tmp>/tsconfig.base.json + <tmp>/packages/domain: the same relative layout, so the domain tsconfig's extends resolves.
const roots = new Map();
function domainCopy() {
  const root = mkdtempSync(join(tmpdir(), "qa6-domain-"));
  cpSync(join(FE, "tsconfig.base.json"), join(root, "tsconfig.base.json"));
  const dir = join(root, "packages/domain");
  mkdirSync(dir, { recursive: true });
  for (const f of ["src", "test", "vitest.config.ts", "package.json", "tsconfig.json"]) cpSync(join(DOMAIN, f), join(dir, f), { recursive: true });
  symlinkSync(join(DOMAIN, "node_modules"), join(dir, "node_modules"));
  roots.set(dir, root);
  return dir;
}
const drop = (dir) => rmSync(roots.get(dir) ?? dir, { recursive: true, force: true });
const vitest = (dir, ...args) => spawnSync(join(DOMAIN, "node_modules/.bin/vitest"), ["run", ...args], { cwd: dir, encoding: "utf8", env: { ...process.env, CI: "1", FORCE_COLOR: "0" } });
const out = (r) => (r.stdout ?? "") + (r.stderr ?? "");

test("FEF-013 AC1: Vitest coverage >= 90% lines on every *Logic.ts, vitals, money and format file", { timeout: 120_000 }, () => {
  const dir = domainCopy();
  try {
    const r = vitest(dir, "--coverage");
    assert.equal(r.status, 0, out(r).slice(-3000));
    const sum = JSON.parse(readFileSync(join(dir, "coverage/coverage-summary.json"), "utf8"));
    const gated = Object.keys(sum).filter((k) => /\/src\/(\w*Logic|vitals|money|format)\.ts$/.test(k));
    const names = gated.map((k) => k.split("/").pop()).sort();
    assert.deepEqual(names, ["doseLogic.ts", "format.ts", "money.ts", "rxPadLogic.ts", "vitals.ts"]);
    for (const k of gated) assert.ok(sum[k].lines.pct >= 90, `${k}: ${sum[k].lines.pct}%`);
  } finally { drop(dir); }
});

test("FEF-013 AC1 negative: an untested branch-heavy function added to money.ts fails the coverage gate", { timeout: 120_000 }, () => {
  const dir = domainCopy();
  try {
    assert.equal(vitest(dir, "--coverage").status, 0, "unmutated copy passes the gate");
    const body = Array.from({ length: 30 }, (_, i) => `  if (x === ${i}) return ${i * 2};`).join("\n");
    appendFileSync(join(dir, "src/money.ts"), `\nexport function qaUntested(x: number): number {\n${body}\n  return -1;\n}\n`);
    const r = vitest(dir, "--coverage");
    assert.notEqual(r.status, 0, "gate must fail");
    assert.match(out(r), /money\.ts/);
    assert.match(out(r), /threshold/i);
  } finally { drop(dir); }
});

test("FEF-013 AC1 negative: a NEW untested *Logic.ts file fails the gate (perFile glob, untested files counted)", { timeout: 120_000 }, () => {
  const dir = domainCopy();
  try {
    assert.equal(vitest(dir, "--coverage").status, 0, "unmutated copy passes the gate");
    writeFileSync(join(dir, "src/triageLogic.ts"), "export function band(score: number): string {\n  if (score > 7) return 'red';\n  if (score > 4) return 'amber';\n  return 'green';\n}\n");
    const r = vitest(dir, "--coverage");
    assert.notEqual(r.status, 0, "gate must fail for an untested new *Logic.ts:\n" + out(r).slice(-1500));
    assert.match(out(r), /triageLogic\.ts/);
  } finally { drop(dir); }
});

test("FEF-013 AC2 mutation: suggestQuantity returning a negative quantity is caught by the property tests", { timeout: 120_000 }, () => {
  const dir = domainCopy();
  try {
    assert.equal(vitest(dir, "test/rxPadLogic.test.ts", "test/s12qa-fef013.test.ts").status, 0, "unmutated copy is green");
    const p = join(dir, "src/rxPadLogic.ts");
    const src = readFileSync(p, "utf8");
    const mutated = src.replace("return quantity > 0 && quantity <= 999999 ? { quantity, perDay, days } : null;", "return { quantity: perDay - days, perDay, days };");
    assert.notEqual(mutated, src, "mutation applied");
    writeFileSync(p, mutated);
    const r = vitest(dir, "test/rxPadLogic.test.ts");
    assert.notEqual(r.status, 0, "engineer's property suite must catch it");
    const r2 = vitest(dir, "test/s12qa-fef013.test.ts");
    assert.notEqual(r2.status, 0, "QA-6 property suite must catch it");
  } finally { drop(dir); }
});

test("FEF-013 AC2 mutation: durationDays accepting 0 days / NaN is caught", { timeout: 120_000 }, () => {
  const dir = domainCopy();
  try {
    assert.equal(vitest(dir, "test/s12qa-fef013.test.ts").status, 0, "unmutated copy is green");
    const p = join(dir, "src/rxPadLogic.ts");
    const src = readFileSync(p, "utf8");
    const mutated = src.replace("if (n <= 0) return null;", "").replace("const quantity = Math.round(perDay * days * 100) / 100;", "const quantity = days === 0 ? NaN : Math.round(perDay * days * 100) / 100;").replace("return quantity > 0 && quantity <= 999999", "return quantity <= 999999 || Number.isNaN(quantity)");
    assert.notEqual(mutated, src);
    writeFileSync(p, mutated);
    const r = vitest(dir, "test/s12qa-fef013.test.ts");
    assert.notEqual(r.status, 0, out(r).slice(-1500));
  } finally { drop(dir); }
});

test("FEF-013 AC5 mutation: breaking statusBeforeConsult is caught by the migrated consult-flow Vitest suite", { timeout: 120_000 }, () => {
  const dir = domainCopy();
  try {
    assert.equal(vitest(dir, "test/consultFlow.test.ts").status, 0, "unmutated copy is green");
    const p = join(dir, "src/consultFlow.ts");
    const src = readFileSync(p, "utf8");
    const mutated = src.replace(/export const DEFAULT_START_STATUS = "vitals_done"/, 'export const DEFAULT_START_STATUS = "waiting"')
      .replace(/(export function statusBeforeConsult\(sequence: string\[\]\): string \| null \{)/, "$1\n  if (sequence.length > 0) return sequence[0];");
    assert.notEqual(mutated, src);
    writeFileSync(p, mutated);
    const r = vitest(dir, "test/consultFlow.test.ts");
    assert.notEqual(r.status, 0);
    assert.match(out(r), /failed/);
  } finally { drop(dir); }
});

test("FEF-013 AC5: the 8 original test-consult-flow cases run as Vitest; the old script is gone and nothing runs it", () => {
  const ORIGINAL = [
    "default pipeline starts the consult from vitals_done",
    "a pipeline without vitals starts the consult from waiting",
    "billing before consultation starts from billing_pending",
    "a pipeline without consultation has no start status",
    "status labels replace every underscore and use the shared wording",
    "upcoming follow-up: soonest scheduled follow-up with this doctor, in the future",
    "next patient prefers a visit already in consultation, then the lowest startable token",
    "timeline only lists what is recorded",
  ];
  const src = readFileSync(join(DOMAIN, "test/consultFlow.test.ts"), "utf8");
  assert.match(src, /from "vitest"/);
  const names = [...src.matchAll(/^test\("([^"]+)"/gm)].map((m) => m[1]);
  assert.deepEqual(names, ORIGINAL);
  assert.equal(existsSync(join(FE, "apps/staff-web/scripts/test-consult-flow.mjs")), false);
  assert.equal(existsSync(join(FE, "scripts/test-consult-flow.mjs")), false);
  const app = JSON.parse(readFileSync(join(FE, "apps/staff-web/package.json"), "utf8"));
  assert.doesNotMatch(JSON.stringify(app.scripts), /test-consult-flow/);
  const dom = JSON.parse(readFileSync(join(DOMAIN, "package.json"), "utf8"));
  assert.match(dom.scripts.test, /^vitest run --coverage$/);
  const ci = readFileSync(join(REPO, ".github/workflows/ci.yml"), "utf8");
  assert.match(ci, /pnpm turbo run test\b/, "CI runs every workspace test task (domain included)");
});

// ---------- AC3 / AC4 single definitions ----------
function sources(dir, outList = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (["node_modules", ".next", ".turbo", "coverage", "lint-fixtures"].includes(e.name)) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) sources(p, outList);
    else if (/\.(ts|tsx|mts|mjs|js|jsx)$/.test(e.name) && !p.includes("/tests/") && !p.includes("/test/")) outList.push(p);
  }
  return outList;
}
// A definition = type/interface/class/enum/const declaration of that name, NOT an alias of the generated type and not an import/re-export.
const DEF = {
  QueueEntry: /^\s*(export\s+)?(declare\s+)?(type|interface|class|enum)\s+QueueEntry\b(?!\s*=\s*G\.\w+;)/m,
  PatientSummary: /^\s*(export\s+)?(declare\s+)?(type|interface|class|enum)\s+PatientSummary\b(?!\s*=\s*G\.\w+;)/m,
  Problem: /^\s*(export\s+)?(declare\s+)?(type|interface|class)\s+Problem\b(?!\s*=\s*G\.\w+;)/m,
  VISIT_LABEL: /\b(const|let|var)\s+VISIT_LABEL\b|\bVISIT_LABEL\s*:\s*Record</,
  initials: /\bfunction\s+initials\s*[(<]|\b(const|let|var)\s+initials\s*=\s*(async\s*)?(\([^)]*\)|\w+)\s*=>|\b(const|let|var)\s+initials\s*=\s*function/,
  age: /\bfunction\s+age\s*[(<]|\b(const|let|var)\s+age\s*=\s*(\([^)]*\)|\w+)\s*=>|\b(const|let|var)\s+age\s*=\s*function/,
};
function scan(roots) {
  const files = roots.flatMap((r) => sources(r)).map((p) => [p, readFileSync(p, "utf8")]);
  return Object.fromEntries(Object.entries(DEF).map(([n, re]) => [n, files.filter(([, s]) => re.test(s)).map(([p]) => p.replace(FE, ""))]));
}
const ROOTS = [join(FE, "apps"), join(FE, "packages")];

test("FEF-013 AC3: QueueEntry, PatientSummary and Problem each defined once (domain or generated api-client)", () => {
  const found = scan(ROOTS);
  assert.deepEqual(found.QueueEntry, ["packages/domain/src/types.ts"]);
  assert.deepEqual(found.PatientSummary, ["packages/domain/src/types.ts"]);
  assert.deepEqual(found.Problem, ["packages/api-client/src/generated/types.ts"]);
});

test("FEF-013 AC4: VISIT_LABEL, initials() and age() each exist once, in packages/domain", () => {
  const found = scan(ROOTS);
  assert.deepEqual(found.VISIT_LABEL, ["packages/domain/src/status.ts"]);
  assert.deepEqual(found.initials, ["packages/domain/src/format.ts"]);
  assert.deepEqual(found.age, ["packages/domain/src/format.ts"]);
});

test("FEF-013 AC3/AC4 negative: a planted duplicate of each name is detected (scanner bites)", () => {
  const dir = mkdtempSync(join(tmpdir(), "qa6-dup-"));
  try {
    mkdirSync(join(dir, "app"));
    writeFileSync(join(dir, "app/dup.tsx"), [
      "export interface QueueEntry { id: string }", "type PatientSummary = { id: string };", "export type Problem = { title: string };",
      'const VISIT_LABEL = { walk_in: "Walk in" };', "export const initials = (n: string) => n[0];", "function age(d: string) { return 1; }",
    ].join("\n"));
    const found = scan([...ROOTS, dir]);
    for (const n of Object.keys(DEF)) assert.equal(found[n].length, 2, `${n}: ${found[n].join(", ")}`);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

// Stricter than the AC's name grep: the age-from-dob arithmetic itself should not be re-implemented under another name.
test("FEF-013 AC4 strict: no inline age-from-dob arithmetic outside packages/domain", { todo: "known-defect-FEF-013: age logic re-implemented in arrivals, nursing, RegistryDialogs, NewAppointmentDialog" }, () => {
  const hits = sources(join(FE, "apps")).filter((p) => /getFullYear\(\)\s*-\s*(\w+\.getFullYear\(\)|y\b)|31557600000|365\.25\s*\*/.test(readFileSync(p, "utf8"))).map((p) => p.replace(FE, ""));
  assert.deepEqual(hits, [], `age logic still re-implemented in: ${hits.join(", ")}`);
});
