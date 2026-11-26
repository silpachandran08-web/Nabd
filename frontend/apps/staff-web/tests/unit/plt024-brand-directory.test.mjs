// PLT-024: brand directory screens. The model functions (what is ever sent, how a refusal reads) and how the screens are wired
// (absent unless sharing is on, a check is an explicit tap, unlink only for the Owner, opt-out at registration).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { canTurnOn, directoryPhone, dobOk, linkBody, linkProblem, logKey, lookupPath, profileBody, sexKey } from "../../app/components/brandDirectory/brandDirectory.ts";

const read = (p) => readFileSync(new URL(`../../${p}`, import.meta.url), "utf8");
const json = (p) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${p}`, import.meta.url), "utf8"));

test("only a whole mobile number is ever sent to the other branches: names, MRNs and half-typed numbers never leave", () => {
  assert.equal(directoryPhone("+91 98450 12345"), "+91 98450 12345");
  assert.equal(directoryPhone("  9845012345 "), "9845012345");
  for (const q of ["Asha Rao", "MRN-0001", "98450", "984501234", "", "9845012345x", "1234567890123456"]) assert.equal(directoryPhone(q), null, q);
  assert.equal(lookupPath("+91 98450 12345"), "/patients/brand-directory?phone=%2B91+98450+12345");
});

test("a link needs a real past date of birth, and the body carries the pairwise reference, the mobile and the date", () => {
  assert.equal(dobOk("1990-04-02", "2026-11-23"), true);
  for (const d of ["", "2026-11-23", "2027-01-01", "1990-4-2", "1990-13-45"]) assert.equal(dobOk(d, "2026-11-23"), false, d);
  const m = { sourceTenantId: "t1", branchLabel: "A", sourcePatientRef: "ab".repeat(32), displayName: "Asha", sex: "female", birthYear: 1990 };
  assert.deepEqual(linkBody(m, "+919845012345", "1990-04-02"), { sourceTenantId: "t1", sourcePatientRef: "ab".repeat(32), phone: "+919845012345", dateOfBirth: "1990-04-02" });
  assert.deepEqual(Object.keys(linkBody(m, "p", "d")).sort(), ["dateOfBirth", "phone", "sourcePatientRef", "sourceTenantId"], "no name or sex is sent back");
});

test("every refusal reads as its own sentence", () => {
  assert.equal(linkProblem(422, "DOB_MISMATCH"), "dobMismatch");
  assert.equal(linkProblem(410, "SOURCE_OPTED_OUT"), "optedOut");
  assert.equal(linkProblem(409, "ALREADY_LINKED"), "alreadyLinked");
  assert.equal(linkProblem(403, "DIRECTORY_DISABLED"), "disabled");
  assert.equal(linkProblem(429), "tooMany");
  assert.equal(linkProblem(404), "gone");
  assert.equal(linkProblem(500), "failed");
  assert.equal(sexKey("female"), "sex_female");
  assert.equal(sexKey("x"), "sex_other");
});

test("the owner's switch: turning on needs the notice statement, turning off never does, and only the owner account can", () => {
  assert.deepEqual(profileBody(true, true), { profile: "DEMOGRAPHICS", noticeDisclosesDirectory: true });
  assert.deepEqual(profileBody(true, false), { profile: "DEMOGRAPHICS", noticeDisclosesDirectory: false });
  assert.deepEqual(profileBody(false, false), { profile: "NONE" });
  assert.equal(canTurnOn({ canEdit: true, profile: "NONE" }, false), false);
  assert.equal(canTurnOn({ canEdit: true, profile: "NONE" }, true), true);
  assert.equal(canTurnOn({ canEdit: false, profile: "NONE" }, true), false);
  assert.equal(canTurnOn({ canEdit: true, profile: "DEMOGRAPHICS" }, true), false);
  assert.equal(logKey({ direction: "incoming", kind: "link" }), "incomingLink");
  assert.equal(logKey({ direction: "outgoing", kind: "lookup" }), "outgoing");
});

test("wiring: no cross-branch search unless the brand shares, a check is a tap (not per keystroke), the panel is for patients:create", () => {
  const panel = read("app/components/brandDirectory/BrandDirectoryPanel.tsx");
  assert.match(panel, /if \(!enabled \|\| !phone\) return null/, "profile NONE: nothing is rendered");
  assert.match(panel, /\/patients\/brand-directory\/status/);
  assert.doesNotMatch(panel, /useEffect\([^)]*lookupPath/s, "the lookup runs only from the button");
  assert.match(panel, /onClick=\{\(\) => void check\(\)\}/);
  assert.match(panel, /intentKeys\(\)/, "a link carries an Idempotency-Key");
  const page = read("app/(clinic)/patients/page.tsx");
  assert.match(page, /canRegister && !forbidden && <BrandDirectoryPanel/, "patients:create only");
});

test("wiring: removing a link is for the Owner only, the opt-out is patients:edit, registration offers the opt-out", () => {
  const links = read("app/components/brandDirectory/BrandLinksSection.tsx");
  assert.match(links, /const owner = canEdit && isOwner\(\)/);
  assert.match(links, /\{owner && <button/, "absent, not disabled, for everyone else");
  assert.match(links, /canEdit && enabled && \(\s*<label/);
  assert.match(read("app/(clinic)/patients/drawer/OverviewTab.tsx"), /<BrandLinksSection/);
  const reg = read("app/(clinic)/patients/RegistryDialogs.tsx");
  assert.match(reg, /form\.brandOptOut \? \{ brandDirectoryOptOut: true \}/);
  assert.match(read("app/(clinic)/compliance/page.tsx"), /<DirectoryLog \/>/);
  assert.match(read("app/(clinic)/setup/privacy/page.tsx"), /<SharingSwitch \/>/);
});

test("every string exists in English and Hindi under the same keys", () => {
  const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`])).sort();
  const en = keys(json("en/shell.json").brandDirectory);
  assert.deepEqual(keys(json("hi/shell.json").brandDirectory), en);
  for (const k of ["problem.dobMismatch", "problem.optedOut", "problem.alreadyLinked", "registeredAt", "checkInPerson", "sharing.notice", "log.incomingLink", "links.unlinkConfirm"]) assert.ok(en.includes(k), k);
});
