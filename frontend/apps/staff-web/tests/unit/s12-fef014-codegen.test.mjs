// FEF-014: Permission union, CI drift gate, rename-breaks-compilation proof, augmentation-file ratchet.
// (AC3, request-validating MSW, is tested in packages/api-client/test/s12-fef014-msw-validation.test.mjs.)
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = fileURLToPath(new URL("../../../../../", import.meta.url));
const APP = join(REPO, "frontend/apps/staff-web");
const CLIENT = join(REPO, "frontend/packages/api-client");
const GEN = join(CLIENT, "src/generated");
const SPEC = join(REPO, "api/openapi/core.yaml");
const gen = (...args) => spawnSync("python3", [join(REPO, "scripts/api/generate_client.py"), ...args], { encoding: "utf8" });
const tmp = () => mkdtempSync(join(tmpdir(), "fef014-"));

// Ratchet: may only go down. Lower it when a squad closes a gap; Phase 1 S5 target is 0 (AC4).
const AUGMENTATION_BASELINE = 13; // FEF-013: QueueEntry entry removed (domain owns the queue row shape)

test("AC1: Permission union = every authority in x-required-permission (public/authenticated excluded, a|b and SpEL split)", () => {
  const values = [...readFileSync(SPEC, "utf8").matchAll(/x-required-permission: "?(.+?)"?$/gm)].map((m) => m[1]);
  const want = [...new Set(values.flatMap((v) => v.match(/[a-z][a-z_]*:[A-Za-z_]+/g) ?? []))].sort();
  const types = readFileSync(join(GEN, "types.ts"), "utf8");
  const union = types.match(/export type Permission =\n((?:  \| ".+"\n?)+);/);
  assert.ok(union, "types.ts declares the Permission union");
  const got = [...union[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(got, want);
  assert.ok(got.includes("clinical:approve") && got.includes("setup:edit"), "split from `a | b` and from SpEL");
  assert.ok(!got.includes("public") && !got.includes("authenticated"));
  assert.match(readFileSync(join(APP, "app/lib/permissions.ts"), "utf8"), /export function can\(permission: AppPermission\)/, "can() is typed by it");
});

test("AC1: drift gate: committed output equals a fresh run; a hand edit (or a spec change without regenerating) fails --check", () => {
  const ok = gen("--check");
  assert.equal(ok.status, 0, ok.stdout + ok.stderr);
  const dir = tmp();
  try {
    cpSync(GEN, dir, { recursive: true });
    assert.equal(gen("--check", `--out=${dir}`).status, 0);
    writeFileSync(join(dir, "types.ts"), readFileSync(join(dir, "types.ts"), "utf8").replace('  | "billing:view"\n', ""));
    const stale = gen("--check", `--out=${dir}`);
    assert.equal(stale.status, 1);
    assert.match(stale.stdout, /generated client is stale \(types\.ts\)/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("AC1: CI runs the gate on every PR that touches the spec, the generator or the frontend", () => {
  const ci = readFileSync(join(REPO, ".github/workflows/ci.yml"), "utf8");
  assert.match(ci, /python3 scripts\/api\/generate_client\.py --check/);
  assert.match(ci, /- 'api\/\*\*'/);
  assert.match(ci, /frontend:\n(?:\s+- '.+'\n)*?\s+- 'scripts\/api\/\*\*'/, "generator changes trigger stage 5");
  const pkg = JSON.parse(readFileSync(join(CLIENT, "package.json"), "utf8"));
  assert.match(pkg.scripts.codegen, /generate_client\.py/);
  assert.ok(JSON.parse(readFileSync(join(REPO, "frontend/turbo.json"), "utf8").replace(/^\s*\/\/.*$/gm, "")).tasks.codegen, "turbo codegen task");
});

test("AC2: a response field renamed in the spec breaks every consumer in staff-web (tsc on a temp copy)", { timeout: 120_000 }, () => {
  const dir = tmp();
  try {
    const spec = readFileSync(SPEC, "utf8");
    const at = spec.indexOf("\n    InvoiceResponse:\n");
    const field = spec.indexOf("\n        balanceDue:\n", at);
    assert.ok(at > 0 && field > at, "fixture field found");
    writeFileSync(join(dir, "core.yaml"), spec.slice(0, field) + "\n        balanceOwed:\n" + spec.slice(field + "\n        balanceDue:\n".length));
    mkdirSync(join(dir, "src/generated"), { recursive: true });
    assert.equal(gen(`--spec=${join(dir, "core.yaml")}`, `--out=${join(dir, "src/generated")}`).status, 0);
    cpSync(join(CLIENT, "types.ts"), join(dir, "types.ts"));
    writeFileSync(join(dir, "tsconfig.json"), JSON.stringify({
      extends: join(APP, "tsconfig.json"),
      compilerOptions: { incremental: false, paths: { "@/*": [join(APP, "*")], "@nabd/api-client/types": [join(dir, "types.ts")] } },
    }));
    const r = spawnSync(join(APP, "node_modules/.bin/tsc"), ["-p", join(dir, "tsconfig.json"), "--noEmit"], { cwd: APP, encoding: "utf8" });
    const errors = r.stdout.split("\n").filter((l) => /error TS/.test(l));
    assert.notEqual(r.status, 0, "typecheck must fail");
    const consumers = ["app/checkout/[queueEntryId]/page.tsx", "app/checkout/otc/page.tsx"];
    for (const c of consumers) {
      const mine = errors.filter((l) => l.includes(c));
      assert.ok(mine.length > 0, `${c} must fail compilation:\n${errors.join("\n")}`);
      assert.ok(mine.every((l) => /balanceDue/.test(l)), `${c} fails only because of the rename:\n${mine.join("\n")}`);
    }
    assert.ok(errors.some((l) => l.includes("types.ts") && /balanceDue/.test(l)), "the augmentation entry names the vanished field too");
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("AC4: augmentation file entries are counted and only go down", () => {
  const src = readFileSync(join(CLIENT, "types.ts"), "utf8");
  const exported = [...src.matchAll(/^export type (\w+) = ([^\n]*)/gm)];
  const wired = exported.filter((m) => /^G\.\w+;$/.test(m[2]));
  const entries = exported.filter((m) => !/^G\.\w+;$/.test(m[2])).map((m) => m[1]);
  console.log(`augmentation entries: ${entries.length} (${entries.join(", ")}); wired: ${wired.length}`);
  assert.ok(entries.length <= AUGMENTATION_BASELINE, `augmentation grew to ${entries.length} (baseline ${AUGMENTATION_BASELINE}): close the gap in the spec instead`);
  assert.ok(wired.length >= 2);
});

test("AC4: augmentation file has 0 entries by the end of Phase 1 S5", { todo: `${AUGMENTATION_BASELINE} entries left; owners in packages/api-client/types.ts` }, () => {
  assert.equal(AUGMENTATION_BASELINE, 0);
});
