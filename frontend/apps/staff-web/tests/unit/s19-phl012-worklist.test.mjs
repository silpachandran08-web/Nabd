// PHL-012: worklist board rules, the SSE mapping and the polling fallback. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { cardTitle, claimRefusal, columnEntries, COLUMNS, lineText, moveFocus, WORKLIST_RESOURCE } from "../../app/components/setup/worklistLogic.ts";
import { LIVE_RESOURCES, resourcesFor } from "../../../../packages/api-client/sse/patch.ts";

const e = (id, status, signedAt) => ({ id, status, signedAt, lines: [] });

test("columns: New, In progress (incl. partly given), Ready; oldest first inside a column", () => {
  const all = [e("c", "new", "2026-11-03T10:02:00Z"), e("a", "new", "2026-11-03T10:00:00Z"), e("p", "partially_dispensed", "2026-11-03T09:00:00Z"),
    e("i", "in_progress", "2026-11-03T09:30:00Z"), e("d", "dispensed", "2026-11-03T08:00:00Z"), e("x", "expired", "2026-11-03T07:00:00Z")];
  assert.deepEqual(COLUMNS.map((c) => c.title), ["New", "In progress", "Ready"]);
  assert.deepEqual(columnEntries(all, COLUMNS[0].statuses).map((x) => x.id), ["a", "c"]);
  assert.deepEqual(columnEntries(all, COLUMNS[1].statuses).map((x) => x.id), ["p", "i"]);
  assert.deepEqual(columnEntries(all, COLUMNS[2].statuses).map((x) => x.id), ["d"]);
  assert.equal(COLUMNS.some((c) => c.statuses.includes("expired")), false, "an expired entry is on no column");
});

test("a card shows the token and the first name only", () => {
  assert.equal(cardTitle({ token: 12, patientName: "Meera  Nair" }), "T-12 · Meera");
  assert.equal(cardTitle({ token: null, patientName: null }), "Patient");
  assert.equal(lineText({ drug: "Amoxicillin 500", qtyPrescribed: 10, qtyDispensed: 0 }), "Amoxicillin 500 × 10");
  assert.equal(lineText({ drug: null, qtyPrescribed: 10, qtyDispensed: 4 }), "Medicine × 10 (4 of 10 given)");
});

test("a lost Start says who has it; an expired Rx says so", () => {
  assert.equal(claimRefusal({ status: 409, problem: { type: "https://nabd/problems/already-claimed", claimedByName: "Asha" } }), "Taken by Asha.");
  assert.equal(claimRefusal({ status: 409, problem: { type: "x/already-claimed" } }), "Taken by another pharmacist.");
  assert.match(claimRefusal({ status: 409, problem: { type: "x/rx-expired" } }), /expired/);
  assert.equal(claimRefusal({ status: 409, detail: "This prescription is dispensed.", problem: { type: "x/not-claimable" } }), "This prescription is dispensed.");
});

test("J / K move through the cards and wrap", () => {
  assert.equal(moveFocus(-1, 3, "j"), 0);
  assert.equal(moveFocus(2, 3, "j"), 0);
  assert.equal(moveFocus(0, 3, "k"), 2);
  assert.equal(moveFocus(-1, 3, "K"), 2);
  assert.equal(moveFocus(1, 3, "x"), 1);
  assert.equal(moveFocus(-1, 0, "j"), -1);
});

test("worklist events refresh the board; pharmacy:dispense holders subscribe to pharmacy.worklist; a snapshot refreshes it too", () => {
  assert.deepEqual(resourcesFor("pharmacy.worklist.entry_created.v1"), [WORKLIST_RESOURCE]);
  assert.deepEqual(resourcesFor("pharmacy.worklist.status_changed.v1"), [WORKLIST_RESOURCE]);
  assert.ok(LIVE_RESOURCES.includes(WORKLIST_RESOURCE));
  const live = readFileSync(new URL("../../app/lib/live.tsx", import.meta.url), "utf8");
  assert.match(live, /pharmacy:dispense"\)\) t\.push\("pharmacy\.worklist"\)/);
});

test("the board polls while there is no stream and keeps the list on refetch; it is absent without the grant or outside In-house", () => {
  const hook = readFileSync(new URL("../../app/components/setup/usePharmacyWorklist.ts", import.meta.url), "utf8");
  assert.match(hook, /useLiveRefresh\(\[WORKLIST_RESOURCE\], load\)/);
  assert.match(hook, /usePolling\(load, 10_000\)/);
  assert.doesNotMatch(hook, /setEntries\(null\)/, "a refetch never swaps the board for a skeleton");
  const panel = readFileSync(new URL("../../app/components/setup/PharmacyPanel.tsx", import.meta.url), "utf8");
  assert.match(panel, /const boardMode = \(mode: PharmacyMode\) => mode === "in_house" && can\("pharmacy:dispense"\)/);
  assert.match(panel, /\{boardMode\(mode\) \? <WorklistBoard \/> :/);
  const board = readFileSync(new URL("../../app/components/setup/WorklistBoard.tsx", import.meta.url), "utf8");
  assert.match(board, /\{e\.status === "new" && \(/, "Start only on a new entry");
  assert.match(board, /const openable = \(e: WorklistEntry\) => canOpen && !!e\.queueEntryId/, "the open link is absent without billing:view");
  assert.match(board, /label="Out of stock"/);
});
