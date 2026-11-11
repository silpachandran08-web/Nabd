// ACC-035: tour completion per account (server), replay from Help, the ten-task reception competency check, the top-five desk
// task video slots (en + hi, none invented), Hindi for every tour and tip, and the train-the-trainer pack.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createTranslator } from "next-intl";
import { loadMessages } from "@nabd/i18n";
import { COMPETENCY_TASKS, DESK_VIDEO_TASKS, DESK_VIDEOS, TOUR_ROLES, VIDEO_LANGS, taskKey, tasksDone, tourKey } from "../../app/shell/help.ts";

const app = new URL("../../", import.meta.url).pathname;
const repo = new URL("../../../../../", import.meta.url).pathname;
const msgDir = new URL("../../../../packages/i18n/messages/", import.meta.url).pathname;
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, `${p}${k}.`) : [[`${p}${k}`, v]]));
const help = (loc) => Object.fromEntries(flat(JSON.parse(readFileSync(`${msgDir}${loc}/help.json`, "utf8"))));

test("keys match Core's allow-list (StaffOnboardingService): four tours and ten tasks in the pack's order", () => {
  const java = readFileSync(`${repo}backend/src/main/java/com/nabd/hms/staff/StaffOnboardingService.java`, "utf8");
  for (const r of TOUR_ROLES) assert.ok(java.includes(`"${tourKey(r)}"`), r);
  const order = [...java.matchAll(/"task\.(\w+)"/g)].map((m) => m[1]);
  assert.deepEqual(order, [...COMPETENCY_TASKS]);
  assert.equal(COMPETENCY_TASKS.length, 10);
  assert.equal(tasksDone({ completed: ["task.walkin", "task.search", "tour.reception"], competencyPassedAt: null }), 2);
  assert.equal(tasksDone(undefined), 0);
});

test("Hindi: every help key (tours, screen tips, competency, videos) exists with the same placeholders and is not English", () => {
  const en = help("en"), hi = help("hi");
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
  const vars = (s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join();
  for (const k of Object.keys(en)) {
    assert.equal(vars(hi[k]), vars(en[k]), k);
    if (/^(tours|screens|competency|videos)\./.test(k) && /[A-Za-z]{4}/.test(en[k].replace(/\{\w+\}/g, ""))) assert.match(hi[k], /[ऀ-ॿ]/, `${k} is not Hindi`);
  }
  for (const task of COMPETENCY_TASKS) assert.ok(en[`competency.tasks.${task}`] && hi[`competency.tasks.${task}`], task);
});

test("the competency progress and pass line render in the UI language", async () => {
  const say = async (loc) => createTranslator({ locale: loc, messages: await loadMessages(loc, ["help"]), namespace: "help" });
  assert.equal((await say("en"))("competency.progress", { done: 3, total: 10 }), "3 of 10 done");
  assert.equal((await say("hi"))("competency.progress", { done: 3, total: 10 }), "10 में से 3 पूरे");
  assert.equal((await say("hi"))("videos.missing", { lang: "हिंदी" }), "हिंदी: अभी उपलब्ध नहीं");
});

test("top five desk tasks each have an en and a hi video slot; any link given is https, none is invented", () => {
  assert.deepEqual([...DESK_VIDEO_TASKS].sort(), ["checkin", "checkout", "delay", "requests", "walkin"]);
  assert.deepEqual([...VIDEO_LANGS], ["en", "hi"]);
  for (const task of DESK_VIDEO_TASKS) for (const url of Object.values(DESK_VIDEOS[task])) assert.match(url, /^https:\/\//);
  const missing = DESK_VIDEO_TASKS.flatMap((t) => VIDEO_LANGS.filter((l) => !DESK_VIDEOS[t][l]).map((l) => `${t}/${l}`));
  assert.equal(missing.length, 10, "all ten slots are external content still to be recorded (ACC-035 report)");
});

test("tour: completion comes from Core (no browser flag); replay from Help; the checklist is for reception only", () => {
  const card = readFileSync(`${app}app/shell/TourCard.tsx`, "utf8");
  assert.ok(card.includes("useOnboarding(") && !card.includes("localStorage"), "server progress, no per-browser flag");
  assert.ok(card.includes("complete(tourKey(role))"));
  const panel = readFileSync(`${app}app/shell/HelpPanel.tsx`, "utf8");
  assert.ok(panel.includes("TOUR_EVENT") && panel.includes('t("menu.replayTour")'), "replay from Help");
  assert.ok(panel.includes('role === "reception" && <CompetencyChecklist />'));
  const hook = readFileSync(`${app}app/shell/useOnboarding.ts`, "utf8");
  assert.ok(hook.includes("staleTime: Infinity") && hook.includes("refetchOnWindowFocus: false"), "one read per session, no refetch storm");
  const list = readFileSync(`${app}app/shell/CompetencyChecklist.tsx`, "utf8");
  for (const s of ['t("competency.loading")', 't("competency.error")', 't("competency.saveFailed")']) assert.ok(list.includes(s), s);
});

test("train-the-trainer pack lists the same ten tasks and the competency rule", () => {
  const pack = readFileSync(`${repo}docs/training/reception-train-the-trainer.md`, "utf8");
  for (const task of COMPETENCY_TASKS) assert.ok(pack.includes(taskKey(task)), task);
  assert.match(pack, /all ten/i);
});
