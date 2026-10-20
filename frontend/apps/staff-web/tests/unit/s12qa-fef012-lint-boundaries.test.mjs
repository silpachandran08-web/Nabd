// QA-5 (S12) FEF-012: independent negative/positive lint samples for the boundary rules (AC2, AC3).
// Each case lints an in-memory sample at a real path, so the shipped eslint configs are what is tested.
import { test } from "node:test";
import assert from "node:assert/strict";
import { ESLint } from "eslint";

const app = new URL("../../", import.meta.url).pathname;    // frontend/apps/staff-web
const fe = new URL("../../../../", import.meta.url).pathname; // frontend/
const appLint = new ESLint({ cwd: app });
const feLint = new ESLint({ cwd: fe });
const errors = async (eslint, code, filePath) => (await eslint.lintText(code, { filePath }))[0].messages.filter((m) => m.severity === 2);
const ruleErrors = async (eslint, code, filePath, rules) =>
  (await errors(eslint, code, filePath)).filter((m) => rules.includes(m.ruleId));
const BOUNDARY = ["boundaries/dependencies", "boundaries/element-types"];
const FETCH = ["no-restricted-globals", "no-restricted-properties", "no-restricted-syntax"];

// ---------- AC2: feature -> feature ----------
const FEATURE = "app/components/setup/QaSample.tsx";

test("AC2 neg: feature (setup) importing another feature (nursing) by relative path fails", async () => {
  const e = await ruleErrors(appLint, 'import { VaccinePanel } from "../nursing/VaccinePanel";\nexport const x = VaccinePanel;\n', FEATURE, BOUNDARY);
  assert.equal(e.length, 1, JSON.stringify(e));
});

test("AC2 neg: re-export from another feature fails", async () => {
  const e = await ruleErrors(appLint, 'export { VaccinePanel } from "../nursing/VaccinePanel";\n', FEATURE, BOUNDARY);
  assert.equal(e.length, 1, JSON.stringify(e));
});

test("AC2 neg: dynamic import of another feature fails", async () => {
  const e = await ruleErrors(appLint, 'export const load = () => import("../nursing/VaccinePanel");\n', FEATURE, BOUNDARY);
  assert.equal(e.length, 1, JSON.stringify(e));
});

test("AC2 neg: feature importing another feature through the tsconfig alias @/ fails", async () => {
  const e = await ruleErrors(appLint, 'import { VaccinePanel } from "@/app/components/nursing/VaccinePanel";\nexport const x = VaccinePanel;\n', FEATURE, BOUNDARY);
  assert.equal(e.length, 1, "alias import across features must fail too: " + JSON.stringify(e));
});

test("AC2 neg: frozen baseline edge is one-way (checkout -> arrivals still fails)", async () => {
  const e = await ruleErrors(appLint, 'import { CheckInDialog } from "../arrivals/CheckInDialog";\nexport const x = CheckInDialog;\n', "app/components/checkout/QaSample.tsx", BOUNDARY);
  assert.equal(e.length, 1, JSON.stringify(e));
});

test("AC2 neg: whole-app lint fails when a bad feature file is present (exit code path)", async () => {
  const r = await appLint.lintText('import { VaccinePanel } from "../nursing/VaccinePanel";\nexport const x = VaccinePanel;\n', { filePath: "app/components/staff/QaSample.tsx" });
  assert.ok(r[0].errorCount > 0, "errorCount > 0 makes `eslint .` exit 1");
});

test("AC2 pos: own folder, shared ui, app/lib and packages pass", async () => {
  const code = [
    'import { PharmacyPanel } from "./PharmacyPanel";',
    'import { Dialog } from "../ui/Dialog";',
    'import { formatClinicDate } from "../../lib/clinicTime";',
    'import type { Invoice } from "@nabd/api-client/types";',
    "export const x = [PharmacyPanel, Dialog, formatClinicDate] as unknown as Invoice;",
  ].join("\n");
  assert.deepEqual(await ruleErrors(appLint, code, FEATURE, BOUNDARY), []);
});

// ---------- AC3: packages/ui ----------
const UI = "packages/ui/src/QaSample.ts";

for (const [name, code] of [
  ["bare import", 'import { createClient } from "@nabd/api-client";\nexport const c = createClient;\n'],
  ["subpath type import", 'import type { Invoice } from "@nabd/api-client/types";\nexport type I = Invoice;\n'],
  ["relative import", 'import type { Invoice } from "../../api-client/types.ts";\nexport type I = Invoice;\n'],
  ["re-export", 'export { createClient } from "@nabd/api-client";\n'],
  ["dynamic import", 'export const load = () => import("@nabd/api-client");\n'],
]) {
  test(`AC3 neg: packages/ui ${name} of api-client fails`, async () => {
    const e = await ruleErrors(feLint, code, UI, BOUNDARY);
    assert.ok(e.length >= 1, JSON.stringify(e));
  });
}

test("AC3 neg: packages/ui .tsx component importing api-client fails", async () => {
  const e = await ruleErrors(feLint, 'import { createClient } from "@nabd/api-client";\nexport const c = createClient;\n', "packages/ui/src/Qa.tsx", BOUNDARY);
  assert.ok(e.length >= 1, JSON.stringify(e));
});

for (const [name, code] of [
  ["fetch()", 'export const l = () => fetch("/v1/patients");\n'],
  ["globalThis.fetch", 'export const l = () => globalThis.fetch("/v1/patients");\n'],
  ["window.fetch", 'export const l = () => window.fetch("/v1/patients");\n'],
  ["self.fetch", 'export const l = () => self.fetch("/v1/patients");\n'],
  ["fetch passed as value", "export const f = fetch;\n"],
  ['globalThis["fetch"]', 'export const l = () => globalThis["fetch"]("/v1/patients");\n'],
  ["destructured from globalThis", 'const { fetch: f } = globalThis;\nexport const l = () => f("/v1/patients");\n'],
]) {
  test(`AC3 neg: packages/ui calling ${name} fails`, async () => {
    const e = await ruleErrors(feLint, code, UI, FETCH);
    assert.ok(e.length >= 1, JSON.stringify(e));
  });
}

test("AC3 pos: api-client may call fetch; ui without fetch/api-client is clean", async () => {
  assert.deepEqual(await errors(feLint, 'export const l = () => fetch("/v1");\nexport const m = () => globalThis.fetch("/v1");\n', "packages/api-client/src/QaSample.ts"), []);
  assert.deepEqual(await errors(feLint, 'import { x } from "./index";\nexport const y = x;\n', UI).then((e) => e.filter((m) => [...BOUNDARY, ...FETCH].includes(m.ruleId))), []);
});
