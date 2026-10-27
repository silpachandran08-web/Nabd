// ACC-005 / ACC-006: the "(+1)" marker on overnight hours, and the affected-bookings offer path has no text input.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { hoursLabel } = await import("../../app/components/myschedule/hoursLabel.ts");

test("an hours block that ends the next day shows (+1); a same-day block does not", () => {
  assert.equal(hoursLabel({ startTime: "20:00:00", endTime: "01:00:00", endsNextDay: true }), "20:00–01:00 (+1)");
  assert.equal(hoursLabel({ startTime: "09:00:00", endTime: "13:00:00", endsNextDay: false }), "09:00–13:00");
  assert.equal(hoursLabel({ startTime: "09:00", endTime: "13:00" }), "09:00–13:00");
});

test("the affected-bookings panel is buttons and links only: no input, textarea or select", () => {
  const src = readFileSync(new URL("../../app/components/myschedule/ScheduleDialogs.tsx", import.meta.url), "utf8");
  const panel = src.slice(src.indexOf("function AffectedPanel"), src.indexOf("export function LeaveDialog"));
  assert.ok(panel.length > 200, "panel found");
  assert.doesNotMatch(panel, /<input|<textarea|<select|contentEditable/i);
  assert.match(panel, /Offer alternatives to all/);
  assert.match(panel, /Call patient/);
});

test("the leave dialog sends a window only for part-day leave", () => {
  const src = readFileSync(new URL("../../app/components/myschedule/ScheduleDialogs.tsx", import.meta.url), "utf8");
  assert.match(src, /\.\.\.\(part \? \{ startTime: winStart, endTime: winEnd \} : \{\}\)/);
});
