// ADM-042: the owner's live operations board: alert actions by grant, the broadcast-delay call, the requests link and its filter,
// threshold form rules, row text, and the SSE wiring (topic `ops`, resource live-ops).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const m = await import("../../app/(clinic)/overview/liveOpsModel.ts");
const { requestFilter } = await import("../../app/components/requests/requestsLogic.ts");
const { resourcesFor, LIVE_RESOURCES, createApplier } = await import("../../../../packages/api-client/sse/patch.ts");

const DOC = "0192f0a0-0000-7000-8000-000000000001", DELAY = "0192f0a0-0000-7000-8000-000000000002";
const R1 = "0192f0a0-0000-7000-8000-00000000000a", R2 = "0192f0a0-0000-7000-8000-00000000000b";
const late = (o) => ({ code: "doctor_late", severity: "warning", summary: "Dr Rao is running 25 min late: 6 waiting, 3 still to arrive, 4 already told.",
  actions: ["broadcast_delay"], minutes: 25, doctorId: DOC, delayId: null, waiting: 6, told: 4, requestIds: null, ...o });
const requests = (o) => ({ code: "requests_waiting", severity: "warning", summary: "2 booking requests have waited over 10 min.", actions: ["open_requests"],
  minutes: 14, doctorId: null, delayId: null, waiting: 2, told: null, requestIds: [R1, R2], ...o });
const pharmacy = { code: "pharmacy_wait", severity: "critical", summary: "Pharmacy wait is 17 min, over the 8 min target (3 open).", actions: ["open_pharmacy"],
  minutes: 17, doctorId: null, delayId: null, waiting: 3, told: null, requestIds: null };
const all = () => true, none = () => false;
const only = (...grants) => (p) => grants.includes(p);

