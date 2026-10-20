// FEF-013: shared display helpers and status vocabularies (one copy each, used by every screen).
import { expect, test } from "vitest";
import { age, DONE_STATUSES, initials, minutesSince, QUEUE_STATUSES, QUEUE_STATUS_LABEL, VISIT_LABEL, VISIT_SOURCES, WAITING_STATUSES } from "../src/index.ts";

test("age counts a birthday not yet reached this year as one less", () => {
  const now = new Date(2026, 9, 20);
  expect(age("2000-10-20", now)).toBe(26);
  expect(age("2000-10-21", now)).toBe(25);
  expect(age("2000-11-01", now)).toBe(25);
  expect(age("2000-01-01", now)).toBe(26);
});

test("initials: first letters of the first two words, upper case", () => {
  expect(initials("asha rao")).toBe("AR");
  expect(initials("  Asha   Devi Rao ")).toBe("AD");
  expect(initials("Mohammed")).toBe("M");
  expect(initials("")).toBe("");
});

test("minutesSince: whole minutes, never negative, null for absent or bad input", () => {
  const now = Date.parse("2026-10-20T10:00:00Z");
  expect(minutesSince("2026-10-20T09:30:20Z", now)).toBe(30);
  expect(minutesSince("2026-10-20T10:05:00Z", now)).toBe(0);
  expect(minutesSince(null, now)).toBeNull();
  expect(minutesSince(undefined, now)).toBeNull();
  expect(minutesSince("nope", now)).toBeNull();
});

test("every queue status has a label and every visit source has a label", () => {
  for (const s of QUEUE_STATUSES) expect(QUEUE_STATUS_LABEL[s]).toBeTruthy();
  for (const s of VISIT_SOURCES) expect(VISIT_LABEL[s]).toBeTruthy();
  expect(VISIT_LABEL.walk_in).toBe("Walk-in");
  for (const s of [...WAITING_STATUSES, ...DONE_STATUSES]) expect(QUEUE_STATUSES).toContain(s);
});
