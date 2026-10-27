// FEF-039: the consult workspace is extracted to the `consult` catalogue; clinical terms live once in the glossary (packages/i18n/glossary + messages/<lang>/glossary.json)
// and every screen references the term id. Checks: 0 literal-string lint violations, every key used exists, ICU parses, glossary ids are used from two places,
// no catalogue entry repeats a glossary text, unreviewed translations cannot ship, and CODEOWNERS makes the clinical reviewers approve glossary changes.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { ESLint } from "eslint";
import { pseudoMessage } from "@nabd/i18n";

const app = new URL("../../", import.meta.url).pathname;
const pkg = new URL("../../../../packages/i18n/", import.meta.url).pathname;
const repo = new URL("../../../../../", import.meta.url).pathname;
const read = (p) => JSON.parse(readFileSync(p, "utf8"));
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, `${p}${k}.`) : [[`${p}${k}`, v]]));
const walk = (d) => readdirSync(d).flatMap((f) => { const p = `${d}/${f}`; return statSync(p).isDirectory() ? walk(p) : [p]; });
const FILES = [...walk(`${app}app/components/consult`), ...walk(`${app}app/(clinic)/consult`)].filter((f) => /\.tsx?$/.test(f));
const consult = Object.fromEntries(flat(read(`${pkg}messages/en/consult.json`)));
const glossary = Object.fromEntries(flat(read(`${pkg}messages/en/glossary.json`)));
const terms = read(`${pkg}glossary/terms.json`).terms;
const src = (f) => readFileSync(f, "utf8");

test("0 literal-string violations in features/consult (the same ESLint rule as CI)", async () => {
  const eslint = new ESLint({ cwd: app, ignore: false });
  const bad = (await eslint.lintFiles(FILES.map((f) => f.replace(app, "")).filter((f) => f.endsWith(".tsx"))))
    .flatMap((r) => r.messages.filter((m) => m.ruleId === "i18next/no-literal-string" || (m.ruleId === "no-restricted-syntax" && /template literal/.test(m.message))).map((m) => `${r.filePath.replace(app, "")}:${m.line} ${m.message}`));
  assert.deepEqual(bad, []);
});

test("every literal catalogue key used in consult source exists (consult.* and glossary)", () => {
  let checked = 0;
  for (const f of FILES) {
    const s = src(f);
    for (const [, v, ns] of s.matchAll(/const (\w+) = useTranslations\("([^"]+)"\)/g)) {
      for (const m of s.matchAll(new RegExp(`\\b${v}\\("([A-Za-z0-9_.]+)"`, "g"))) {
        checked++;
        const table = ns === "glossary" ? glossary : consult;
        const full = ns === "glossary" ? m[1] : `${ns.replace(/^consult\./, "")}.${m[1]}`;
        assert.ok(full in table, `${f.replace(app, "")}: ${ns}.${m[1]} is not in the catalogue`);
      }
    }
  }
  assert.ok(checked > 400, `only ${checked} keys checked`);
});

test("dynamic keys resolve: allergy severities, soap steps, sig slots, pill and filter families", () => {
  for (const id of ["allergy.severity.mild", "allergy.severity.moderate", "allergy.severity.severe", "soap.reason", "soap.examine", "soap.assess", "soap.plan", "rx.slot.morning", "rx.slot.noon", "rx.slot.night"]) assert.ok(id in glossary, id);
  for (const k of ["stepRail.hint.reason", "stepRail.hint.close", "queueList.scope.mine", "queueList.filter.done", "queueList.source.walk_in", "statusBadge.in_consult", "allergyRegister.categories.drug", "allergyRegister.sources.clinician", "followUpPanel.interval.custom"]) assert.ok(k in consult, k);
});

test("every ICU message parses", () => {
  for (const [k, v] of [...Object.entries(consult), ...Object.entries(glossary)]) assert.doesNotThrow(() => pseudoMessage(v), `${k}: ${v}`);
});

