// PLT-054: the data safety training (module rules, quiz marking, en + hi text), the completion state shapes, and where the
// module, the clinic team list and the operator console pages are wired in.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createTranslator } from "next-intl";
import { loadMessages } from "@nabd/i18n";
import {
  PASS_MARK, QUIZ_OPTIONS, SECTION_POINTS, TRAINING_MODULE, TRAINING_QUIZ, TRAINING_SECTIONS, asTrainingRoster, asTrainingStatus, quizComplete, quizPassed, quizScore,
} from "../../app/shell/training.ts";
import { checkText } from "../../app/lib/trial.ts";

const app = new URL("../../", import.meta.url).pathname;
const repo = new URL("../../../../../", import.meta.url).pathname;
const msgDir = new URL("../../../../packages/i18n/messages/", import.meta.url).pathname;
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, `${p}${k}.`) : [[`${p}${k}`, v]]));
const training = (loc) => Object.fromEntries(flat(JSON.parse(readFileSync(`${msgDir}${loc}/help.json`, "utf8")).training));
const src = (p) => readFileSync(`${app}${p}`, "utf8");

test("module id and pass mark match Core (TrainingRules)", () => {
  const java = readFileSync(`${repo}backend/src/main/java/com/nabd/hms/common/TrainingRules.java`, "utf8");
  assert.ok(java.includes(`REQUIRED_MODULE = "${TRAINING_MODULE}"`));
  assert.ok(java.includes(`PASS_MARK = ${PASS_MARK}`));
});

test("quiz marking: four of five right is the pass mark, three is not, and a score under it is never posted", () => {
  const right = Object.fromEntries(TRAINING_QUIZ.map((q) => [q.id, q.correct]));
  assert.equal(quizScore(right), 100);
  const wrong = (n) => Object.fromEntries(TRAINING_QUIZ.map((q, i) => [q.id, i < n ? QUIZ_OPTIONS.find((o) => o !== q.correct) : q.correct]));
  assert.equal(quizScore(wrong(1)), 80);
  assert.ok(quizPassed(quizScore(wrong(1))));
  assert.equal(quizScore(wrong(2)), 60);
  assert.ok(!quizPassed(quizScore(wrong(2))));
  assert.equal(quizScore({}), 0);
  assert.ok(!quizComplete({ q1: "a" }) && quizComplete(right));
  assert.equal(TRAINING_QUIZ.length, 5);
  const mod = src("app/shell/TrainingModule.tsx");
  assert.match(mod, /if \(!quizPassed\(marked\)\) \{ setScore\(marked\); return; \}/, "a failed attempt stops before the POST");
  assert.ok(mod.includes("client.post(path, { moduleId: TRAINING_MODULE, score: marked })"));
});

