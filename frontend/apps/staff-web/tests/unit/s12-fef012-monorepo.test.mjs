// FEF-012: monorepo layout, lint boundaries, Turborepo pipeline, CODEOWNERS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { ESLint } from "eslint";

const app = new URL("../../", import.meta.url).pathname;
const fe = new URL("../../../../", import.meta.url).pathname; // frontend/ (workspace root)
const repo = new URL("../../../../../", import.meta.url).pathname;
const errs = (r, rule) => r.messages.filter((m) => m.severity === 2 && m.ruleId === rule);

test("AC2: a feature folder importing another feature fails boundaries/dependencies (fixture)", async () => {
  const [r] = await new ESLint({ cwd: app, ignore: false }).lintFiles(["lint-fixtures/features/patients/bad-cross-feature.ts"]);
  assert.equal(errs(r, "boundaries/dependencies").length, 2, JSON.stringify(r.messages));
  const [ok] = await new ESLint({ cwd: app, ignore: false }).lintFiles(["lint-fixtures/features/patients/ok-own-folder.ts"]);
  assert.equal(errs(ok, "boundaries/dependencies").length, 0, JSON.stringify(ok.messages));
});

test("AC2: real app feature folders: new cross-feature edge fails; own folder, shared ui, app/lib and packages pass", async () => {
  const eslint = new ESLint({ cwd: app });
  const at = "app/components/arrivals/X.tsx";
  const [bad] = await eslint.lintText('import { age } from "../consult/shared";\nexport const a = age;\n', { filePath: at });
  assert.equal(errs(bad, "boundaries/dependencies").length, 1, JSON.stringify(bad.messages));
  // FEF-013 removed the frozen baseline: the three old edges fail like any other.
  for (const [file, code] of [["app/components/arrivals/X.tsx", 'import { TenderRows } from "../checkout/TenderRows";'],
    ["app/components/myday/X.tsx", 'import { age } from "../consult/shared";'], ["app/components/templates/X.tsx", 'import { RxPad } from "../consult/RxPad";']]) {
    const [r] = await eslint.lintText(`${code}\nexport const v = 1;\n`, { filePath: file });
    assert.equal(errs(r, "boundaries/dependencies").length, 1, file + JSON.stringify(r.messages));
  }
  const okCode = [
    'import { PAYMENT_METHODS } from "@nabd/domain";', // FEF-013: shared rules come from packages/domain
    'import { CheckInDialog } from "./CheckInDialog";',
    'import { Button } from "../ui/Button";',
    'import { formatClinicDate } from "../../lib/clinicTime";',
    'import type { Invoice } from "@nabd/api-client/types";',
    "export const x = [PAYMENT_METHODS, CheckInDialog, Button, formatClinicDate] as unknown as Invoice;",
  ].join("\n");
  const [ok] = await eslint.lintText(okCode, { filePath: at });
  assert.equal(errs(ok, "boundaries/dependencies").length, 0, JSON.stringify(ok.messages));
});

test("AC3: packages/ui importing api-client (bare or relative) or calling fetch fails lint", async () => {
  const eslint = new ESLint({ cwd: fe, ignore: false });
  const [imp] = await eslint.lintFiles(["packages/ui/lint-fixtures/bad-import.ts"]);
  assert.equal(errs(imp, "boundaries/dependencies").length, 2, JSON.stringify(imp.messages));
  const [f] = await eslint.lintFiles(["packages/ui/lint-fixtures/bad-fetch.ts"]);
  assert.equal(errs(f, "no-restricted-globals").length, 1);
  assert.equal(errs(f, "no-restricted-properties").length, 1);
  const lint = async (code, filePath) => (await new ESLint({ cwd: fe }).lintText(code, { filePath }))[0];
  for (const p of ["packages/tokens/src/x.ts", "packages/domain/src/x.ts"]) {
    assert.equal(errs(await lint('export const l = () => window.fetch("/v1");', p), "no-restricted-properties").length, 1, p);
  }
  const client = await lint('export const l = () => globalThis.fetch("/v1");\nexport const m = () => fetch("/v1");', "packages/api-client/x.ts");
  assert.equal(client.messages.filter((m) => m.severity === 2).length, 0, "only api-client may call fetch");
  assert.equal((await lint('export {};', "packages/ui/src/index.ts")).messages.length, 0);
});

