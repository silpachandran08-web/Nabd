// S15 ACC-018 (display mode helpers), ACC-022 (board keyboard/drag logic), ACC-023 (lobby SSE parsing, liveness, bilingual words).
import { test } from "node:test";
import assert from "node:assert/strict";
const eta = await import("../../app/lib/etaDisplay.ts");
const board = await import("../../app/components/arrivals/doctors/doctorBoardLogic.ts");
const disp = await import("../../app/display/displayLogic.ts");

test("ACC-018: exact mode below 85% measured accuracy warns the owner; range or 85% does not", () => {
  assert.equal(eta.accuracyWarning("exact", 0.78), "Exact times will often be wrong; range is safer");
  assert.equal(eta.accuracyWarning("exact", 0.85), null);
  assert.equal(eta.accuracyWarning("exact", null), null); // no measured visits: no warning
  assert.equal(eta.accuracyWarning("range", 0.5), null);
});
test("ACC-018: doctor accuracy is the visit-weighted within-10-min share of that doctor only", () => {
  const rows = [{ doctorId: "a", n: 30, within10Pct: 1 }, { doctorId: "a", n: 10, within10Pct: 0.6 }, { doctorId: "b", n: 99, within10Pct: 0 }, { doctorId: "a", n: 5 }];
  assert.equal(eta.doctorAccuracy(rows, "a"), (30 + 6) / 40);
  assert.equal(eta.doctorAccuracy(rows, "zzz"), null);
});
test("ACC-018: five modes, range is the labelled default", () => {
  assert.deepEqual(eta.ETA_MODES.map((m) => m.value), ["exact", "range", "position", "delay_only", "hidden"]);
  assert.match(eta.modeLabel(null), /default/);
});

test("ACC-022: M starts a move, digits pick the column, Escape cancels, P is priority", () => {
  assert.deepEqual(board.keyAction("m", false), { type: "start" });
  assert.deepEqual(board.keyAction("M", false), { type: "start" });
  assert.deepEqual(board.keyAction("2", true), { type: "target", index: 1 });
  assert.equal(board.keyAction("2", false), null); // a digit alone does nothing
  assert.deepEqual(board.keyAction("Escape", true), { type: "cancel" });
  assert.deepEqual(board.keyAction("p", false), { type: "priority" });
  assert.equal(board.keyAction("p", true), null);
  assert.equal(board.keyAction("0", true), null);
});
test("ACC-022: a target column must exist and not be the patient's own doctor", () => {
  const docs = [{ doctorId: "a", entries: [] }, { doctorId: "b", entries: [] }];
  assert.equal(board.targetDoctor(docs, "a", 1).doctorId, "b");
  assert.equal(board.targetDoctor(docs, "a", 0), null);
  assert.equal(board.targetDoctor(docs, "a", 5), null);
});
test("ACC-022: only patients who have not started the consult can move; the token shows the old one", () => {
  assert.equal(board.isMovable({ stage: "waiting" }), true);
  assert.equal(board.isMovable({ stage: "in_consult" }), false);
  assert.equal(board.isMovable({ stage: "checkout_pending" }), false);
  assert.equal(board.tokenLabel({ token: 14, previousToken: 9 }), "T-14, was T-9");
  assert.equal(board.tokenLabel({ token: 14 }), "T-14");
  const docs = [{ doctorId: "a", entries: [{ id: "e1" }] }];
  assert.equal(board.ownerOf(docs, "e1").doctor.doctorId, "a");
  assert.equal(board.ownerOf(docs, "nope"), null);
});

test("ACC-023: liveness is stale at 60 s and lost at 180 s", () => {
  assert.equal(disp.liveness(59_999), "live");
  assert.equal(disp.liveness(60_000), "stale");
  assert.equal(disp.liveness(179_999), "stale");
  assert.equal(disp.liveness(180_000), "lost");
});
test("ACC-023: SSE frames split on blank lines, a partial frame stays in the buffer", () => {
  const a = disp.parseSse('event: projection\ndata: {"v":1}\n\nevent: revoked\ndata: {}\n\nevent: projec');
  assert.deepEqual(a.frames, [{ event: "projection", data: '{"v":1}' }, { event: "revoked", data: "{}" }]);
  assert.equal(a.rest, "event: projec");
  assert.deepEqual(disp.parseSse("data: x\r\n\r\n").frames, [{ event: "message", data: "x" }]);
});
test("ACC-023: English plus Hindi or Arabic; Arabic is right-to-left; unknown language falls back to English", () => {
  assert.equal(disp.isRtl("ar"), true);
  assert.equal(disp.isRtl("hi"), false);
  assert.equal(disp.wordsFor("hi").now, "अभी देखे जा रहे हैं");
  assert.equal(disp.wordsFor("xx").now, "Now serving");
  assert.match(disp.wordsFor("ar").late(15), /15/);
  assert.equal(disp.tokenText(12), "T-12");
});