test("text: every part and question exists in English and Hindi with the same placeholders, and Hindi is Hindi", () => {
  const en = training("en"), hi = training("hi");
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
  const vars = (s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join();
  for (const k of Object.keys(en)) {
    assert.equal(vars(hi[k]), vars(en[k]), k);
    assert.match(hi[k], /[ऀ-ॿ]/, `${k} is not Hindi`);
  }
  for (const s of TRAINING_SECTIONS) {
    assert.ok(en[`sections.${s}.title`] && en[`sections.${s}.p1`], s);
    for (const k of Object.keys(en).filter((x) => x.startsWith(`sections.${s}.p`))) assert.ok(SECTION_POINTS.includes(k.split(".").pop()), k);
  }
  for (const q of TRAINING_QUIZ) for (const part of ["q", ...QUIZ_OPTIONS]) assert.ok(en[`quiz.${q.id}.${part}`], `${q.id}.${part}`);
  // the module covers what the story names: phishing, PHI handling and never forwarding reports on personal WhatsApp
  assert.match(en["sections.whatsapp.p1"], /personal WhatsApp/);
  assert.match(en["sections.phishing.p1"], /never asks for your PIN/);
});

test("the status and result lines render in the UI language", async () => {
  const say = async (loc) => createTranslator({ locale: loc, messages: await loadMessages(loc, ["help"]), namespace: "help.training" });
  assert.equal((await say("en"))("result.failed", { score: 60, pass: 80 }), "You scored 60 out of 100. You need 80 to pass. Read the parts again and retake the questions.");
  assert.equal((await say("hi"))("roster.summary", { current: 3, total: 4 }), "4 में से 3 ने पूरा किया");
  assert.equal((await say("en"))("part", { n: 2, total: 6 }), "Part 2 of 6");
});

test("answers of the wrong shape read as a failed load, never as a completed training", () => {
  assert.deepEqual(asTrainingStatus({ moduleId: "phi-handling", state: "current", completedAt: "2026-11-20T10:00:00Z", dueAt: "2027-11-20T10:00:00Z", score: 100 }),
    { state: "current", completedAt: "2026-11-20T10:00:00Z", dueAt: "2027-11-20T10:00:00Z" });
  assert.deepEqual(asTrainingStatus({ state: "due", completedAt: null, dueAt: null }), { state: "due", completedAt: null, dueAt: null });
  for (const bad of [null, undefined, [], "ok", {}, { state: "done" }, { state: 1 }]) assert.equal(asTrainingStatus(bad), undefined);

  const clinic = asTrainingRoster({ moduleId: "phi-handling", total: 2, current: 1, staff: [
    { staffId: "s1", name: "Asha", role: "Owner", accountStatus: "active", completedAt: "2026-11-20T10:00:00Z", dueAt: "2027-11-20T10:00:00Z", state: "current" },
    { staffId: "s2", name: "Ravi", role: "Reception", accountStatus: "invited", completedAt: null, dueAt: null, state: "due" }] });
  assert.equal(clinic.entries.length, 2);
  assert.deepEqual(clinic.entries.map((e) => [e.id, e.state, e.invited]), [["s1", "current", false], ["s2", "due", true]]);
  const nabd = asTrainingRoster({ total: 1, current: 0, operators: [{ operatorId: "o1", name: "Op", role: "sre", joinedAt: "2026-11-01T00:00:00Z", completedAt: null, dueAt: "2026-11-08T00:00:00Z", state: "overdue" }] });
  assert.deepEqual([nabd.entries[0].id, nabd.entries[0].state, nabd.entries[0].dueAt], ["o1", "overdue", "2026-11-08T00:00:00Z"]);
  for (const bad of [null, {}, { total: 1, current: 0 }, { total: 1, current: 0, staff: [{ staffId: "s", name: "x", state: "nope" }] }, { total: 1, current: 0, staff: [{ name: "x", state: "due" }] }]) {
    assert.equal(asTrainingRoster(bad), undefined);
  }
});

test("wiring: Help has the module for every role, the staff page has the team list, go-live names the blocker", () => {
  const panel = src("app/shell/HelpPanel.tsx");
  assert.ok(panel.includes('<TrainingModule client={api} path="/training/completions" queryKey={key("training/completions/me")} />'));
  assert.ok(!/role === "\w+" && <TrainingModule/.test(panel), "not limited to one role");
  const mod = src("app/shell/TrainingModule.tsx");
  assert.ok(mod.includes("staleTime: Infinity") && mod.includes("refetchOnWindowFocus: false"), "one status read, no refetch storm");
  for (const state of ["t(\"loading\")", "t(\"error\")", "t(\"retry\")", "result.saveFailed"]) assert.ok(mod.includes(state), state);
  const staff = src("app/(clinic)/staff/page.tsx");
  assert.ok(staff.includes('{!loading && !forbidden && tab === "people" && <TrainingRoster />}'));
  const roster = src("app/(clinic)/staff/TrainingRoster.tsx");
  assert.ok(roster.includes('getter<unknown>("/training/completions")'));
  for (const state of ["<Skeleton", "<ErrorState", 't("empty")']) assert.ok(roster.includes(state), state);
  assert.match(checkText("staff_phi_training"), /data safety training/);
  assert.ok(readFileSync(`${repo}backend/src/main/java/com/nabd/hms/staff/TrainingGoLiveCheck.java`, "utf8").includes('new Result("staff_phi_training", untrained == 0, "/staff", null)'));
});

test("operator console: own training for every operator; the watch log, summary and team list under the Audit nav item", async () => {
  const { platformItemFor, platformRouteAllowed } = await import("../../app/(platform)/platform/surfaces.ts");
  assert.equal(platformItemFor("/platform/audit/compliance")?.id, "audit");
  assert.ok(!platformRouteAllowed("/platform/audit/compliance", ["command_centre:view", "support_tickets:view"]));
  assert.ok(platformRouteAllowed("/platform/audit/compliance", ["audit_compliance:view"]));
  assert.ok(platformRouteAllowed("/platform/training", ["command_centre:view"]), "no nav item: open to every operator");
  const own = src("app/(platform)/platform/training/page.tsx");
  assert.ok(own.includes('<TrainingModule client={platformApi} path="/platform/training/completions"'));
  assert.ok(src("app/(platform)/platform/page.tsx").includes('href="/platform/training"'));
  assert.ok(src("app/(platform)/platform/audit/page.tsx").includes('href="/platform/audit/compliance"'));
  const page = src("app/(platform)/platform/audit/compliance/page.tsx");
  for (const call of ['platformApi.get<WatchLog>("/platform/regulatory-watch"', 'platformApi.post("/platform/regulatory-watch", {', "/platform/regulatory-watch/summary?year=${year}&quarter=${quarter}", '"/platform/training/completions"']) {
    assert.ok(page.includes(call), call);
  }
  for (const state of ["<Skeleton", "<ErrorState", "<EmptyState", "<RoleDenied"]) assert.ok(page.includes(state), state);
  assert.ok(page.includes("log.data.markets.map") && !/["'](IN|SA|AE|QA)["']/.test(page), "markets come from the server (regions table), none hard-coded");
});
