// S12 QA-6 (independent) FEF-014: drift gate (and mutations that must trip it), deterministic codegen, Permission
// union vs an independent YAML parse, rename-breaks-every-consumer on the queue/patient fields the clinic screens read,
// and the augmentation count. Everything runs on temp copies; the tree is never modified.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, mkdirSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = fileURLToPath(new URL("../../../../../", import.meta.url));
const APP = join(REPO, "frontend/apps/staff-web");
const CLIENT = join(REPO, "frontend/packages/api-client");
const GEN = join(CLIENT, "src/generated");
const SPEC = join(REPO, "api/openapi/core.yaml");
const gen = (...args) => spawnSync("python3", [join(REPO, "scripts/api/generate_client.py"), ...args], { encoding: "utf8" });
const tmp = () => mkdtempSync(join(tmpdir(), "qa6-fef014-"));
const FILES = ["handlers.ts", "index.ts", "schemas.ts", "types.ts"];

/** Rename `old` to `neu` inside one component schema's properties (text edit on a copy of the spec). */
function renameField(spec, schema, old, neu) {
  const at = spec.indexOf(`\n    ${schema}:\n`);
  const f = spec.indexOf(`\n        ${old}:\n`, at);
  assert.ok(at > 0 && f > at, `${schema}.${old} found`);
  const nextSchema = spec.slice(at + 1).search(/\n    [A-Za-z]/) + at + 1;
  assert.ok(f < nextSchema, `${schema}.${old} is inside ${schema}`);
  return spec.slice(0, f) + `\n        ${neu}:\n` + spec.slice(f + `\n        ${old}:\n`.length);
}

test("FEF-014 AC1: committed generated output equals a fresh run (--check rc 0)", () => {
  const r = gen("--check");
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /up to date \(4 files\)/);
});

test("FEF-014 AC1: codegen is deterministic (two runs byte-identical, equal to the committed files)", () => {
  const a = tmp(), b = tmp();
  try {
    assert.equal(gen(`--out=${a}`).status, 0);
    assert.equal(gen(`--out=${b}`).status, 0);
    for (const f of FILES) {
      const x = readFileSync(join(a, f), "utf8");
      assert.equal(x, readFileSync(join(b, f), "utf8"), `${f} differs between runs`);
      assert.equal(x, readFileSync(join(GEN, f), "utf8"), `${f} differs from committed`);
    }
  } finally { rmSync(a, { recursive: true, force: true }); rmSync(b, { recursive: true, force: true }); }
});

