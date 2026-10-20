// LIVE-03: patient list and drawer share one last-visit formatter. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { formatLastVisit } from "../../app/lib/lastVisit.ts";

test("null/undefined/empty means no visits", () => {
  for (const v of [null, undefined, ""]) assert.equal(formatLastVisit(v), "No visits yet");
});

test("registry date and drawer instant for the same day render identically", () => {
  assert.equal(formatLastVisit("2026-10-03"), "03 Oct 2026");
  // Noon UTC is the same calendar day in every zone from UTC-11 to UTC+11.
  assert.equal(formatLastVisit("2026-10-03T12:00:00Z"), formatLastVisit("2026-10-03"));
});

test("a date-only value never shifts a day whatever the local zone", () => {
  assert.equal(formatLastVisit("2026-01-01"), "01 Jan 2026");
});

test("garbage renders a dash, not 'Invalid Date'", () => {
  assert.equal(formatLastVisit("not-a-date"), "—");
});
