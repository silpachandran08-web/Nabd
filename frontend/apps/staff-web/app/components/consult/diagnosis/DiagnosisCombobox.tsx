"use client";

// CLN-012: the diagnosis field of the Assess step. With an ICD-10 list in use it is a type-ahead (code, name or synonym;
// the doctor's favourites first) over GET /clinical/icd10/search plus the visit's coded diagnoses (one primary, optional
// note per code). The server checks every code and writes the note's diagnosis line from the coded rows; this component
// passes that line to the note editor so the autosave carries the same text. Without a list (none loaded or licensed for
// the clinic's region) the free-text field of DOC-027 is shown unchanged. A signed note is changed only by an amendment
// with a reason. All rules that can be tested without a browser are in diagnosisModel.ts.
import { useTranslations } from "next-intl";
import { useCallback, useEffect, useId, useRef, useState } from "react";
import { api, reasonText } from "../../../lib/api";
import DiagnosisField from "../DiagnosisField";
import StatusPill from "../../ui/StatusPill";
import {
  addDx, canSaveAmendment, fieldMode, matchFavourites, MAX_QUALIFIER, MIN_CHARS, removeDx, sameDx, setPrimary, setQualifier, sourceKey, toRequest, withoutPicked,
  type Dx, type DxResult, type Icd10Item, type Icd10Result,
} from "./diagnosisModel";
import p from "../consultParts.module.css";
import rx from "../RxPad.module.css";
import s from "./diagnosis.module.css";

const DEBOUNCE_MS = 200;
type ListState = { load: "loading" | "ok" | "error"; version: string | null; source: string | null };
type Find = { query: string; options: Icd10Item[]; open: boolean; active: number; state: "idle" | "loading" | "done" | "error" };
const NO_FIND: Find = { query: "", options: [], open: false, active: -1, state: "idle" };

export type DiagnosisVisit = { entryId: string; locked: boolean; canAmend: boolean };