test("AC1/AC4: workspace + turbo pipeline declared with inputs/outputs; packages scaffolded; npm lockfile gone", () => {
  assert.match(readFileSync(fe + "pnpm-workspace.yaml", "utf8"), /"apps\/\*"[\s\S]*"packages\/\*"/);
  const turbo = JSON.parse(readFileSync(fe + "turbo.json", "utf8").replace(/^\s*\/\/.*$/gm, "")); // turbo.json allows // comments
  for (const t of ["typegen", "typecheck", "lint", "test", "build", "e2e"]) assert.ok(turbo.tasks[t], t);
  assert.deepEqual(turbo.tasks.build.outputs, [".next/**", "!.next/cache/**"]);
  assert.equal(turbo.tasks.e2e.cache, false);
  assert.ok(turbo.tasks.test.env.includes("NABD_REPO_INPUTS"), "repo files the unit tests read key the cache");
  assert.ok(!existsSync(fe + "package-lock.json"));
  assert.ok(existsSync(fe + "pnpm-lock.yaml"));
  for (const p of ["ui", "tokens", "domain", "api-client"]) {
    const pkg = JSON.parse(readFileSync(`${fe}packages/${p}/package.json`, "utf8"));
    assert.equal(pkg.name, `@nabd/${p}`);
    assert.ok(pkg.scripts.lint && pkg.scripts.typecheck, p);
  }
  assert.ok(existsSync(fe + "packages/api-client/src/generated/.gitkeep"), "OPS-035 writes the generated client here");
  const staff = JSON.parse(readFileSync(app + "package.json", "utf8"));
  assert.equal(staff.dependencies["@nabd/api-client"], "workspace:*");
});

test("AC4: CI keeps the Turborepo cache on the runner (no remote SaaS cache)", () => {
  const ci = readFileSync(repo + ".github/workflows/ci.yml", "utf8");
  assert.match(ci, /actions\/cache@v4[\s\S]*path: frontend\/\.turbo\/cache/);
  assert.match(ci, /pnpm turbo run typecheck lint check:permissions/);
  assert.match(ci, /pnpm turbo run test/);
  assert.doesNotMatch(ci, /TURBO_TOKEN|TURBO_TEAM|package-lock/);
});

test("AC5: CODEOWNERS makes the Frontend squad own packages/ui and packages/tokens; code-owner review is required", () => {
  const lines = readFileSync(repo + ".github/CODEOWNERS", "utf8").split("\n").map((l) => l.trim().split(/\s+/));
  for (const p of ["/frontend/packages/ui/", "/frontend/packages/tokens/"]) {
    const l = lines.filter(([path]) => path === p);
    assert.equal(l.length, 1, p);
    assert.ok(l[0].includes("@nabd/frontend"), p);
  }
  const bp = JSON.parse(readFileSync(repo + ".github/branch-protection.json", "utf8"));
  assert.equal(bp.required_pull_request_reviews.require_code_owner_reviews, true);
});

test("audit gate reads `pnpm audit --json` (advisories shape) and honours the allowlist", async () => {
  const { evaluate } = await import("../../scripts/audit-gate.mjs");
  const a = { advisories: { 1: { module_name: "m", severity: "critical", github_advisory_id: "GHSA-p", title: "t" }, 2: { module_name: "n", severity: "moderate", github_advisory_id: "GHSA-q" } } };
  assert.deepEqual(evaluate(a, []).failures.map((f) => f.id), ["GHSA-p"]);
  assert.equal(evaluate(a, [{ id: "GHSA-p", expires: "2999-01-01" }]).failures.length, 0);
});
