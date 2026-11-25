// PLT-023: branch picker at sign-in and the top-bar switcher (pure model). `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { BRANCH_FLUSH_EVENT, branchColor, flushOpenDrafts, isBranchChoice, onBranchSwitchFlush, selectBody, showSwitcher, switchBranch } from "../../app/lib/branches.ts";
import { initialLogin, loginReducer, signInAction } from "../../app/(auth)/login/loginMachine.ts";

const A = { tenantId: "11111111-1111-4111-8111-111111111111", name: "Clinic A" };
const B = { tenantId: "22222222-2222-4222-8222-222222222222", name: "Clinic B" };

test("only a person with more than one branch gets a switcher", () => {
  assert.equal(showSwitcher(null), false);
  assert.equal(showSwitcher({ currentTenantId: A.tenantId, reportsOnly: false, branches: [A] }), false); // the receptionist of one branch
  assert.equal(showSwitcher({ currentTenantId: A.tenantId, reportsOnly: false, branches: [A, B] }), true);
});

test("a sign-in answer is a branch choice only with the choice token and the branches", () => {
  assert.equal(isBranchChoice({ branchSelectToken: "t", expiresIn: 300, branches: [A, B] }), true);
  assert.equal(isBranchChoice({ accessToken: "a", refreshToken: "r", expiresIn: 900 }), false);
  assert.equal(isBranchChoice({ challengeId: "c", method: "totp", expiresIn: 300 }), false);
  assert.equal(isBranchChoice(null), false);
});

test("the sign-in reducer shows the picker, keeps the choice token out of the form, and back clears it", () => {
  const choice = { branchSelectToken: "tok", expiresIn: 300, branches: [A, B] };
  const next = signInAction(choice);
  assert.equal(next.type, "branchChoice");
  const s = loginReducer({ ...initialLogin, pin: "123456", code: "111111" }, next);
  assert.equal(s.view, "branchPick");
  assert.equal(s.pin, ""); // the PIN does not wait behind the picker
  assert.deepEqual(s.branchChoice, choice);
  const back = loginReducer(s, { type: "back" });
  assert.equal(back.view, "login");
  assert.equal(back.branchChoice, null);
  assert.equal(signInAction({ accessToken: "a", refreshToken: "r", expiresIn: 1 }), null); // a single-branch user signs in as before
});

test("the select body carries one tenant and the choice token, nothing else", () => {
  assert.deepEqual(selectBody({ branchSelectToken: "tok" }, B.tenantId), { tenantId: B.tenantId, branchSelectToken: "tok" });
});

test("each branch has one stable colour from the six chart tokens", () => {
  assert.equal(branchColor(A.tenantId), branchColor(A.tenantId));
  for (const id of [A.tenantId, B.tenantId, "33333333-3333-4333-8333-333333333333"]) assert.match(branchColor(id), /^var\(--nb-chart-[1-6]\)$/);
});

test("a switch saves drafts first, then selects, stores, clears caches and reloads, in that order", async () => {
  const log = [];
  await switchBranch({
    flush: async () => { log.push("flush"); },
    select: async (id, ro) => { log.push(`select ${id} ${ro}`); return { accessToken: "new" }; },
    store: (p) => log.push(`store ${p.accessToken}`),
    clearCaches: async () => { log.push("clear"); },
    reload: () => log.push("reload"),
  }, B.tenantId);
  assert.deepEqual(log, ["flush", `select ${B.tenantId} false`, "store new", "clear", "reload"]);
});

test("a refused switch (not linked, suspended there, inactive) clears nothing and keeps the session", async () => {
  const log = [];
  await assert.rejects(switchBranch({
    flush: async () => { log.push("flush"); },
    select: async () => { throw new Error("403 NOT_LINKED"); },
    store: () => log.push("store"),
    clearCaches: async () => { log.push("clear"); },
    reload: () => log.push("reload"),
  }, B.tenantId, true), /NOT_LINKED/);
  assert.deepEqual(log, ["flush"]);
});

test("open drafts are awaited before a switch, a failed save does not block it, a hung save waits at most the limit", async () => {
  globalThis.window = new EventTarget(); // the node test has no DOM
  const saved = [];
  const off1 = onBranchSwitchFlush(async () => { await new Promise((r) => setTimeout(r, 20)); saved.push("note"); });
  const off2 = onBranchSwitchFlush(async () => { throw new Error("offline"); });
  await flushOpenDrafts(1000);
  assert.deepEqual(saved, ["note"]);
  off1(); off2();
  const off3 = onBranchSwitchFlush(() => new Promise(() => {})); // never settles
  const t0 = Date.now();
  await flushOpenDrafts(60);
  assert.ok(Date.now() - t0 < 500);
  off3();
  const before = Date.now();
  await flushOpenDrafts(1000); // no listeners: returns at once
  assert.ok(Date.now() - before < 100);
  assert.equal(typeof BRANCH_FLUSH_EVENT, "string");
});