test("glossary: every term has an id, a definition and an approved language list; ids match the English text file", () => {
  assert.deepEqual(Object.keys(terms).sort(), Object.keys(glossary).sort());
  for (const [id, t] of Object.entries(terms)) {
    assert.ok(t.definition.trim().length > 10, `${id} needs a definition`);
    assert.ok(Array.isArray(t.approved) && t.approved.includes("en"), `${id} approved list`);
  }
});

test("glossary: a translation file may only hold terms a clinician approved for that language", () => {
  const langs = readdirSync(`${pkg}messages`).filter((l) => l !== "en" && existsSync(`${pkg}messages/${l}/glossary.json`));
  for (const l of langs) for (const id of Object.keys(Object.fromEntries(flat(read(`${pkg}messages/${l}/glossary.json`))))) {
    assert.ok(terms[id], `${l}: ${id} is not a glossary term`);
    assert.ok(terms[id].approved.includes(l), `${l}: ${id} has a translation that is not listed as clinically approved in glossary/terms.json`);
  }
});

test("two places, one glossary id: the allergy label, allergy warning title and severities are referenced from several consult files", () => {
  const users = (id) => FILES.filter((f) => src(f).includes(`"${id}"`)).map((f) => f.replace(app, ""));
  assert.ok(users("allergy.label").length >= 4, `allergy.label used in ${users("allergy.label").join(", ")}`);
  assert.ok(users("allergy.warning.title").length >= 1 && src(`${app}app/components/consult/rx/RxPanel.tsx`).includes("WARNING_TITLE_ID"), "warning title id shared by RxWarnings and RxPanel");
  assert.ok(FILES.filter((f) => src(f).includes("allergy.severity.")).length >= 3, "severities");
  assert.ok(users("pregnancy.label").length >= 1 && users("condition.chronic").length >= 2);
});

test("a clinical term is never repeated as catalogue text: no consult entry equals a glossary text", () => {
  const gt = new Map(Object.entries(glossary).map(([id, t]) => [t, id]));
  const dup = Object.entries(consult).filter(([, v]) => gt.has(v)).map(([k, v]) => `consult.${k} = "${v}" is glossary term ${gt.get(v)}`);
  assert.deepEqual(dup, []);
});

test("CODEOWNERS: glossary changes need the clinical reviewer group", () => {
  const co = readFileSync(`${repo}.github/CODEOWNERS`, "utf8").split("\n").filter((l) => !l.startsWith("#"));
  for (const path of ["/frontend/packages/i18n/glossary/", "/frontend/packages/i18n/messages/*/glossary.json"]) {
    const line = co.find((l) => l.startsWith(path));
    assert.ok(line && /@nabd\/clinical-owners/.test(line), `${path} needs @nabd/clinical-owners`);
  }
});

test("shared domain/document labels shown in the consult UI have catalogue entries (timeline, note fields, documents, routes)", () => {
  const domain = readFileSync(`${repo}frontend/packages/domain/src/consultFlow.ts`, "utf8");
  const tl = src(`${app}app/components/consult/VisitTimeline.tsx`);
  const body = domain.slice(domain.indexOf("export function buildTimeline"));
  for (const m of body.matchAll(/(?:label|sub): "([^"]+)"/g)) assert.ok(tl.includes(`"${m[1]}"`), `timeline text "${m[1]}" has no catalogue mapping in VisitTimeline.tsx`);
  for (const k of tl.matchAll(/"((?:event|sub)\.\w+)"/g)) assert.ok(`visitTimeline.${k[1]}` in consult, k[1]);
  const docs = src(`${app}app/components/consult/documents.ts`);
  for (const m of docs.matchAll(/key: "(\w+)", label:/g)) assert.ok(`documents.field.${m[1]}` in consult || ["diagnosis", "assessment", "plan", "medicines"].includes(m[1]), `document field ${m[1]}`);
  for (const t of ["visit_summary", "referral", "certificate"]) assert.ok(`documents.label.${t}` in consult, t);
  const routes = src(`${repo}frontend/packages/domain/src/rxPadLogic.ts`).match(/ROUTES = \[([^\]]+)\]/)[1].match(/"(\w+)"/g).map((r) => r.slice(1, -1).toLowerCase());
  for (const r of [...routes, "infusion"]) assert.ok(`rx.routes.${r}` in glossary, r);
});
