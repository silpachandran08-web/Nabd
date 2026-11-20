"use client";

import { useCallback, useEffect, useRef, useState, type ComponentProps } from "react";
import { useTranslations } from "next-intl";
import s from "./paperQuickEntry.module.css";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { ApiError, api, intentKeys } from "../../lib/api";
import { clinicToday } from "../../lib/clinicTime";
import { ErrorSummary, FieldError, useFormErrors } from "../../lib/formErrors";
import { can } from "../../lib/permissions";
import { BLANK, FIELD_ORDER, LOOKUP_DEBOUNCE_MS, birthYear, cardBody, cardIssues, debouncer, lookupDigits, matches, pillState, type Card, type Hit, type Lookup } from "../../lib/paperEntry";

// ADM-014: paper cards typed in one after another, keyboard only. Tab moves on, Enter saves through the normal
// POST /v1/patients (source paper_import) and puts the cursor back in Name of a blank card. The mobile is looked up while it
// is typed; a number already on file must be answered "same person" (card skipped) or "new person" before the card saves.
// Mounted only for patients:create (Patient Registry, and the Paper tab of Setup > Data).
type Gate = "loading" | "live" | "demo_only" | "error";
type Candidate = { patientId: string; name: string; phone: string };

export default function PaperQuickEntry() {
  const t = useTranslations("shell.paperEntry");
  const [gate, setGate] = useState<Gate>("loading");
  const [card, setCard] = useState<Card>(BLANK);
  const [lookup, setLookup] = useState<Lookup>(null);
  const [newPersonFor, setNewPersonFor] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [counts, setCounts] = useState({ saved: 0, skipped: 0, lastSaveMs: 0 });
  const fe = useFormErrors("paper", { name: t("name"), phone: t("phone"), yearOfBirth: t("yearOfBirth"), gender: t("gender"), note: t("note") });
  const nameRef = useRef<HTMLInputElement>(null);
  const sameRef = useRef<HTMLButtonElement>(null);
  const keys = useRef(intentKeys()); // one Idempotency-Key per card: a retry of the same card re-sends the same key
  const digits = lookupDigits(card.phone);
  const wanted = useRef(digits); // an answer for a number that is no longer the typed one is dropped
  useEffect(() => { wanted.current = digits; }, [digits]);

  const loadGate = useCallback(async () => {
    setGate("loading");
    try { setGate((await api.get<{ dataGate: "live" | "demo_only" }>("/patients/data-gate")).dataGate === "live" ? "live" : "demo_only"); }
    catch { setGate("error"); }
  }, []);
  useEffect(() => { void Promise.resolve().then(loadGate); }, [loadGate]);

  const [ask] = useState(() => debouncer(async (d: string) => {
    let answer: Lookup;
    try { answer = { digits: d, hits: ((await api.get<{ data?: Hit[] }>(`/patients?q=${encodeURIComponent(d)}&limit=5`)).data ?? []) }; }
    catch { answer = { digits: d, hits: [], failed: true }; }
    if (wanted.current === d) setLookup(answer);
  }, LOOKUP_DEBOUNCE_MS));
  useEffect(() => {
    if (gate !== "live" || !digits || !can("patients:view")) return; // without the lookup the save itself still answers 409
    ask.push(digits);
    return () => ask.cancel();
  }, [ask, digits, gate]);

  const focusField = (name: string) => document.getElementById(`paper-${name}`)?.focus();
  const set = (name: keyof Card, value: string) => { if (!saving) { setCard((c) => ({ ...c, [name]: value })); fe.clearField(name); } };

  function next(outcome: "saved" | "skipped", startedAt = 0) {
    setCard(BLANK); setLookup(null); setNewPersonFor(null); fe.clear();
    keys.current.done("card");
    nameRef.current?.focus();
    const ms = Math.round(performance.now() - startedAt); // Enter to cursor-in-Name, readable as data-last-save-ms (the 1 s target)
    setCounts((c) => ({ saved: c.saved + (outcome === "saved" ? 1 : 0), skipped: c.skipped + (outcome === "skipped" ? 1 : 0),
      lastSaveMs: outcome === "saved" ? ms : c.lastSaveMs }));
  }

  async function save(asNewPerson = false) {
    if (saving || gate !== "live") return;
    const issues = cardIssues(card, Number(clinicToday().slice(0, 4)));
    const bad = FIELD_ORDER.find((f) => issues[f]);
    if (bad) {
      fe.clear();
      fe.setFields(() => Object.fromEntries(Object.entries(issues).map(([f, code]) => [f, t(`issue_${code}`, { year: clinicToday().slice(0, 4) })])));
      focusField(bad);
      return;
    }
    const pill = asNewPerson ? "new" : pillState(card.phone, lookup, newPersonFor);
    if (pill === "match") { sameRef.current?.focus(); return; } // answer "same person" or "new person" first
    const startedAt = performance.now();
    const body = cardBody(card, pill === "new");
    setSaving(true);
    fe.clear();
    try {
      await api.post("/patients", body, { idempotencyKey: keys.current.get("card", body) });
      next("saved", startedAt);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && Array.isArray(e.problem.candidates)) {
        keys.current.done("card"); // a definitive answer: the confirmed re-send is a new intent
        setNewPersonFor(null);
        setLookup({ digits, fromSave: true, hits: (e.problem.candidates as Candidate[]).map((c) => ({ id: c.patientId, name: c.name, phone: c.phone })) });
        setTimeout(() => sameRef.current?.focus(), 0);
      } else if (e instanceof ApiError && e.status === 403 && /DPA_REQUIRED/.test(String(e.problem.type ?? ""))) {
        setGate("demo_only");
      } else {
        fe.fail(e, t("saveFailed"));
        const field = e instanceof ApiError ? FIELD_ORDER.find((f) => f in e.fieldErrors()) : undefined;
        if (field) focusField(field);
      }
    } finally { setSaving(false); }
  }

  function chooseNewPerson() {
    setNewPersonFor(digits);
    const empty = FIELD_ORDER.find((f) => !card[f].trim());
    if (empty) focusField(empty); else void save(true);
  }

  if (gate === "loading") return <Skeleton rows={3} label={t("loading")} />;
  if (gate === "error") return <ErrorState message={t("gateFailed")} onRetry={() => void loadGate()} />;

  const blocked = gate === "demo_only";
  const pill = pillState(card.phone, lookup, newPersonFor);
  const found = matches(lookup, digits);
  const text = (name: CardInput, extra: ComponentProps<"input"> = {}) => (
    <div className={s.field}>
      <label className={s.label} htmlFor={`paper-${name}`}>{t(name)}</label>
      <input id={`paper-${name}`} className={s.input} value={card[name]} readOnly={saving} autoComplete="off"
        onChange={(e) => set(name, e.target.value)} {...fe.attrs(name)} {...extra} />
      <FieldError fe={fe} name={name} className={s.err} />
    </div>
  );

  return (
    <section className={s.panel} aria-label={t("title")}>
      {blocked ? (
        <div className={s.blocked} role="status">
          <strong>{t("dpaTitle")}</strong>
          <p className={s.note}>{t("dpaBody")}</p>
        </div>
      ) : <p className={s.note}>{t("intro")}</p>}

      <form className={s.panel} noValidate aria-busy={saving} onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <fieldset className={s.card} disabled={blocked}>
          {text("name", { ref: nameRef })}
          <div className={s.field}>
            {text("phone", { inputMode: "tel" })}
            <div className={s.pill} role="status" aria-live="polite" data-pill={pill}>
              {pill === "checking" && <span className={s.note}>{t("checking")}</span>}
              {pill === "clear" && <span className={s.ok}>{t("noMatch")}</span>}
              {pill === "failed" && <span className={s.note}>{t("lookupFailed")}</span>}
              {pill === "new" && <span className={s.warn}>{t("markedNew")}</span>}
              {pill === "match" && (
                <div className={s.match}>
                  <span className={s.warn}>{t("matchTitle")}</span>
                  <ul className={s.hits}>
                    {found.map((h) => <li key={h.id}>{birthYear(h.dob) ? t("matchRow", { name: h.name, year: birthYear(h.dob) }) : h.name}</li>)}
                  </ul>
                  <div className={s.row}>
                    <button type="button" ref={sameRef} className={s.btn} onClick={() => next("skipped")}>{t("samePerson")}</button>
                    <button type="button" className={s.btn} onClick={chooseNewPerson}>{t("newPerson")}</button>
                  </div>
                </div>
              )}
            </div>
          </div>
          {text("yearOfBirth", { inputMode: "numeric", maxLength: 4 })}
          <div className={s.field}>
            <label className={s.label} htmlFor="paper-gender">{t("gender")}</label>
            {/* Option labels start with M / F / O in every language, so one key picks the value; Enter on a select does not submit by itself. */}
            <select id="paper-gender" className={s.input} value={card.gender} onChange={(e) => set("gender", e.target.value)} {...fe.attrs("gender")}
              onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void save(); } }}>
              <option value="">{t("genderPick")}</option>
              {(["male", "female", "other"] as const).map((g) => <option key={g} value={g}>{t(`gender_${g}`)}</option>)}
            </select>
            <FieldError fe={fe} name="gender" className={s.err} />
          </div>
          {text("note", { maxLength: 500 })}
          <div className={s.row}>
            <button type="submit" className={s.btnPrimary} disabled={blocked || saving}>{saving ? t("saving") : t("save")}</button>
          </div>
        </fieldset>
        <ErrorSummary fe={fe} className={s.err} />
      </form>

      <p className={s.counter} role="status" aria-live="polite" data-last-save-ms={counts.lastSaveMs}>{t("counter", { saved: counts.saved, skipped: counts.skipped })}</p>
    </section>
  );
}

type CardInput = "name" | "phone" | "yearOfBirth" | "note";
