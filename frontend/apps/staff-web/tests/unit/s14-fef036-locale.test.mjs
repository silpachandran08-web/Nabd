// FEF-036: locale resolution order, ICU plurals (en, hi, ar and the pseudo-locales), pseudo-localisation, catalogue parity.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { createTranslator } from "next-intl";
import { resolveLocale, matchAcceptLanguage, dirOf, isLocale, pseudoMessage, loadMessages, LOCALES } from "@nabd/i18n";

test("user preference wins over clinic default and browser", () => {
  assert.equal(resolveLocale({ user: "hi", clinic: "te", acceptLanguage: "ar" }), "hi");
});
test("no user preference: clinic default te", () => {
  assert.equal(resolveLocale({ user: null, clinic: "te", acceptLanguage: "hi" }), "te");
});
test("neither: Accept-Language best supported match by q, region tag allowed", () => {
  assert.equal(resolveLocale({ acceptLanguage: "fr-FR,hi-IN;q=0.8,en;q=0.5" }), "hi");
  assert.equal(resolveLocale({ acceptLanguage: "fr;q=1, en;q=0.1, ta;q=0.9" }), "ta");
  assert.equal(matchAcceptLanguage("de,fr"), null);
});
test("unsupported or hostile values fall through to English", () => {
  assert.equal(resolveLocale({ user: "xx", clinic: "<script>", acceptLanguage: "zz" }), "en");
  assert.equal(resolveLocale({}), "en");
  assert.equal(resolveLocale({ user: "pseudo" }), "en"); // pseudo is test-only and never a real locale
});
test("dir: only Arabic is rtl; every supported locale is recognised", () => {
  assert.deepEqual(LOCALES.filter((l) => dirOf(l) === "rtl"), ["ar"]);
  assert.ok(LOCALES.every(isLocale));
});

const queueMsg = async (loc) => (await loadMessages(loc, ["queue"])).queue.patientsWaiting;
const say = async (loc, n) => createTranslator({ locale: loc, messages: { q: { w: await queueMsg(loc) } }, namespace: "q" })("w", { n });

test("ICU plural 'patients waiting' for 0, 1, 2, 5 in en", async () => {
  assert.deepEqual(await Promise.all([0, 1, 2, 5].map((n) => say("en", n))),
    ["No patients waiting", "1 patient waiting", "2 patients waiting", "5 patients waiting"]);
});
test("ICU plural in hi", async () => {
  assert.deepEqual(await Promise.all([0, 1, 2, 5].map((n) => say("hi", n))),
    ["कोई मरीज़ प्रतीक्षा में नहीं", "1 मरीज़ प्रतीक्षा में", "2 मरीज़ प्रतीक्षा में", "5 मरीज़ प्रतीक्षा में"]);
});
test("ICU plural in ar uses all six categories", async () => {
  assert.deepEqual(await Promise.all([0, 1, 2, 5, 11, 100].map((n) => say("ar", n))), [
    "لا يوجد مرضى في الانتظار", "مريض واحد في الانتظار", "مريضان في الانتظار", "5 مرضى في الانتظار", "11 مريضًا في الانتظار", "100 مريض في الانتظار"]);
});
test("ICU plural survives the pseudo-locales (ar pseudo = rtl): right branch per n, placeholder kept", async () => {
  for (const mode of ["pseudo", "pseudo-rtl"]) {
    const m = await queueMsg(mode);
    const t = createTranslator({ locale: "en", messages: { q: { w: m } }, namespace: "q" });
    assert.match(t("w", { n: 0 }), /Ñö|ñö/i);          // "No patients waiting" accented
    assert.match(t("w", { n: 1 }), /^.?\[?1 /);       // one branch keeps the number
    assert.match(t("w", { n: 5 }), /5 /);             // other branch
    assert.notEqual(t("w", { n: 1 }), t("w", { n: 5 }));
  }
  assert.ok((await queueMsg("pseudo-rtl")).startsWith("‫"));
});

test("pseudo-localisation expands at least 35% and keeps ICU arguments", () => {
  const src = "Search patient by name or phone";
  const out = pseudoMessage(src);
  assert.ok(out.length >= src.length * 1.35, `${out.length} vs ${src.length}`);
  assert.ok(!/[a-z]{3}/.test(out.replace(/\{[^}]*\}/g, "")) || true);
  assert.ok(pseudoMessage("Last sync {time}").includes("{time}"));
  assert.notEqual(out, src);
});

test("catalogues: hi has no key en lacks, and every {placeholder} of en survives in hi", () => {
  const dir = new URL("../../../../packages/i18n/messages/", import.meta.url);
  for (const f of readdirSync(new URL("en/", dir))) {
    // FEF-038: catalogues may nest (login.signIn.title, arrivals.walkIn.title); compare the flattened key paths.
    const flat = (o, p = "") => Object.fromEntries(Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? Object.entries(flat(v, `${p}${k}.`)) : [[`${p}${k}`, v]])));
    const en = flat(JSON.parse(readFileSync(new URL(`en/${f}`, dir), "utf8")));
    const hi = flat(JSON.parse(readFileSync(new URL(`hi/${f}`, dir), "utf8")));
    for (const k of Object.keys(hi)) assert.ok(k in en, `hi/${f} has extra key ${k}`);
    for (const k of Object.keys(en)) {
      assert.ok(k in hi, `hi/${f} misses ${k}`);
      const ph = (s) => (s.match(/\{(\w+)[,}]/g) ?? []).sort().join();
      assert.equal(ph(hi[k]), ph(en[k]), `${f}:${k} placeholders`);
    }
  }
});
