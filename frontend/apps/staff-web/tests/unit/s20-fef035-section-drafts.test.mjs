// FEF-035: every consult section autosaves safely on shared machines. Pure checks of the section-keyed encrypted draft
// store (key survives a reload of the same tab, dies on sign-out), the Rx change/conflict fingerprint, the one combined
// save indicator and the dirty registry, plus wiring checks on the consult source. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const mem = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), clear: () => m.clear() }; };
globalThis.sessionStorage = mem();
const APP = new URL("../../app/", import.meta.url);
const src = (p) => readFileSync(new URL(p, APP), "utf8");
const draftStore = (tag) => import(`../../app/components/consult/draftStore.ts?${tag}`); // a fresh module instance = a reloaded tab

test("drafts are keyed per visit and section; the note keeps its DOC-014 key", async () => {
  const { draftKey } = await draftStore("k");
  assert.equal(draftKey("e1"), "e1");
  assert.equal(draftKey("e1", "note"), "e1");
  assert.equal(draftKey("e1", "rx"), "e1#rx");
  assert.equal(draftKey("e1", "orders.admin"), "e1#orders.admin");
  // deleteVisitDrafts covers the note key and every entryId#section key, and nothing of another visit
  for (const k of ["e1", "e1#rx", "e1#followUp"]) assert.ok(k >= "e1" && k <= "e1#￿", k);
  for (const k of ["e10", "e2", "e1a"]) assert.ok(!(k >= "e1" && k <= "e1#￿"), k);
});

test("crash/reload of the same tab: a fresh module instance (same sessionStorage) reads the draft; sign-out makes it unreadable", async () => {
  const a = await draftStore("a");
  const d = { entryId: "e7", section: "rx", data: { items: [{ drugName: "SECRET-DRUG", dosage: "1", frequency: "", duration: "", instructions: "" }], base: "[]" }, baseUpdatedAt: null, savedAt: "2026-01-01T00:00:00Z" };
  const rec = await a.seal(d);
  assert.equal(rec.entryId, "e7#rx");
  assert.ok(!Buffer.from(rec.ct).toString("latin1").includes("SECRET-DRUG"));
  assert.match(sessionStorage.getItem("nabd_draft_key"), /^[0-9a-f]{64}$/);
  const b = await draftStore("b"); // "reload": new in-memory state, same tab session
  assert.deepEqual(await b.unseal(rec), d);
  await b.clearDrafts(); // sign-out
  assert.equal(sessionStorage.getItem("nabd_draft_key"), null);
  const c = await draftStore("c"); // next user on the machine
  assert.equal(await c.unseal(rec), null);
});

test("new tab without the key: the draft is dropped and counted as lost, never shown as plain text", async () => {
  const a = await draftStore("lost1");
  const rec = await a.seal({ entryId: "e8", data: { subjective: "x", objective: "", assessment: "", plan: "", diagnosis: "" }, baseUpdatedAt: null, savedAt: "t" });
  sessionStorage.removeItem("nabd_draft_key");
  const b = await draftStore("lost2");
  assert.equal(await b.unseal(rec), null);
});

test("rx fingerprint: blank lines and warnings are ignored, any prescribed field change is a change", async () => {
  const { rxFingerprint } = await import("../../app/components/consult/rx/rxLogic.ts");
  const line = { drugName: "Drug A", dosage: "1", frequency: "bd", duration: "5 d", instructions: "", warnings: [{ kind: "allergy" }] };
  const base = rxFingerprint([line, { drugName: "", dosage: "", frequency: "", duration: "", instructions: "" }]);
  assert.equal(base, rxFingerprint([{ ...line, warnings: undefined, coded: true }]));
  assert.equal(rxFingerprint([]), "[]");
  assert.notEqual(base, rxFingerprint([{ ...line, duration: "7 d" }]));
  assert.notEqual(base, rxFingerprint([line, { ...line, drugName: "Drug B" }]));
  assert.notEqual(base, rxFingerprint([{ ...line, quantity: 10 }]));
});

test("one indicator: the most urgent state wins, any dirty section is dirty, the newest save shows", async () => {
  const { combineSave, setSectionDirty, anySectionDirty } = await import("../../app/components/consult/draftStatus.ts");
  const t1 = new Date(1), t2 = new Date(2);
  const note = { saveState: "saved", dirty: false, lastSavedAt: t2, conflict: false };
  assert.deepEqual(combineSave([note, { saveState: "error", dirty: true, lastSavedAt: t1, conflict: false }]), { saveState: "error", dirty: true, conflict: false, lastSavedAt: t2 });
  assert.deepEqual(combineSave([note, { saveState: "saving", dirty: true, lastSavedAt: null, conflict: false }]).saveState, "saving");
  assert.deepEqual(combineSave([note, { saveState: "offline", dirty: false, lastSavedAt: null, conflict: true }]), { saveState: "offline", dirty: false, conflict: true, lastSavedAt: t2 });
  assert.equal(combineSave([note, { saveState: "idle", dirty: false, lastSavedAt: null, conflict: false }]).saveState, "saved");
  assert.equal(anySectionDirty(), false);
  setSectionDirty("e1#followUp", true);
  setSectionDirty("e1#orders.admin", true);
  assert.equal(anySectionDirty(), true);
  setSectionDirty("e1#followUp", false);
  assert.equal(anySectionDirty(), true);
  setSectionDirty("e1#orders.admin", false);
  assert.equal(anySectionDirty(), false);
});

test("wiring: every section drafts, the page guards beforeunload on the combined dirty flag, signing clears the visit's drafts, sign-out clears the key", () => {
  const ws = src("components/consult/visit/VisitWorkspace.tsx");
  assert.match(ws, /combineSave\(\[/);
  assert.match(ws, /dirty: pad\.rxDirty \|\| localDirty/);
  assert.match(ws, /reportDirty\.current\(save\.dirty\)/);
  assert.match(ws, /<RxConflictDialog/);
  assert.match(src("(clinic)/consult/page.tsx"), /useUnsavedGuard\(dirty\)/);
  assert.match(src("components/consult/closeout/useCloseOut.ts"), /deleteVisitDrafts\(entry\.id\)/);
  assert.match(src("components/consult/rx/useRxSigning.ts"), /deleteDraft\(entryId, "rx"\)/);
  assert.match(src("components/consult/FollowUpPanel.tsx"), /useSectionDraft\(entryId, "followUp"/);
  assert.match(src("components/consult/orders/OrderForms.tsx"), /useSectionDraft\(queueEntryId, "orders\.admin"/);
  assert.match(src("components/consult/orders/OrderForms.tsx"), /useSectionDraft\(queueEntryId, "orders\.proc"/);
  assert.match(src("components/consult/AllergyRegister.tsx"), /useSectionDraft\(draftKey, "allergy"/);
  const pad = src("components/consult/rx/useRxPad.ts");
  assert.match(pad, /createAutosave\(/); // server PATCH when typing pauses (3 s idle / 30 s max, OPS-006)
  assert.match(pad, /LOCAL_DRAFT_MS/); // encrypted local draft 800 ms after the last edit
  assert.match(pad, /setRxConflict\(\{ mine: sent, server: serverItems \}\)/); // never a silent overwrite
  assert.match(src("lib/idleLock.ts"), /await clearDrafts\(\)/);
  assert.match(src("components/consult/draftStore.ts"), /sessionStorage\.removeItem\(KEY_SLOT\)/);
});
