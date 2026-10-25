// IDC-003: locked-clause rule (mirrors the server), language labels, re-acknowledgement text, step-up code shape.
import { test } from "node:test";
import assert from "node:assert/strict";
const { lockedClausesIntact, languageLabel, reackText, isStepUpCode, currentOf, historyOf } = await import("../../app/lib/privacyNotice.ts");

const clauses = ["[C1]", "[C2]", "[C3]"];
const body = "# Clinic\n\n[C1]\n\n[C2]\n\n[C3]\n\nGrievance contact: Dr X";

test("adding text around locked clauses is allowed", () => {
  assert.equal(lockedClausesIntact(body, clauses), true);
  assert.equal(lockedClausesIntact("intro\n" + body + "\nmore", clauses), true);
  assert.equal(lockedClausesIntact("[C1] extra [C2] more [C3]", clauses), true);
});
test("deleting, rewording or reordering a locked clause is refused", () => {
  assert.equal(lockedClausesIntact(body.replace("[C2]", ""), clauses), false);
  assert.equal(lockedClausesIntact(body.replace("[C2]", "[C2 changed]"), clauses), false);
  assert.equal(lockedClausesIntact("[C2]\n[C1]\n[C3]", clauses), false);
  assert.equal(lockedClausesIntact("[C1] [C2] [C2]", clauses), false);
});
test("no locked clauses means anything goes", () => assert.equal(lockedClausesIntact("", []), true));
test("language labels fall back to the code", () => {
  assert.equal(languageLabel("hi"), "Hindi"); assert.equal(languageLabel("kn"), "Kannada"); assert.equal(languageLabel("te"), "Telugu");
  assert.equal(languageLabel("xx"), "xx");
});
test("re-acknowledgement text appears only when needed", () => {
  assert.equal(reackText(undefined), null);
  assert.equal(reackText({ needsReAcknowledgement: false, acceptedVersion: "2", currentVersion: "2", language: "en" }), null);
  assert.match(reackText({ needsReAcknowledgement: true, acceptedVersion: "1", currentVersion: "2", language: "en" }), /v1.*v2/);
});
test("step-up code is exactly six digits", () => {
  assert.equal(isStepUpCode("123456"), true);
  for (const bad of ["", "12345", "1234567", "12345a", " 12345"]) assert.equal(isStepUpCode(bad), false);
});
test("current and history pick by language", () => {
  const list = [{ language: "en", version: "2", current: true }, { language: "en", version: "1", current: false }, { language: "hi", version: "1", current: true }];
  assert.equal(currentOf(list, "en").version, "2");
  assert.equal(historyOf(list, "en").length, 2);
  assert.equal(currentOf(list, "te"), undefined);
});
