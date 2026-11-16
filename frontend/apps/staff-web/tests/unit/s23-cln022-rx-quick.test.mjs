// CLN-022: Rx pad quick actions (repeat last + favourite set chips). Pure logic is tested in @nabd/domain (rxQuick.test.ts);
// these are static checks of the wiring, plus the step counts that stand in for the timed tasks (< 30 s repeat, < 90 s set).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (p) => readFileSync(new URL(p, import.meta.url), "utf8");
const quick = read("../../app/components/consult/rx/RxQuickActions.tsx");
const panel = read("../../app/components/consult/rx/RxPanel.tsx");
const en = JSON.parse(read("../../../../packages/i18n/messages/en/consult.json")).rxQuickActions;

test("repeat copies exactly the previewed prescription (sourcePrescriptionId) through the repeat-last endpoint, via the shared client", () => {
  assert.match(quick, /api\.post<Prescription>\(path, body\)/);
  assert.match(quick, /repeat-last`, \{ sourcePrescriptionId: source\.id \}/);
  assert.match(quick, /repeatSource\(prescriptions\.data \?\? \[\], entryId\)/);
  assert.match(quick, /data-testid="rx-repeat-preview"/);
});

test("timed-task proxy: repeat = chip tap + Repeat tap (then sign); a set on an empty pad = one tap; a confirm only to replace lines or show stale items", () => {
  assert.match(quick, /onClick=\{\(\) => setPreview\(true\)\}/);
  assert.match(quick, /onClick=\{\(\) => void repeat\(\)\} autoFocus/); // Enter confirms from the keyboard too
  assert.match(quick, /filledCount > 0 \|\| set\.staleItemCount > 0 \? setConfirmSet\(set\) : void apply\(set\)/);
});

test("keyboard: R and 1-9 via the domain rule, ignored while typing or inside a dialog", () => {
  assert.match(quick, /rxShortcut\(\{ key: e\.key/);
  assert.match(quick, /closest\("\[aria-modal\]"\)/);
  assert.match(quick, /aria-keyshortcuts="R"/);
  assert.match(quick, /\(sets \?\? \[\]\)\.slice\(0, 9\)/);
});

test("absent, not disabled, without clinical:edit or once signed; one shared sets fetch for chips and list", () => {
  assert.match(panel, /\{!signed && can\("clinical:edit"\) && \(\s*<RxQuickActions/);
  assert.match(panel, /const rxSets = useRxSets\(authedFetch\);/);
  assert.match(panel, /rxSets=\{rxSets\}/);
});

test("stale items show their reason and the suggested replacement before applying; strings are in the catalogue", () => {
  assert.match(quick, /tx\(`stale\.\$\{i\.staleReason \?\? "not_in_master"\}`\)/);
  assert.match(quick, /tx\("replacement", \{ display: i\.replacement\.display \}\)/);
  for (const k of ["repeatLast", "repeatLastBy", "previewTitle", "checksRunAgain", "replacesPad", "replacement", "couldntRepeat"]) assert.ok(en[k], k);
  for (const k of ["retired", "out_of_stock", "not_in_master"]) assert.ok(en.stale[k], k);
  assert.match(en.checksRunAgain, /not carried over/);
});
