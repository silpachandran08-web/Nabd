// CLN-032: the Rx pad's drug-drug interaction and duplicate-therapy cards. Pure view-model tests plus source gates.
// TEST DATA ONLY: every drug, severity, mechanism and source below is synthetic, written for this test; none of it is clinical content.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { APP } from "./consultSource.mjs";
import { decisionMatch, genericWarnings, interactionCards, isInteractionWarning, lineNotChecked } from "../../app/components/consult/rx/interactionWarnings.ts";
import { openWarningCount, rxFingerprint } from "../../app/components/consult/rx/rxLogic.ts";

const pkg = new URL("../../../../packages/i18n/", import.meta.url).pathname;
const read = (p) => JSON.parse(readFileSync(p, "utf8"));
const src = (rel) => readFileSync(join(APP, rel), "utf8");
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, `${p}${k}.`) : [[`${p}${k}`, v]]));

const warning = (over = {}) => ({
  kind: "ddi", severity: "major", message: "Major interaction with Test A: alpha + beta", matched: "alpha + beta", requiresDecision: true, resolved: false,
  reasonOptions: [{ code: "benefit_outweighs_risk", label: "Clinical benefit outweighs risk" }], decision: null,
  otherLineRef: null, otherDrug: "Test A", otherFrom: "rx", mechanism: "TEST DATA mechanism", management: "TEST DATA management", source: "SYNTHETIC-TEST-KB 2026-10", sourceRef: "TEST-REF-1", totalDailyDose: null,
  ...over,
});
const allergy = { kind: "allergy", severity: "severe", message: "penicillin (severe)", matched: "penicillin", requiresDecision: true, resolved: false, reasonOptions: [], decision: null };
const line = (over = {}) => ({ drugName: "Test B", dosage: "", frequency: "", duration: "", instructions: "", ...over });

test("AC1: a major interaction with an active medicine is an open card naming the other medicine, with the KB source behind Why?", () => {
  const [card, ...rest] = interactionCards(line({ warnings: [warning()], interactionCheck: "checked" }));
  assert.equal(rest.length, 0);
  assert.equal(card.tone, "danger");
  assert.equal(card.open, true);
  assert.equal(card.advisory, false);
  assert.equal(card.severityId, "interaction.severity.major");
  assert.equal(card.other, "rx");
  assert.equal(card.otherLine, null);
  assert.equal(card.hasWhy, true);
  assert.equal(card.warning.source, "SYNTHETIC-TEST-KB 2026-10");
  assert.equal(card.key, "ddi:alpha + beta");
});

test("AC2 / AC3: a duplicate from another line of the draft points at that line (1-based); the total daily dose is carried as sent", () => {
  const dup = warning({ kind: "duplicate", matched: "same class: Test class", otherFrom: "draft", otherLineRef: 0, mechanism: null, management: null });
  const dose = warning({ kind: "duplicate", severity: "moderate", requiresDecision: false, resolved: true, matched: "same ingredient: gamma", otherFrom: "draft", otherLineRef: 2, totalDailyDose: "3450 mg/day = 650 mg x 3 (B) + 500 mg x 3 (A)" });
  const cards = interactionCards(line({ warnings: [dose, dup] }));
  assert.deepEqual(cards.map((c) => [c.warning.matched, c.other, c.otherLine]), [["same class: Test class", "draft", 1], ["same ingredient: gamma", "draft", 3]]);
  assert.equal(cards[1].warning.totalDailyDose, "3450 mg/day = 650 mg x 3 (B) + 500 mg x 3 (A)");
  assert.equal(cards[0].hasWhy, true, "the source alone is worth the expander");
  assert.equal(interactionCards(line({ warnings: [warning({ mechanism: null, management: null, source: null, sourceRef: null })] }))[0].hasWhy, false);
});

test("AC4: a minor (or moderate) interaction is advisory: a warn card that is not open and is not counted as blocking signing", () => {
  for (const severity of ["minor", "moderate"]) {
    const w = warning({ severity, requiresDecision: false, resolved: true, reasonOptions: [] });
    const [card] = interactionCards(line({ warnings: [w] }));
    assert.deepEqual([card.tone, card.open, card.advisory, card.severityId], ["warn", false, true, `interaction.severity.${severity}`]);
    assert.equal(openWarningCount([line({ warnings: [w] })]), 0);
  }
  assert.equal(openWarningCount([line({ warnings: [warning()] })]), 1, "a major one does block until decided");
  assert.equal(openWarningCount([line({ warnings: [warning({ resolved: true, decision: { kind: "ddi", reasonCode: "x", reasonLabel: "X", note: null, by: null, byName: "Dr T", at: null } })] })]), 0);
});

test("cards are ordered most severe first, an open one before a decided one; a recorded decision turns the card to info", () => {
  const decided = warning({ matched: "a + b", resolved: true, decision: { kind: "ddi", reasonCode: "x", reasonLabel: "X", note: null, by: null, byName: "Dr T", at: null } });
  const cards = interactionCards(line({ warnings: [warning({ severity: "minor", requiresDecision: false, resolved: true, matched: "m" }), decided, warning({ matched: "c + d" }), warning({ severity: "contraindicated", matched: "e + f" })] }));
  assert.deepEqual(cards.map((c) => c.warning.matched), ["e + f", "c + d", "a + b", "m"]);
  assert.deepEqual(cards.map((c) => c.tone), ["danger", "danger", "info", "warn"]);
  assert.equal(interactionCards(line({ warnings: [warning({ severity: "unheard-of" })] }))[0].severityId, null, "an unknown severity is shown as sent, never mapped to a known one");
});

