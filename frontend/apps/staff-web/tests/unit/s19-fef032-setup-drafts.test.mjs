// FEF-032 fix round (S19-BUG-1, S19-BUG-2): on the old single setup page (32896bb) the add-form drafts, the specialty
// draft, the profile error, the payroll month, the loaded lists and the failed-save Retry banner were page state: they
// survived a tab switch and were dropped on leaving Setup. With one route per tab they live in the setup layout's store.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { SetupStoreProvider, createSetupStore, useSetupState } from "../../app/(clinic)/setup/setupStore.ts";

const APP = new URL("../../app/(clinic)/setup/", import.meta.url);
const src = (p) => readFileSync(new URL(p, APP), "utf8");

/** Mounts a fresh "tab" that reads one store key; returns its rendered value and its setter. */
function mount(store, key, initial) {
  let setter;
  const Tab = () => {
    const [v, set] = useSetupState(key, initial);
    setter = set;
    return createElement("output", null, JSON.stringify(v));
  };
  const html = renderToString(createElement(SetupStoreProvider, { store }, createElement(Tab)));
  return { value: JSON.parse(html.replace(/<\/?output>/g, "").replace(/&quot;/g, '"')), set: setter };
}

test("BUG-1: a half-typed charge survives leaving the tab and coming back (a fresh tab mount reads the draft)", () => {
  const store = createSetupStore();
  const charges = mount(store, "newCharge", { active: true });
  assert.deepEqual(charges.value, { active: true });
  charges.set({ active: true, code: "HALF", name: "Half typed charge" });
  mount(store, "policies-something", null); // the Policies tab is open in between
  assert.deepEqual(mount(store, "newCharge", { active: true }).value, { active: true, code: "HALF", name: "Half typed charge" });
});

test("BUG-1: updater form, separate keys per tab, and leaving Setup (a new store) starts empty again", () => {
  const store = createSetupStore();
  const h = mount(store, "newHoliday", { recurring: false });
  h.set((prev) => ({ ...prev, name: "Eid" }));
  assert.deepEqual(mount(store, "newHoliday", { recurring: false }).value, { recurring: false, name: "Eid" });
  assert.deepEqual(mount(store, "newShift", { patternJson: "{}" }).value, { patternJson: "{}" }, "other tabs keep their own draft");
  assert.deepEqual(mount(createSetupStore(), "newHoliday", { recurring: false }).value, { recurring: false }, "a new frame has no drafts");
});

test("BUG-2: the failed save stays in the store, so the banner shows on the next tab until a save succeeds", () => {
  const store = createSetupStore();
  mount(store, "saveFail", null).set({ message: "Request failed" });
  assert.deepEqual(mount(store, "saveFail", null).value, { message: "Request failed" });
  mount(store, "saveFail", null).set(null);
  assert.equal(mount(store, "saveFail", null).value, null);
});

test("wiring: the layout owns the store and the banner; every old page-level draft is a store key; the tab body has no banner", () => {
  const layout = src("layout.tsx");
  assert.match(layout, /<SetupStoreProvider>\s*<SaveFailBanner \/>\s*\{children\}\s*<\/SetupStoreProvider>/);
  assert.match(layout, /if \(segment !== null && !isTabRoute\(segment\)\) return <>\{children\}<\/>;/, "wizard / departments / workflows sit outside the store, as they were outside the old page");
  const keys = { "charges/page.tsx": ["newCharge"], "holidays/page.tsx": ["newHoliday"], "shifts/page.tsx": ["newShift"], "licences/page.tsx": ["newLicence"],
    "profile/page.tsx": ["specialtyDraft", "profileError"], "payroll/page.tsx": ["payrollMonth"], "setupTab.tsx": ["data", "saveFail"] };
  for (const [f, ks] of Object.entries(keys)) for (const k of ks) assert.match(src(f), new RegExp(`useSetupState(<[^(]*>)?\\("${k}"`), `${f}: ${k}`);
  for (const f of ["charges", "holidays", "shifts", "licences"]) assert.doesNotMatch(src(`${f}/page.tsx`), /\buseState\b/, `${f}: no route-local draft`);
  const body = src("setupTab.tsx").slice(src("setupTab.tsx").indexOf("export function SetupTabBody"));
  assert.doesNotMatch(body, /saveFail|>Retry</, "the save banner is the layout's, not the tab's");
});
