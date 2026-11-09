// FEF-062: help keyed by screen id, first-run tours per role (5 steps, en + hi), completion in the browser, deferral during a consult.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createTranslator } from "next-intl";
import { loadMessages } from "@nabd/i18n";
import { HELP_VIDEOS, SCREEN_IDS, TOUR_ROLES, TOUR_STEPS, consultActive, markTourDone, screenIdFor, tourDone, tourDue } from "../../app/shell/help.ts";
import { SHORTCUTS } from "../../app/shell/shortcuts.ts";

const msgDir = new URL("../../../../packages/i18n/messages/", import.meta.url).pathname;
const cat = (loc) => JSON.parse(readFileSync(`${msgDir}${loc}/help.json`, "utf8"));
const fakeStore = () => { const m = new Map(); return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => m.set(k, v) }; };

test("screen id: route prefix with a path boundary, generic otherwise", () => {
  assert.equal(screenIdFor("/arrivals"), "arrivals");
  assert.equal(screenIdFor("/checkout/abc"), "billing");
  assert.equal(screenIdFor("/my-day"), "myday");
  assert.equal(screenIdFor("/patientsx"), "generic");
  assert.equal(screenIdFor(null), "generic");
});

test("help content: every screen id has a title and first paragraph in en and hi; video links are config, none invented", () => {
  for (const loc of ["en", "hi"]) {
    const s = cat(loc).screens;
    for (const id of SCREEN_IDS) assert.ok(s[id]?.title && s[id]?.p1, `${loc}: screens.${id}`);
  }
  for (const v of Object.values(HELP_VIDEOS)) assert.match(v, /^https:\/\//);
});

test("tours: reception, nurse, doctor and owner each have exactly 5 steps with title and body in en and hi", () => {
  assert.deepEqual([...TOUR_ROLES], ["reception", "nurse", "doctor", "owner"]);
  assert.equal(TOUR_STEPS, 5);
  for (const loc of ["en", "hi"]) for (const role of TOUR_ROLES) {
    const steps = cat(loc).tours[role];
    assert.deepEqual(Object.keys(steps).sort(), ["s1", "s2", "s3", "s4", "s5"], `${loc}: tours.${role}`);
    for (const s of Object.values(steps)) assert.ok(s.title.trim() && s.body.trim());
  }
});

test("the tour renders in the UI language: step 1 of the reception tour in hi is Devanagari", async () => {
  const say = async (loc) => createTranslator({ locale: loc, messages: await loadMessages(loc, ["help"]), namespace: "help" });
  assert.match((await say("hi"))("tours.reception.s1.title"), /[ऀ-ॿ]/);
  assert.equal((await say("en"))("tour.step", { n: 2, total: 5 }), "Step 2 of 5");
  assert.equal((await say("hi"))("panel.title", { screen: "x" }), "मदद: x");
});

test("first sign-in: due for a tour role until marked done; completion is per staff id and role, never for a role without a tour", () => {
  const store = fakeStore();
  assert.equal(tourDue({ role: "reception", done: tourDone(store, "s1", "reception"), consult: false }), true);
  markTourDone(store, "s1", "reception");
  assert.equal(tourDone(store, "s1", "reception"), true);
  assert.equal(tourDue({ role: "reception", done: tourDone(store, "s1", "reception"), consult: false }), false);
  assert.equal(tourDone(store, "s2", "reception"), false, "another staff member on the same browser");
  assert.equal(tourDone(store, "s1", "nurse"), false, "another role context");
  assert.equal(tourDue({ role: "pharmacist", done: false, consult: false }), false);
  assert.equal(tourDue({ role: null, done: false, consult: false }), false);
  store.setItem("nabd_tour_done", "{garbage");
  assert.equal(tourDone(store, "s1", "reception"), false, "a corrupt value reads as not done");
});

test("an open consult defers the tour (consult workspace with ?entry=); the consult list without a visit does not", () => {
  const q = (entry) => ({ get: (k) => (k === "entry" ? entry : null) });
  assert.equal(consultActive("/consult", q("v1")), true);
  assert.equal(consultActive("/consult", q(null)), false);
  assert.equal(consultActive("/arrivals", q("v1")), false);
  assert.equal(tourDue({ role: "doctor", done: false, consult: true }), false);
  assert.equal(tourDue({ role: "doctor", done: false, consult: false }), true);
});

test("F1 is in the shortcut registry on every screen", () => {
  assert.ok(SHORTCUTS.some((s) => s.id === "screenhelp" && s.keys[0] === "F1" && s.scope === "all"));
});

test("shell: HelpPanel and TourCard are mounted above the route, the user menu offers help and replay", () => {
  const app = new URL("../../app/", import.meta.url).pathname;
  const shell = readFileSync(`${app}AppShell.tsx`, "utf8");
  assert.ok(shell.includes("<HelpPanel />") && shell.includes("<TourCard />"));
  const menu = readFileSync(`${app}shell/ShellUserMenu.tsx`, "utf8");
  assert.ok(menu.includes('th("menu.help")') && menu.includes('th("menu.replayTour")'));
  assert.ok(!readFileSync(`${app}shell/TourCard.tsx`, "utf8").includes("aria-modal"), "the tour never blocks work");
});
