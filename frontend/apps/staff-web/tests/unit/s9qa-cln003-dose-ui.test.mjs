// Independent QA (S9 CLN-003): pure dose-UI logic plus static checks of DoseCalculator.tsx (no browser in this file).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { weightAge, rateError, weightError } from "../../../../packages/domain/src/doseLogic.ts"; // FEF-013: moved to @nabd/domain

const src = readFileSync(new URL("../../app/components/consult/DoseCalculator.tsx", import.meta.url), "utf8");
const page = readFileSync(new URL("../../app/consult/page.tsx", import.meta.url), "utf8");

test("unknown or garbage recordedAt is never fresh", () => {
  for (const v of [null, undefined, "", "not-a-date"]) assert.equal(weightAge(v).state, "unknown");
});

test("client 3-month fallback boundary: exactly 3 months fresh, one minute later stale", () => {
  const rec = "2026-01-15T10:00:00Z";
  assert.equal(weightAge(rec, new Date("2026-04-15T10:00:00Z")).state, "fresh");
  assert.equal(weightAge(rec, new Date("2026-04-15T10:01:00Z")).state, "stale");
});

test("rate and weight validation", () => {
  assert.ok(rateError("")); assert.ok(rateError("0")); assert.ok(rateError("-1")); assert.ok(rateError("abc"));
  assert.equal(rateError("12.5"), null);
  assert.ok(weightError("0.19")); assert.ok(weightError("400.1")); assert.equal(weightError("0.2"), null); assert.equal(weightError("400"), null);
});

test("component: 422 adult-patient hides the whole helper", () => {
  assert.match(src, /res\.status === 422 && pr\.type\?\.endsWith\("adult-patient"\)/);
  assert.match(src, /if \(adult\) return null;/);
});

test("component: server weightStale wins over the client month guess, amber warn notice with 're-weigh' wording and the recorded date", () => {
  assert.match(src, /result\.weightStale \?\? age\?\.state === "stale"/);
  assert.match(src, /noticeWarn[^]*Re-weigh/);
  assert.match(src, /fmtDate\(age\.date\)/);
});

test("component: Record weight action is absent (not disabled) without clinical:edit", () => {
  assert.match(src, /const recordAction = canRecordWeight && !recording && \(/);
  assert.doesNotMatch(src, /disabled=\{!canRecordWeight\}/);
  assert.match(page, /canRecordWeight=\{can\("clinical:edit"\)\}/);
});

test("consult page also hides the helper for a loaded adult patient (age >= 18)", () => {
  assert.match(page, /!\(pat && age\(pat\.dob\) >= 18\)/);
});

test("amber token is a design token, not a hard-coded colour", () => {
  const css = readFileSync(new URL("../../app/components/consult/consultParts.module.css", import.meta.url), "utf8");
  assert.match(css, /\.noticeWarn \{[^}]*var\(--nb-warn-/);
});
