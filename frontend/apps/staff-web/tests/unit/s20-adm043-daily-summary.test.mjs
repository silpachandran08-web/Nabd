// ADM-043: the owner's daily WhatsApp summary dialog — settings form, preview, owner-only button.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { toRequest, SLOT_LABELS } = await import("../../app/(clinic)/overview/dailySummaryModel.ts");

const src = (f) => readFileSync(new URL(`../../app/(clinic)/overview/${f}`, import.meta.url), "utf8");
const dialog = src("DailySummaryDialog.tsx");
const page = src("page.tsx");

test("the PUT body matches the API shape: recipients as {staffId}, sendAt 'close' or HH:mm", () => {
  assert.deepEqual(toRequest({ enabled: true, combined: false, recipients: ["a", "b"], sendAt: "close" }),
    { enabled: true, combined: false, recipients: [{ staffId: "a" }, { staffId: "b" }], sendAt: "close" });
  assert.equal(toRequest({ enabled: true, combined: true, recipients: [], sendAt: "21:00" }).sendAt, "21:00");
  assert.equal(toRequest({ enabled: true, combined: true, recipients: [], sendAt: "9pm" }).sendAt, "close");
  assert.equal(SLOT_LABELS.length, 4);
});

test("the dialog reads settings and preview, has loading and error states, and saves through PUT", () => {
  assert.match(dialog, /api\.get<Settings>\("\/reports\/daily-summary\/settings"\)/);
  assert.match(dialog, /api\.get<Preview>\("\/reports\/daily-summary\/preview"\)/);
  assert.match(dialog, /api\.put<Settings>\("\/reports\/daily-summary\/settings"/);
  assert.match(dialog, /<Skeleton rows=\{4\}/);
  assert.match(dialog, /<ErrorState message=\{loadError\} onRetry=\{load\}/);
  assert.match(dialog, /aria-label="Preview of today's message"/);
  assert.match(dialog, /settings\.branches > 1 && \(/); // the combined option only for a chain
});

test("the button is absent unless the caller is the owner with reports:view (the endpoints' gate)", () => {
  assert.match(page, /setCanDailySummary\(isOwner\(\) && can\("reports:view"\)\)/);
  assert.match(page, /\{canDailySummary && \(\s*<button type="button" className=\{styles\.dsOpen\}/);
});