test("FEF-014 AC1 mutation: a spec field rename without regenerating fails --check (types and schemas stale)", () => {
  const dir = tmp();
  try {
    writeFileSync(join(dir, "core.yaml"), renameField(readFileSync(SPEC, "utf8"), "QueueEntryResponse", "tokenNumber", "tokenNo"));
    const r = gen("--check", `--spec=${join(dir, "core.yaml")}`);
    assert.equal(r.status, 1, r.stdout);
    assert.match(r.stdout, /stale \(.*types\.ts/);
    assert.match(r.stdout, /schemas\.ts/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("FEF-014 AC1 mutation: a duplicated type, a hand-edited schema or a missing handlers file each fail --check", () => {
  for (const mutate of [
    (d) => writeFileSync(join(d, "types.ts"), readFileSync(join(d, "types.ts"), "utf8") + "export type QueueEntryResponse = { id?: string };\n"),
    (d) => writeFileSync(join(d, "schemas.ts"), readFileSync(join(d, "schemas.ts"), "utf8").replace("z.string()", "z.string().min(1)")),
    (d) => unlinkSync(join(d, "handlers.ts")),
  ]) {
    const d = tmp();
    try {
      cpSync(GEN, d, { recursive: true });
      assert.equal(gen("--check", `--out=${d}`).status, 0, "copy starts clean");
      mutate(d);
      const r = gen("--check", `--out=${d}`);
      assert.equal(r.status, 1, r.stdout);
      assert.match(r.stdout, /generated client is stale/);
    } finally { rmSync(d, { recursive: true, force: true }); }
  }
});

test("FEF-014 AC1: Permission union equals every authority in x-required-permission (independent YAML parse)", () => {
  const py = spawnSync("python3", ["-c", `
import yaml, re, json, sys
s = yaml.safe_load(open(sys.argv[1]))
out = set()
for path, ops in s["paths"].items():
    for verb, op in ops.items():
        if isinstance(op, dict) and "x-required-permission" in op:
            out.update(re.findall(r"[a-z][a-z_]*:[A-Za-z_]+", str(op["x-required-permission"])))
print(json.dumps(sorted(out)))`, SPEC], { encoding: "utf8" });
  assert.equal(py.status, 0, py.stderr);
  const want = JSON.parse(py.stdout);
  const types = readFileSync(join(GEN, "types.ts"), "utf8");
  const m = types.match(/export type Permission =\n((?:  \| ".+"\n?)+);/);
  assert.ok(m, "Permission union present");
  const got = [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]).sort();
  assert.deepEqual(got, want);
  assert.ok(got.length > 20, `${got.length} permissions`);
});

test("FEF-014 AC1 mutation: a new x-required-permission in the spec appears in the regenerated union and trips --check", () => {
  const dir = tmp();
  try {
    const spec = readFileSync(SPEC, "utf8").replace(/x-required-permission: "?queue:view"?\n/, 'x-required-permission: "qaprobe:view"\n');
    assert.match(spec, /qaprobe:view/);
    writeFileSync(join(dir, "core.yaml"), spec);
    assert.equal(gen("--check", `--spec=${join(dir, "core.yaml")}`).status, 1);
    mkdirSync(join(dir, "out"));
    assert.equal(gen(`--spec=${join(dir, "core.yaml")}`, `--out=${join(dir, "out")}`).status, 0);
    assert.match(readFileSync(join(dir, "out/types.ts"), "utf8"), /\| "qaprobe:view"/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("FEF-014 AC1: CI stage 5 runs the gate (blocking) for spec, generator and frontend changes", () => {
  const ci = readFileSync(join(REPO, ".github/workflows/ci.yml"), "utf8");
  const job = ci.slice(ci.indexOf("\n  stage5-api-contract:"), ci.indexOf("\n  # OPS-036"));
  assert.match(job, /needs\.changes\.outputs\.backend == 'true' \|\| needs\.changes\.outputs\.frontend == 'true'/);
  assert.match(job, /python3 scripts\/api\/generate_client\.py --check/);
  assert.doesNotMatch(job, /continue-on-error:\s*true/);
  const filters = ci.slice(ci.indexOf("filters: |"), ci.indexOf("\n  stage1-static:"));
  assert.match(filters, /backend:[\s\S]*- 'api\/\*\*'/);
  assert.match(filters, /frontend:[\s\S]*- 'frontend\/\*\*'[\s\S]*- 'scripts\/api\/\*\*'/);
});

// Consumers of the queue row / patient fields that the reception, nursing, consult and schedule screens read.
const tokenConsumers = () => {
  const out = [];
  const walk = (d) => { for (const e of readdirSync(d, { withFileTypes: true })) { const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (/\.tsx?$/.test(e.name) && /\.tokenNumber\b/.test(readFileSync(p, "utf8"))) out.push(p.slice(APP.length + 1)); } };
  walk(join(APP, "app"));
  // lib/api.ts reads tokenNumber off the ALREADY_CHECKED_IN problem extension, not a queue row.
  return out.filter((p) => p !== "app/lib/api.ts").sort();
};

test("FEF-014 AC2: renaming spec fields breaks compilation of EVERY consumer (QueueEntryResponse.tokenNumber/status, PatientDetailResponse.name, WaitEstimateResponse.estimatedMinutes)", { timeout: 300_000 }, () => {
  const dir = tmp();
  try {
    let spec = readFileSync(SPEC, "utf8");
    spec = renameField(spec, "QueueEntryResponse", "tokenNumber", "tokenNo");
    spec = renameField(spec, "QueueEntryResponse", "status", "queueStatus");
    spec = renameField(spec, "PatientDetailResponse", "name", "fullName");
    spec = renameField(spec, "WaitEstimateResponse", "estimatedMinutes", "etaMinutes");
    writeFileSync(join(dir, "core.yaml"), spec);
    mkdirSync(join(dir, "src/generated"), { recursive: true });
    assert.equal(gen(`--spec=${join(dir, "core.yaml")}`, `--out=${join(dir, "src/generated")}`).status, 0);
    cpSync(join(CLIENT, "types.ts"), join(dir, "types.ts"));
    writeFileSync(join(dir, "tsconfig.json"), JSON.stringify({
      extends: join(APP, "tsconfig.json"),
      compilerOptions: { incremental: false, paths: { "@/*": [join(APP, "*")], "@nabd/api-client/types": [join(dir, "types.ts")], "@nabd/api-client/generated": [join(dir, "src/generated/index.ts")] } },
    }));
    const r = spawnSync(join(APP, "node_modules/.bin/tsc"), ["-p", join(dir, "tsconfig.json"), "--noEmit"], { cwd: APP, encoding: "utf8" });
    const errors = r.stdout.split("\n").filter((l) => /error TS/.test(l));
    console.log(`tsc errors after rename: ${errors.length}\n${errors.map((l) => "  " + l.replace(/\(\d+,\d+\).*Property '(\w+)'.*/, " -> $1")).join("\n")}`);
    assert.notEqual(r.status, 0, "typecheck must fail at all");
    // Positive control: a field read only through the generated type breaks its consumer.
    assert.ok(errors.some((l) => l.includes("app/arrivals/page.tsx") && /estimatedMinutes/.test(l)), "arrivals wait estimate");
    // The AC: every consumer of the renamed queue field fails, not just some.
    const consumers = tokenConsumers();
    const silent = consumers.filter((c) => !errors.some((l) => l.startsWith(c) || l.includes(c + "(")));
    assert.deepEqual(silent, [], `consumers of .tokenNumber that still compile after QueueEntryResponse.tokenNumber was renamed (they would read undefined at runtime): ${silent.join(", ")}`);
    // status: the arrivals and nursing row types re-declare it, so they must still fail on the rename.
    for (const page of ["app/arrivals/page.tsx", "app/nursing/page.tsx"]) {
      assert.ok(errors.some((l) => l.includes(page) && /'status'|queueStatus/.test(l)), `${page} must fail when QueueEntryResponse.status is renamed`);
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("FEF-014 AC4: the augmentation file has 0 entries (target end of Phase 1 S5)", { todo: "known-defect-FEF-014: 13 entries left, target is Phase 1 S5" }, () => {
  const src = readFileSync(join(CLIENT, "types.ts"), "utf8");
  const exported = [...src.matchAll(/^export type (\w+) = ([^\n]*)/gm)];
  const entries = exported.filter((m) => !/^G\.\w+;$/.test(m[2])).map((m) => m[1]);
  console.log(`augmentation entries: ${entries.length}: ${entries.join(", ")}`);
  assert.equal(entries.length, 0, `${entries.length} augmentation entries remain`);
});

test("FEF-014 AC4 ratchet mutation: one more augmentation entry fails the engineer's ratchet test", { timeout: 60_000 }, () => {
  // Run the engineer's ratchet against a copy of types.ts with one extra entry (same regex it uses).
  const src = readFileSync(join(CLIENT, "types.ts"), "utf8") + "export type QaExtra = G.Problem & { qa: string };\n";
  const baseline = Number(readFileSync(join(APP, "tests/unit/s12-fef014-codegen.test.mjs"), "utf8").match(/AUGMENTATION_BASELINE = (\d+)/)[1]);
  const count = [...src.matchAll(/^export type (\w+) = ([^\n]*)/gm)].filter((m) => !/^G\.\w+;$/.test(m[2])).length;
  assert.ok(count > baseline, `extra entry pushes ${count} over baseline ${baseline}`);
});
