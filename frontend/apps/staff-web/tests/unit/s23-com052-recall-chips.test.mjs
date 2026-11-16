// COM-052: 'Review in 2 weeks' recall chips at consult close. Pure rules of recall.ts plus wiring checks on the consult source.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { MAX_RECALL_DAYS, RECALL_CHIPS, recallLabel, validRecallDays } from "../../app/components/consult/recall.ts";

const APP = new URL("../../app/", import.meta.url);
const src = (p) => readFileSync(new URL(p, APP), "utf8");
const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/consult.json", import.meta.url), "utf8"));

test("the chips are 1, 2, 4 weeks and 3, 6 months in days; the range matches the server (1..730 whole days)", () => {
  assert.deepEqual(RECALL_CHIPS.map((c) => [c.id, c.days]), [["1w", 7], ["2w", 14], ["4w", 28], ["3m", 90], ["6m", 180]]);
  assert.equal(MAX_RECALL_DAYS, 730);
  for (const ok of [1, 14, 730]) assert.equal(validRecallDays(ok), true, String(ok));
  for (const bad of [0, -1, 731, 1.5, Number.NaN]) assert.equal(validRecallDays(bad), false, String(bad));
  for (const c of RECALL_CHIPS) assert.ok(en.recallChips.chip[c.id], c.id);
});

test("the summary label is the clinic calendar date; null = not set, undefined = not known", () => {
  assert.equal(recallLabel({ recallId: "r", dueDate: "2026-11-30", status: "pending", intervalDays: 14, bookedAppointmentId: null }), "30 Nov 2026");
  assert.equal(recallLabel(null), null);
  assert.equal(recallLabel(undefined), undefined);
});

test("one press on a chip creates the recall: a native button posting at once, no confirm step; absent unless canSet", () => {
  const s = src("components/consult/RecallChips.tsx");
  assert.match(s, /<button key=\{c\.id\} type="button"[^>]*onClick=\{\(\) => void set\(c\.id, c\.days\)\}/);
  assert.match(s, /api\.post<Recall>\(`\/encounters\/\$\{entryId\}\/recall-orders`/);
  assert.doesNotMatch(s, /confirm\(|Dialog/);
  assert.match(s, /\{canSet && recall === null && \(/); // absent, not disabled, for anyone but the visit doctor
  assert.match(src("components/consult/consultParts.module.css"), /@media \(pointer: coarse\) \{ \.recallChip \{ min-height: 48px/);
});

test("the chips live in the consult follow-up panel (not a shell) and the close summary shows the recall", () => {
  const fu = src("components/consult/FollowUpPanel.tsx");
  assert.match(fu, /<RecallChips entryId=\{entryId\}/);
  assert.doesNotMatch(fu, /reminderNotAvailableYetNo/);
  assert.equal(en.followUpPanel.reminderNotAvailableYetNo, undefined);
  assert.match(src("components/consult/visit/VisitWorkspace.tsx"), /canSetRecall=\{entry\.doctorId === selfId && can\("clinical:edit"\)\}/);
  assert.match(src("components/consult/closeout/CloseOutPanel.tsx"), /<strong>\{tx\("recall"\)\}<\/strong>/);
  assert.ok(en.closeOutPanel.recallSet && en.closeOutPanel.recallBooked);
});