export default function DiagnosisCombobox({ visit, value, onChange, disabled, inputClassName }: {
  visit: DiagnosisVisit; value: string; onChange: (v: string) => void; disabled: boolean; inputClassName: string;
}) {
  const t = useTranslations("consult.diagnosisCombobox");
  const g = useTranslations("glossary");
  const uid = useId();
  const listId = `${uid}-list`;
  const [list, setList] = useState<ListState>({ load: "loading", version: null, source: null });
  const [favs, setFavs] = useState<Icd10Item[]>([]);
  const [saved, setSaved] = useState<Dx[]>([]);
  const [draft, setDraft] = useState<Dx[] | null>(null); // the amendment being prepared on a signed note
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ error?: string; notice?: string }>({});
  const [find, setFind] = useState<Find>(NO_FIND);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const seq = useRef(0);
  const { query, options, open, active } = find;
  const setActive = (next: (a: number) => number) => setFind((f) => ({ ...f, active: next(f.active) }));

  // The first load starts from the "loading" state above; retry() sets it again before calling this.
  const fetchAll = useCallback(async () => {
    try {
      const [f, d] = await Promise.all([api.get<Icd10Result>("/clinical/icd10/favourites"), api.get<DxResult>(`/clinical/visits/${visit.entryId}/diagnoses`)]);
      setFavs(f.items ?? []);
      setSaved(d.items ?? []);
      setList({ load: "ok", version: f.version ?? null, source: f.source ?? null });
    } catch {
      setList((l) => ({ ...l, load: "error" }));
    }
  }, [visit.entryId]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- loads on mount; the setState calls run after the awaited requests
  useEffect(() => { void fetchAll(); }, [fetchAll]);
  const retry = () => { setList((l) => ({ ...l, load: "loading" })); void fetchAll(); };
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); seq.current += 1; }, []);

  const mode = fieldMode({ load: list.load, version: list.version, coded: saved.length });
  if (mode === "loading" || mode === "freeText" || mode === "error") {
    // The free-text field is never blocked by the list: it shows while loading and when the search could not be loaded.
    return (
      <div className={s.wrap}>
        <DiagnosisField value={value} disabled={disabled} inputClassName={inputClassName} onChange={onChange} />
        {mode === "loading" && <p className={s.meta} role="status">{t("loading")}</p>}
        {mode === "error" && (
          <p className={s.error} role="alert">{t("loadFailed")}{" "}<button type="button" className={s.act} onClick={retry}>{t("retry")}</button></p>
        )}
      </div>
    );
  }

  const amending = draft !== null;
  const editable = amending || (!disabled && !visit.locked);
  const shown = draft ?? saved;
  const canSearch = editable && list.version !== null;

  async function save(next: Dx[]) {
    if (sameDx(next, saved)) return;
    setBusy(true); setMsg({});
    try {
      const r = await api.put<DxResult>(`/clinical/visits/${visit.entryId}/diagnoses`, toRequest(next));
      setSaved(r.items ?? []);
      onChange(r.summary ?? "");
    } catch (e) {
      setMsg({ error: reasonText(e, t("saveFailed")) });
    } finally {
      setBusy(false);
    }
  }

  async function saveAmendment() {
    if (!draft || !canSaveAmendment(saved, draft, reason)) return;
    setBusy(true); setMsg({});
    try {
      const r = await api.put<DxResult>(`/clinical/visits/${visit.entryId}/diagnoses`, toRequest(draft, reason));
      setSaved(r.items ?? []);
      setDraft(null); setReason("");
      setMsg({ notice: t("amended") });
    } catch (e) {
      setMsg({ error: reasonText(e, t("saveFailed")) });
    } finally {
      setBusy(false);
    }
  }

  /** A change is saved at once on a draft note; on a signed note it only edits the amendment being prepared. */
  function apply(next: Dx[]) {
    if (amending) setDraft(next); else void save(next);
  }

  async function toggleStar(d: { code: string; title: string }) {
    const starred = favs.some((f) => f.code === d.code);
    setMsg({});
    try {
      if (starred) await api.delete(`/clinical/icd10/favourites/${encodeURIComponent(d.code)}`);
      else await api.put(`/clinical/icd10/favourites/${encodeURIComponent(d.code)}`);
      setFavs((cur) => (starred ? cur.filter((f) => f.code !== d.code) : [...cur, { code: d.code, title: d.title, favourite: true }]));
    } catch (e) {
      setMsg({ error: reasonText(e, t("saveFailed")) });
    }
  }

  function search(text: string) {
    if (timer.current) clearTimeout(timer.current);
    const mine = ++seq.current;
    const q = text.trim();
    const local = withoutPicked(matchFavourites(favs, q), shown); // favourites answer at once, no request
    const short = q.length < MIN_CHARS;
    setFind({ query: text, options: local, open: true, active: -1, state: local.length > 0 ? "done" : short ? "idle" : "loading" });
    if (short) return;
    timer.current = setTimeout(async () => {
      try {
        const r = await api.get<Icd10Result>(`/clinical/icd10/search?q=${encodeURIComponent(q)}&limit=10`);
        if (mine === seq.current) setFind((f) => ({ ...f, options: withoutPicked(r.items ?? [], shown), state: "done", active: -1 }));
      } catch {
        if (mine === seq.current && local.length === 0) setFind((f) => ({ ...f, state: "error" }));
      }
    }, DEBOUNCE_MS);
  }

  function pick(it: Icd10Item) {
    seq.current += 1;
    if (timer.current) clearTimeout(timer.current);
    setFind(NO_FIND);
    apply(addDx(shown, it, shown.length === 0 && !visit.locked ? value : ""));
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setFind((f) => ({ ...f, open: true, active: options.length > 0 ? (f.active + 1) % options.length : -1 }));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (options.length > 0) setActive((a) => (a <= 0 ? options.length - 1 : a - 1));
    } else if (e.key === "Enter" && open && active >= 0 && options[active]) {
      e.preventDefault(); pick(options[active]);
    } else if (e.key === "Escape" && open) {
      e.preventDefault(); e.stopPropagation(); setFind((f) => ({ ...f, open: false, active: -1 }));
    }
  }

  const hasList = open && options.length > 0;
  const status =
    find.state === "loading" ? t("searching")
    : find.state === "error" ? t("unavailable")
    : find.state === "done" && options.length === 0 ? t("noMatch")
    : hasList ? t("suggestionCount", { count: options.length })
    : "";

  return (
    <div className={s.wrap}>
      {list.version !== null
        ? <p className={s.meta}>{t(sourceKey(list.source), { version: list.version })}</p>
        : <p className={s.meta}>{t("noListForNewCodes")}</p>}

      {canSearch && (
        <div className={s.search}>
          <label className={rx.label} htmlFor={`${uid}-q`}>{t("searchLabel")}</label>
          <input id={`${uid}-q`} className={inputClassName} role="combobox" aria-autocomplete="list" aria-expanded={hasList} aria-controls={listId}
            aria-activedescendant={hasList && active >= 0 ? `${uid}-opt-${active}` : undefined} autoComplete="off" spellCheck={false}
            placeholder={t("searchPlaceholder")} value={query} disabled={busy} maxLength={60}
            onChange={(e) => search(e.target.value)} onKeyDown={onKeyDown}
            onFocus={() => { if (query === "" && favs.length > 0) search(""); else setFind((f) => ({ ...f, open: true })); }}
            onBlur={() => setFind((f) => ({ ...f, open: false, active: -1 }))} />
          <span className={rx.srOnly} role="status" aria-live="polite">{open ? status : ""}</span>
          {open && !hasList && status && <div className={rx.listbox} aria-hidden="true"><p className={rx.listNote}>{status}</p></div>}
          {hasList && (
            <ul id={listId} role="listbox" aria-label={t("suggestionsFor")} className={rx.listbox}>
              {options.map((it, n) => (
                <li key={it.code} id={`${uid}-opt-${n}`} role="option" aria-selected={n === active}
                  className={`${rx.option} ${n === active ? rx.optionActive : ""}`}
                  onMouseDown={(e) => { e.preventDefault(); pick(it); }} onMouseMove={() => setActive(() => n)}>
                  <span><span className={s.code}>{it.code}</span>{it.title}</span>
                  {it.favourite && <span className={rx.optionMeta}>★ {t("favourite")}</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {shown.length === 0 ? <p className={s.meta}>{t("none")}</p> : (
        <ul className={s.list} aria-label={t("codedDiagnoses")}>
          {shown.map((d) => {
            const starred = favs.some((f) => f.code === d.code);
            return (
              <li key={d.code} className={s.row}>
                <span className={s.title}><span className={s.code}>{d.code}</span>{d.title}</span>
                {d.primary && <StatusPill tone="info" label={t("primary")} />}
                {editable && !d.primary && (
                  <button type="button" className={s.act} disabled={busy} aria-label={t("makePrimaryOf", { code: d.code })} onClick={() => apply(setPrimary(shown, d.code))}>{t("makePrimary")}</button>
                )}
                {editable && (
                  <button type="button" className={s.act} disabled={busy} aria-pressed={starred} aria-label={t(starred ? "unstar" : "star", { code: d.code })} onClick={() => void toggleStar(d)}>{starred ? "★" : "☆"}</button>
                )}
                {editable && (
                  <button type="button" className={s.act} disabled={busy} aria-label={t("removeOf", { code: d.code })} onClick={() => apply(removeDx(shown, d.code))}>{t("remove")}</button>
                )}
                {editable
                  ? <input key={`${d.code}:${d.qualifier ?? ""}`} className={`${inputClassName} ${s.qualifier}`} defaultValue={d.qualifier ?? ""} maxLength={MAX_QUALIFIER} disabled={busy}
                      aria-label={t("qualifier", { code: d.code })} placeholder={t("qualifier", { code: d.code })} onBlur={(e) => apply(setQualifier(shown, d.code, e.target.value))} />
                  : d.qualifier && <span className={`${s.meta} ${s.qualifier}`}>{d.qualifier}</span>}
              </li>
            );
          })}
        </ul>
      )}

      {/* The note's diagnosis line: generated while codes exist, typed by hand when there are none. */}
      {saved.length > 0 || visit.locked
        ? <p className={s.meta}><strong>{t("summary")}:</strong>{" "}{value.trim() || "—"}</p>
        : (
          <label className={rx.label} htmlFor="diagnosis">
            {g("diagnosis.label")}
            <input id="diagnosis" className={inputClassName} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
            <span className={s.meta}>{t("freeTextHint")}</span>
          </label>
        )}

      {visit.locked && !amending && <p className={s.meta}>{t("signedHint")}</p>}
      {visit.locked && visit.canAmend && !amending && list.version !== null && (
        <div><button type="button" className={p.btn} onClick={() => { setDraft(saved); setMsg({}); }}>{t("amend")}</button></div>
      )}
      {amending && (
        <>
          <label className={rx.label} htmlFor={`${uid}-reason`}>{t("amendReason")}</label>
          <textarea id={`${uid}-reason`} className={inputClassName} value={reason} maxLength={500} aria-describedby={`${uid}-reason-hint`} onChange={(e) => setReason(e.target.value)} />
          <p id={`${uid}-reason-hint`} className={s.meta}>{t("amendReasonHint")}</p>
          <div className={s.actions}>
            <button type="button" className={p.btn} disabled={busy || !canSaveAmendment(saved, draft, reason)} onClick={() => void saveAmendment()}>{t("saveAmendment")}</button>
            <button type="button" className={p.btn} disabled={busy} onClick={() => { setDraft(null); setReason(""); setMsg({}); }}>{t("cancel")}</button>
          </div>
        </>
      )}

      {busy && <p className={s.meta} role="status">{t("saving")}</p>}
      {msg.notice && <p className={s.meta} role="status">{msg.notice}</p>}
      {msg.error && <p className={s.error} role="alert">{msg.error}</p>}
    </div>
  );
}