test("the interaction cards and the generic cards split the warnings between them: nothing shown twice, nothing dropped", () => {
  const item = line({ warnings: [allergy, warning(), warning({ kind: "duplicate", matched: "same class: T" })] });
  assert.deepEqual(genericWarnings(item).map((w) => w.kind), ["allergy"]);
  assert.deepEqual(interactionCards(item).map((c) => c.warning.kind).sort(), ["ddi", "duplicate"]);
  assert.ok(isInteractionWarning({ kind: "ddi" }) && isInteractionWarning({ kind: "duplicate" }) && !isInteractionWarning({ kind: "allergy" }) && !isInteractionWarning({ kind: "controlled" }));
  assert.deepEqual(genericWarnings(line()), []);
  assert.match(src("components/consult/RxWarnings.tsx"), /const warnings = genericWarnings\(item\)/);
});

test("a decision on an interaction or duplicate names its warning; allergy, pregnancy and controlled decisions stay per kind as before", () => {
  assert.equal(decisionMatch(warning()), "alpha + beta");
  assert.equal(decisionMatch(warning({ kind: "duplicate", matched: "same class: T" })), "same class: T");
  assert.equal(decisionMatch(allergy), undefined);
  assert.match(src("components/consult/rx/useRxSigning.ts"), /matched: decisionMatch\(override\.warning\)/);
  assert.match(src("components/consult/rx/useRxPad.ts"), /matched: overrides\.matched/);
});

test("EMPTY KNOWLEDGE BASE: no card, no note, nothing at all: the pad looks exactly as before and never says 'no interactions'", () => {
  for (const item of [line(), line({ warnings: [] }), line({ warnings: [], interactionCheck: null }), line({ drugId: "x", coded: true, warnings: [], interactionCheck: null }), line({ warnings: [allergy], interactionCheck: null })]) {
    assert.deepEqual(interactionCards(item), []);
    assert.equal(lineNotChecked(item), false);
  }
  // a line that WAS checked and has no finding also shows nothing: there is no "all clear" state to mistake an unchecked line for
  assert.deepEqual(interactionCards(line({ warnings: [], interactionCheck: "checked" })), []);
  assert.equal(lineNotChecked(line({ warnings: [], interactionCheck: "checked" })), false);
  const component = src("components/consult/RxInteractionWarnings.tsx");
  assert.match(component, /if \(cards\.length === 0 && !notChecked\) return null;/);
  const texts = flat(read(`${pkg}messages/en/consult.json`).rxInteractions).map(([, v]) => v).join("\n");
  assert.doesNotMatch(texts, /no (known )?(drug )?interactions?|none found|no duplicate|nothing found|\bsafe\b|all clear/i);
  assert.match(texts, /Not checked/);
});

test("with a knowledge base active, a saved free-text line says it was NOT checked; an edited line waits for the server", () => {
  assert.equal(lineNotChecked(line({ warnings: [], interactionCheck: "not_checked" })), true);
  assert.equal(lineNotChecked(line({ warnings: [allergy], interactionCheck: "not_checked" })), true);
  assert.equal(lineNotChecked(line({ warnings: undefined, interactionCheck: "not_checked" })), false, "warnings are dropped from view while the line is being edited");
  assert.equal(lineNotChecked(line({ warnings: [], interactionCheck: "checked" })), false);
});

test("the cards are their own component, mounted once in the Rx panel next to the generic warnings; the server-only fields never count as an unsaved change", () => {
  const panel = src("components/consult/rx/RxPanel.tsx");
  assert.equal((panel.match(/<RxInteractionWarnings /g) ?? []).length, 1);
  assert.match(panel, /<RxInteractionWarnings item=\{item\} locked=\{signed\} onOverride=\{mayDecideRx \? /, "the override button is absent for someone who may not decide");
  const component = src("components/consult/RxInteractionWarnings.tsx");
  assert.match(component, /\{onOverride && <button/);
  assert.match(component, /<details/);
  const base = rxFingerprint([line()]);
  assert.equal(rxFingerprint([line({ interactionCheck: "not_checked", warnings: [warning()] })]), base);
});

test("the glossary carries the two warning titles and the four severities; the catalogue has every 'with' variant", () => {
  const glossary = Object.fromEntries(flat(read(`${pkg}messages/en/glossary.json`)));
  for (const id of ["interaction.warning.title", "duplicate.warning.title", "interaction.severity.contraindicated", "interaction.severity.major", "interaction.severity.moderate", "interaction.severity.minor"]) assert.ok(id in glossary, id);
  const consult = Object.fromEntries(flat(read(`${pkg}messages/en/consult.json`)));
  for (const k of ["with.draft", "with.rx", "with.reported", "totalDailyDose", "why", "mechanism", "management", "source", "notChecked"]) assert.ok(`rxInteractions.${k}` in consult, k);
  assert.match(src("components/consult/RxWarnings.tsx"), /ddi: "interaction\.warning\.title",\s+duplicate: "duplicate\.warning\.title"/);
});