test("AC1: 'broadcast delay' announces through the delay endpoint with the broadcast when nothing is announced yet", () => {
  assert.deepEqual(m.broadcastCall(late()), { method: "post", path: `/doctors/${DOC}/delay`, body: { minutes: 25, broadcast: true } });
});
test("AC1: on an announced delay it changes that delay (PATCH broadcasts again), never a second announcement", () => {
  assert.deepEqual(m.broadcastCall(late({ delayId: DELAY })), { method: "patch", path: `/doctors/${DOC}/delay/${DELAY}`, body: { minutes: 25 } });
});
test("the delay endpoint takes 1..240 minutes: a longer lateness is capped, an alert without doctor or minutes has no call", () => {
  assert.equal(m.broadcastCall(late({ minutes: 400 })).body.minutes, 240);
  assert.equal(m.broadcastCall(late({ doctorId: null })), null);
  assert.equal(m.broadcastCall(late({ minutes: 0 })), null);
  assert.equal(m.broadcastCall(requests()), null);
});
test("AC1: the doctor-late alert offers 'Broadcast delay' to a queue:edit holder; 'rebalance' is never offered", () => {
  const acts = m.alertActions(late({ actions: ["broadcast_delay", "rebalance"] }), all);
  assert.deepEqual(acts.map((a) => [a.kind, a.label]), [["broadcast", "Broadcast delay"]]);
  assert.equal(acts[0].call.path, `/doctors/${DOC}/delay`);
});
test("restricted actions are absent: no queue:edit, no broadcast; no queue:create, no requests link; no pharmacy:view, no pharmacy link", () => {
  assert.deepEqual(m.alertActions(late(), only("reports:view", "queue:view")), []);
  assert.deepEqual(m.alertActions(requests(), only("reports:view", "queue:edit")), []);
  assert.deepEqual(m.alertActions(pharmacy, only("reports:view")), []);
  assert.deepEqual(m.alertActions(late(), none), []);
  assert.deepEqual(m.alertActions(pharmacy, only("pharmacy:view")), [{ kind: "link", label: "Open pharmacy", href: "/pharmacy" }]);
});
test("AC3: tapping the requests alert opens the inbox filtered to exactly those requests", () => {
  const [act] = m.alertActions(requests(), only("queue:create"));
  assert.equal(act.kind, "link");
  assert.equal(act.href, `/requests?ids=${R1},${R2}`);
  assert.deepEqual(requestFilter(new URL("https://x" + act.href).searchParams.get("ids")), [R1, R2]);
});
test("AC3: the inbox is unfiltered without ids, and anything that is not an id is dropped from the link and the filter", () => {
  assert.equal(requestFilter(null), null);
  assert.equal(requestFilter(""), null);
  assert.equal(requestFilter("abc,<script>"), null);
  assert.deepEqual(requestFilter(` ${R1} ,junk`), [R1]);
  assert.equal(m.requestsHref([]), "/requests");
  assert.equal(m.requestsHref(null), "/requests");
  assert.equal(m.requestsHref(["Asha Rao", R2]), `/requests?ids=${R2}`);
});
test("AC3: the inbox component filters its list by the ids and offers the way back to all requests", () => {
  const inbox = readFileSync(new URL("../../app/components/requests/RequestsInbox.tsx", import.meta.url), "utf8");
  assert.match(inbox, /filter\(\(it\) => !only \|\| only\.includes\(it\.appointmentId\)\)/);
  assert.match(inbox, /href="\/requests"/);
  const page = readFileSync(new URL("../../app/(clinic)/requests/page.tsx", import.meta.url), "utf8");
  assert.match(page, /<RequestsInbox only=\{requestFilter\(useSearchParams\(\)\.get\("ids"\)\)\} \/>/);
  assert.match(page, /<Suspense/); // useSearchParams needs it
});
test("AC4: threshold form: whole minutes 1..240, one sentence per bad field, lab flag kept as stored", () => {
  const cur = { pharmacyWaitMin: 8, labTatBreach: true, requestWaitMin: 10, doctorLateMin: 15 };
  const form = m.toForm(cur);
  assert.deepEqual(form, { pharmacyWaitMin: "8", requestWaitMin: "10", doctorLateMin: "15" });
  assert.deepEqual(m.thresholdErrors(form), {});
  assert.deepEqual(m.toThresholds({ ...form, pharmacyWaitMin: "10" }, cur), { pharmacyWaitMin: 10, labTatBreach: true, requestWaitMin: 10, doctorLateMin: 15 });
  assert.deepEqual(m.toThresholds(form, { ...cur, labTatBreach: false }).labTatBreach, false);
  for (const bad of ["", " ", "0", "241", "7.5", "abc", "-3"]) {
    const e = m.thresholdErrors({ ...form, pharmacyWaitMin: bad });
    assert.deepEqual(Object.keys(e), ["pharmacyWaitMin"], `"${bad}"`);
    assert.equal(e.pharmacyWaitMin, "Enter whole minutes from 1 to 240.");
  }
  assert.deepEqual(m.THRESHOLD_FIELDS.map((f) => f.key).sort(), ["doctorLateMin", "pharmacyWaitMin", "requestWaitMin"]);
});
test("doctor rows: status words, tone and the counts that matter", () => {
  const d = { id: DOC, name: "Dr Rao", status: "not_started", waiting: 6, booked: 3, delayMin: 25, longestWaitMin: 14, told: 4 };
  assert.equal(m.doctorLine(d), "6 waiting · longest 14 min · 3 still to arrive · 25 min late · 4 told");
  assert.equal(m.doctorLine({ ...d, waiting: 0, booked: 0, delayMin: 0, told: 0, longestWaitMin: null }), "0 waiting");
  assert.deepEqual(["in", "paused", "out", "not_started", "??"].map(m.statusLabel), ["In session", "Paused", "Session ended", "Not started", "Not started"]);
  assert.equal(m.statusTone(d), "warn");
  assert.equal(m.statusTone({ status: "in", delayMin: 0 }), "ok");
  assert.equal(m.statusTone({ status: "out", delayMin: 0 }), "neutral");
});
test("stations, severity and the empty board", () => {
  assert.equal(m.stationLine({ open: 0, longestWaitMin: null }, "prescriptions"), "No prescriptions waiting");
  assert.equal(m.stationLine({ open: 3, longestWaitMin: 9 }, "prescriptions"), "3 waiting · longest 9 min");
  assert.deepEqual([m.alertTone(pharmacy), m.alertLabel(pharmacy)], ["urgent", "Urgent"]);
  assert.deepEqual([m.alertTone(late()), m.alertLabel(late())], ["warn", "Needs attention"]); // a word, not colour alone
  const empty = { asOf: "", thresholds: {}, doctors: [], pharmacy: { open: 0, longestWaitMin: null }, requests: { open: 0, longestWaitMin: null }, lab: null, alerts: [] };
  assert.equal(m.boardEmpty(empty), true);
  assert.equal(m.boardEmpty({ ...empty, requests: { open: 1, longestWaitMin: 2 } }), false);
  assert.equal(m.boardEmpty({ ...empty, alerts: [pharmacy] }), false);
});
test("the confirm step says who is told and that nobody is messaged twice within 20 minutes; the result names the count", () => {
  assert.equal(m.broadcastConfirmText(late(), "Dr Rao"), "Dr Rao is running 25 min late. Everyone still to be seen is told on WhatsApp; the 4 already told are not messaged again within 20 minutes.");
  assert.equal(m.broadcastConfirmText(late({ told: 0 }), null), "The doctor is running 25 min late. Everyone still to be seen is told on WhatsApp.");
  assert.equal(m.broadcastDoneText(1), "Delay notice sent to 1 patient.");
  assert.equal(m.broadcastDoneText(6), "Delay notice sent to 6 patients.");
  assert.equal(m.broadcastDoneText(0), "Delay saved. Nobody new needed a message.");
  assert.equal(m.broadcastDoneText(undefined), "Delay saved. Nobody new needed a message.");
});
test("AC2: an ops alert on the stream re-reads the board; reports:view holders subscribe to `ops`; a snapshot re-reads it too", () => {
  assert.deepEqual(resourcesFor("reporting.ops_alert.raised.v1"), [m.LIVE_OPS_RESOURCE]);
  assert.ok(LIVE_RESOURCES.includes(m.LIVE_OPS_RESOURCE));
  const inv = [];
  const apply = createApplier({ getQueriesData: () => [], setQueryData: () => {}, invalidateQueries: (f) => inv.push(f.queryKey.at(-1)) }, (r) => ["t", "x", r]);
  const delta = (v) => ({ event: "delta", data: { type: "reporting.ops_alert.raised.v1", id: "pharmacy_wait", v, topic: "ops", fields: { code: "pharmacy_wait", severity: "warning" } } });
  assert.deepEqual(apply(delta(5)), [m.LIVE_OPS_RESOURCE]);
  assert.deepEqual(apply(delta(5)), [], "a redelivered alert is ignored");
  assert.deepEqual(apply(delta(9)), [m.LIVE_OPS_RESOURCE], "the same alert raised again later re-reads the board");
  const live = readFileSync(new URL("../../app/lib/live.tsx", import.meta.url), "utf8");
  assert.match(live, /perms\.includes\("reports:view"\)\) t\.push\("ops"\)/);
  const board = readFileSync(new URL("../../app/(clinic)/overview/LiveOps.tsx", import.meta.url), "utf8");
  assert.match(board, /useLiveRefresh\(\[LIVE_OPS_RESOURCE\], load\)/);
  assert.equal(m.REFRESH_MS, 15_000);
});
test("the board keeps its figures on a failed re-read, is absent without reports:view, and the settings need setup:edit", () => {
  const board = readFileSync(new URL("../../app/(clinic)/overview/LiveOps.tsx", import.meta.url), "utf8");
  assert.match(board, /s\.kind === "ready" \? \{ \.\.\.s, stale: true \}/); // never back to a skeleton on refetch
  assert.match(board, /if \(!can\("reports:view"\) \|\| state\.kind === "forbidden"\) return null;/);
  assert.match(board, /can\("setup:edit"\) && <button/);
  assert.doesNotMatch(board, /toLocale|new Date\(/); // minutes only: no browser-zone time on the board
  const page = readFileSync(new URL("../../app/(clinic)/overview/page.tsx", import.meta.url), "utf8");
  assert.match(page, /<LiveOpsSection \/>/);
});
