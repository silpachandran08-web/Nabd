"use client";

// PHL-041: the lab test and panel catalogue (prototype lab.catalog). lab:view reads; every control that changes the catalogue
// (add, edit, import, add panel) is absent without lab:catalogue. Prices are the linked price-list head's, shown read-only here
// for a viewer. The page gate (PlannedScreen) has already checked lab:view and the clinic's lab module.
import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { formatNumber } from "@nabd/i18n";
import styles from "../../(clinic)/setup/setup.module.css";
import lab from "./lab.module.css";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, ApiError, errorText } from "../../lib/api";
import { can } from "../../lib/permissions";
import {
  CONTAINERS, SAMPLE_TYPES, STARTER_PACK, TABS, emptyForm, formError, formOf, needsPrice, panelError, panelMaths, plain, tatParts, testBody,
  type CatalogueTab, type ImportResult, type LabPanel, type LabTest, type LabTestPage, type TestForm,
} from "../../lib/labCatalogue";

type T = ReturnType<typeof useTranslations>;
const FIX2 = { min: 2, max: 2 };
const money = (v: number) => formatNumber(Number(v), FIX2);
/** A catalogue label, or the value in plain words when the message catalogue has no entry for it. */
const label = (t: T, group: "sample" | "container", v: string) => (t.has(`${group}.${v}`) ? t(`${group}.${v}`) : plain(v));

export default function LabCatalogue() {
  const t = useTranslations("shell.lab");
  const canEdit = can("lab:catalogue");
  const [tab, setTab] = useState<CatalogueTab>("tests");
  return (
    <main className={styles.page}>
      <h1 className={styles.title}>{t("title")}</h1>
      <p className={styles.subtitle}>{t("subtitle")}</p>
      <div className={lab.tabs} role="tablist" aria-label={t("tabsLabel")}>
        {TABS.map((id) => (
          <button key={id} type="button" role="tab" aria-selected={tab === id} className={tab === id ? lab.tabOn : lab.tab} onClick={() => setTab(id)}>{t(`tab.${id}`)}</button>
        ))}
      </div>
      {tab === "tests" ? <TestsTab canEdit={canEdit} /> : <PanelsTab canEdit={canEdit} />}
    </main>
  );
}

function TestsTab({ canEdit }: { canEdit: boolean }) {
  const t = useTranslations("shell.lab");
  const [term, setTerm] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState<LabTestPage | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [editing, setEditing] = useState<LabTest | "new" | null>(null);
  const [importing, setImporting] = useState(false);

  const path = (q: string, cursor?: string) => `/lab/tests?limit=50${q ? `&q=${encodeURIComponent(q)}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
  const load = useCallback(async (q: string) => {
    setLoadError(null);
    try { setPage(await api.get<LabTestPage>(path(q))); } catch (e) { setLoadError(errorText(e, t("loadFailed"))); }
  }, [t]);
  useEffect(() => { void Promise.resolve().then(() => load(query)); }, [load, query]);

  const more = async () => {
    if (!page?.nextCursor || busy) return;
    setBusy(true); setProblem(null);
    try {
      const next = await api.get<LabTestPage>(path(query, page.nextCursor));
      setPage({ items: [...page.items, ...next.items], nextCursor: next.nextCursor });
    } catch (e) { setProblem(errorText(e, t("loadFailed"))); } finally { setBusy(false); }
  };
  const saved = (text: string) => { setEditing(null); setImporting(false); setNotice(text); void load(query); };
  const items = page?.items ?? [];

  return (
    <section aria-labelledby="lab-tests-title">
      <h2 id="lab-tests-title" className={styles.cardTitle}>{t("tests.title")}</h2>
      <form className={lab.bar} role="search" onSubmit={(e) => { e.preventDefault(); setPage(null); setNotice(null); setQuery(term.trim()); }}>
        <label className={styles.label} htmlFor="lab-q">{t("tests.search")}</label>
        <input id="lab-q" className={`${styles.input} ${lab.target}`} value={term} maxLength={80} onChange={(e) => setTerm(e.target.value)} placeholder={t("tests.searchHint")} />
        <button type="submit" className={`${styles.btn} ${lab.target}`}>{t("tests.searchGo")}</button>
        {canEdit && <button type="button" className={`${styles.btnPrimary} ${lab.target}`} onClick={() => { setNotice(null); setEditing("new"); }}>{t("tests.add")}</button>}
        {canEdit && <button type="button" className={`${styles.btn} ${lab.target}`} onClick={() => { setNotice(null); setImporting(true); }}>{t("import.open")}</button>}
      </form>
      {notice && <div className={lab.notice} role="status">{notice}</div>}
      {problem && <div className={`${lab.notice} ${lab.noticeError}`} role="alert">{problem}</div>}
      {loadError ? <ErrorState message={loadError} onRetry={() => load(query)} />
        : !page ? <Skeleton rows={6} label={t("loading")} />
        : items.length === 0 ? <EmptyState icon="list" title={query ? t("tests.noMatch") : t("tests.empty")} hint={query ? undefined : canEdit ? t("tests.emptyHintEdit") : t("tests.emptyHint")} />
        : (
          <>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th scope="col">{t("col.code")}</th>
                    <th scope="col">{t("col.name")}</th>
                    <th scope="col">{t("col.loinc")}</th>
                    <th scope="col">{t("col.sample")}</th>
                    <th scope="col">{t("col.container")}</th>
                    <th scope="col">{t("col.unit")}</th>
                    <th scope="col">{t("col.tat")}</th>
                    <th scope="col" className={lab.num}>{t("col.price")}</th>
                    <th scope="col">{t("col.status")}</th>
                    {canEdit && <th scope="col"><span className="srOnly">{t("col.actions")}</span></th>}
                  </tr>
                </thead>
                <tbody>
                  {items.map((x) => {
                    const tat = tatParts(x.tatMinutes);
                    return (
                      <tr key={x.id}>
                        <td dir="ltr">{x.code}</td>
                        <td>{x.name}{x.method ? <div className={lab.sub}>{x.method}</div> : null}</td>
                        <td dir="ltr">{x.loinc ?? ""}</td>
                        <td>{label(t, "sample", x.sampleType)}{x.volumeMl != null ? <div className={lab.sub}>{t("volume", { ml: formatNumber(Number(x.volumeMl)) })}</div> : null}</td>
                        <td>{label(t, "container", x.container)}</td>
                        <td dir="ltr">{x.unit ?? ""}</td>
                        <td>{t(`tat.${tat.unit}`, { count: tat.value })}</td>
                        <td className={lab.num}>{needsPrice(x) ? t("tests.noPrice") : money(x.price)}</td>
                        <td>{needsPrice(x) ? t("status.needsPrice") : x.active ? t("status.active") : t("status.inactive")}</td>
                        {canEdit && <td><button type="button" className={`${styles.btn} ${lab.target}`} aria-label={t("tests.editOf", { name: x.name })} onClick={() => { setNotice(null); setEditing(x); }}>{t("tests.edit")}</button></td>}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {page.nextCursor && <button type="button" className={`${styles.btn} ${lab.target}`} disabled={busy} onClick={more}>{t("more")}</button>}
          </>
        )}
      {editing && <TestDrawer test={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={(name) => saved(t("tests.saved", { name }))} />}
      {importing && <ImportDialog onClose={() => setImporting(false)} onDone={(n) => saved(t("import.done", { count: n }))} />}
    </section>
  );
}

function TestDrawer({ test, onClose, onSaved }: { test: LabTest | null; onClose: () => void; onSaved: (name: string) => void }) {
  const t = useTranslations("shell.lab");
  const [f, setF] = useState<TestForm>(() => (test ? formOf(test) : emptyForm()));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const set = (k: keyof TestForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => { setError(null); setF((p) => ({ ...p, [k]: e.target.value })); };
  // A value the starter lists do not know (typed through the API) stays selectable.
  const options = (known: readonly string[], current: string) => (known.includes(current) ? known : [current, ...known]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    const bad = formError(f, !test);
    if (bad) { setError(t(`errors.${bad}`)); return; }
    const body = testBody(f, test ?? undefined);
    if (test && Object.keys(body).length === 0) { onClose(); return; }
    setSaving(true);
    try {
      if (test) await api.patch(`/lab/tests/${test.id}`, body); else await api.post("/lab/tests", body);
      onSaved(f.name.trim());
    } catch (err) {
      setError(err instanceof ApiError && err.status === 409 ? t("errors.duplicate") : errorText(err, t("errors.saveFailed")));
      setSaving(false);
    }
  }

  const field = (k: keyof TestForm, id: string, text: string, extra?: React.InputHTMLAttributes<HTMLInputElement>) => (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={id}>{text}</label>
      <input id={id} className={`${styles.input} ${lab.target}`} value={f[k] as string} onChange={set(k)} {...extra} />
    </div>
  );
  return (
    <Dialog title={test ? t("drawer.editTitle", { name: test.name }) : t("drawer.addTitle")} onClose={onClose} size="drawer">
      <form onSubmit={submit} noValidate>
        {test ? <p className={lab.sub} dir="ltr">{test.code} · {test.chargeCode}</p> : field("code", "lt-code", t("col.code"), { maxLength: 24, dir: "ltr", autoFocus: true })}
        {field("name", "lt-name", t("col.name"), { maxLength: 120 })}
        {field("loinc", "lt-loinc", t("drawer.loinc"), { maxLength: 9, dir: "ltr", inputMode: "numeric" })}
        <div className={styles.field}>
          <label className={styles.label} htmlFor="lt-sample">{t("col.sample")}</label>
          <select id="lt-sample" className={`${styles.select} ${lab.target}`} value={f.sampleType} onChange={set("sampleType")}>
            {options(SAMPLE_TYPES, f.sampleType).map((v) => <option key={v} value={v}>{label(t, "sample", v)}</option>)}
          </select>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="lt-container">{t("col.container")}</label>
          <select id="lt-container" className={`${styles.select} ${lab.target}`} value={f.container} onChange={set("container")}>
            {options(CONTAINERS, f.container).map((v) => <option key={v} value={v}>{label(t, "container", v)}</option>)}
          </select>
        </div>
        {field("volumeMl", "lt-volume", t("drawer.volume"), { inputMode: "decimal", dir: "ltr" })}
        {field("method", "lt-method", t("drawer.method"), { maxLength: 80 })}
        {field("unit", "lt-unit", t("col.unit"), { maxLength: 40, dir: "ltr" })}
        {field("tatMinutes", "lt-tat", t("drawer.tat"), { inputMode: "numeric", dir: "ltr" })}
        {field("price", "lt-price", t("drawer.price"), { inputMode: "decimal", dir: "ltr" })}
        {field("taxCode", "lt-tax", t("drawer.taxCode"), { maxLength: 20, dir: "ltr" })}
        {test && (
          <div className={styles.field}>
            <label className={lab.target}>
              <input type="checkbox" checked={f.active} onChange={(e) => { setError(null); setF((p) => ({ ...p, active: e.target.checked })); }} /> {t("drawer.active")}
            </label>
            {needsPrice(test) && <p className={lab.sub}>{t("drawer.activateHint")}</p>}
          </div>
        )}
        <p className={lab.sub}>{t("drawer.priceHint")}</p>
        {error && <div className={`${lab.notice} ${lab.noticeError}`} role="alert">{error}</div>}
        <button type="submit" className={`${styles.btnPrimary} ${lab.target}`} disabled={saving}>{saving ? t("saving") : t("save")}</button>
      </form>
    </Dialog>
  );
}

/** Dry run first: the diff is shown before anything is created; the import itself needs a second, explicit press. */
export function ImportDialog({ onClose, onDone }: { onClose: () => void; onDone: (created: number) => void }) {
  const t = useTranslations("shell.lab");
  const [diff, setDiff] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const run = useCallback(async () => {
    setError(null);
    try { setDiff(await api.post<ImportResult>("/lab/catalogue/imports", { pack: STARTER_PACK, dryRun: true })); } catch (e) { setError(errorText(e, t("import.failed"))); }
  }, [t]);
  useEffect(() => { void Promise.resolve().then(run); }, [run]);

  const confirm = async () => {
    if (saving) return;
    setSaving(true); setError(null);
    try {
      const done = await api.post<ImportResult>("/lab/catalogue/imports", { pack: STARTER_PACK, dryRun: false });
      onDone(done.created);
    } catch (e) { setError(errorText(e, t("import.failed"))); setSaving(false); }
  };
  return (
    <Dialog title={t("import.title")} onClose={onClose}>
      {error && !diff ? <ErrorState message={error} onRetry={run} />
        : !diff ? <Skeleton rows={3} label={t("import.checking")} />
        : (
          <>
            <p>{t("import.willAdd", { count: diff.toCreate.length })}</p>
            {diff.skipped.length > 0 && <p className={lab.sub}>{t("import.skipped", { count: diff.skipped.length })}</p>}
            {!diff.reviewed && <p className={`${lab.notice} ${lab.noticeError}`} role="note">{t("import.unreviewed")}</p>}
            <p className={lab.sub}>{t("import.noPrices")}</p>
            {diff.toCreate.length > 0 && (
              <details>
                <summary className={lab.target}>{t("import.showCodes")}</summary>
                <p dir="ltr">{diff.toCreate.join(", ")}</p>
              </details>
            )}
            {error && <div className={`${lab.notice} ${lab.noticeError}`} role="alert">{error}</div>}
            {diff.toCreate.length > 0
              ? <button type="button" className={`${styles.btnPrimary} ${lab.target}`} disabled={saving} onClick={confirm}>{saving ? t("import.running") : t("import.confirm", { count: diff.toCreate.length })}</button>
              : <button type="button" className={`${styles.btn} ${lab.target}`} onClick={onClose}>{t("close")}</button>}
          </>
        )}
    </Dialog>
  );
}

function PanelsTab({ canEdit }: { canEdit: boolean }) {
  const t = useTranslations("shell.lab");
  const [panels, setPanels] = useState<LabPanel[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const load = useCallback(async () => {
    setLoadError(null);
    try { setPanels((await api.get<{ items: LabPanel[] }>("/lab/panels")).items); } catch (e) { setLoadError(errorText(e, t("loadFailed"))); }
  }, [t]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  return (
    <section aria-labelledby="lab-panels-title">
      <h2 id="lab-panels-title" className={styles.cardTitle}>{t("panels.title")}</h2>
      <p className={styles.subtitle}>{t("panels.subtitle")}</p>
      {canEdit && <div className={lab.bar}><button type="button" className={`${styles.btnPrimary} ${lab.target}`} onClick={() => { setNotice(null); setAdding(true); }}>{t("panels.add")}</button></div>}
      {notice && <div className={lab.notice} role="status">{notice}</div>}
      {loadError ? <ErrorState message={loadError} onRetry={load} />
        : !panels ? <Skeleton rows={4} label={t("loading")} />
        : panels.length === 0 ? <EmptyState icon="list" title={t("panels.empty")} hint={canEdit ? t("panels.emptyHintEdit") : undefined} />
        : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">{t("col.code")}</th>
                  <th scope="col">{t("col.name")}</th>
                  <th scope="col">{t("panels.tests")}</th>
                  <th scope="col" className={lab.num}>{t("panels.separately")}</th>
                  <th scope="col" className={lab.num}>{t("panels.price")}</th>
                  <th scope="col" className={lab.num}>{t("panels.saving")}</th>
                </tr>
              </thead>
              <tbody>
                {panels.map((p) => (
                  <tr key={p.id}>
                    <td dir="ltr">{p.code}</td>
                    <td>{p.name}</td>
                    <td>{t("panels.count", { count: p.tests.length })}<div className={lab.sub} dir="ltr">{p.tests.map((x) => x.code).join(", ")}</div></td>
                    <td className={lab.num}>{money(p.testsTotal)}</td>
                    <td className={lab.num}>{money(p.price)}</td>
                    <td className={lab.num}>{Number(p.discount) > 0 ? money(p.discount) : ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      {adding && <PanelDialog onClose={() => setAdding(false)} onSaved={(name) => { setAdding(false); setNotice(t("panels.saved", { name })); void load(); }} />}
    </section>
  );
}

function PanelDialog({ onClose, onSaved }: { onClose: () => void; onSaved: (name: string) => void }) {
  const t = useTranslations("shell.lab");
  const [tests, setTests] = useState<LabTest[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [price, setPrice] = useState("");
  const [picked, setPicked] = useState<string[]>([]);
  const [filter, setFilter] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const all: LabTest[] = [];
      let cursor: string | null | undefined;
      do { // a panel holds active tests only; the catalogue is a few hundred rows at most
        const page: LabTestPage = await api.get<LabTestPage>(`/lab/tests?limit=200${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
        all.push(...page.items.filter((x) => x.active));
        cursor = page.nextCursor;
      } while (cursor);
      setTests(all);
    } catch (e) { setLoadError(errorText(e, t("loadFailed"))); }
  }, [t]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const toggle = (id: string) => { setError(null); setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id])); };
  const maths = panelMaths(tests ?? [], picked, price);
  const shown = (tests ?? []).filter((x) => !filter.trim() || `${x.code} ${x.name}`.toLowerCase().includes(filter.trim().toLowerCase()));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    const bad = panelError(code, name, picked, price);
    if (bad) { setError(t(`panelErrors.${bad}`)); return; }
    setSaving(true);
    try {
      await api.post("/lab/panels", { code: code.trim().toUpperCase(), name: name.trim(), testIds: picked, price: Number(price) });
      onSaved(name.trim());
    } catch (err) {
      setError(err instanceof ApiError && err.status === 409 ? t("errors.duplicate") : errorText(err, t("errors.saveFailed")));
      setSaving(false);
    }
  }

  return (
    <Dialog title={t("panels.addTitle")} onClose={onClose} size="lg">
      {loadError ? <ErrorState message={loadError} onRetry={load} />
        : !tests ? <Skeleton rows={4} label={t("loading")} />
        : tests.length === 0 ? <EmptyState icon="list" title={t("panels.noTests")} />
        : (
          <form onSubmit={submit} noValidate>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="lp-code">{t("col.code")}</label>
              <input id="lp-code" className={`${styles.input} ${lab.target}`} value={code} maxLength={24} dir="ltr" onChange={(e) => { setError(null); setCode(e.target.value); }} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="lp-name">{t("col.name")}</label>
              <input id="lp-name" className={`${styles.input} ${lab.target}`} value={name} maxLength={120} onChange={(e) => { setError(null); setName(e.target.value); }} />
            </div>
            <fieldset className={styles.field}>
              <legend className={styles.label}>{t("panels.pick", { count: picked.length })}</legend>
              <label className="srOnly" htmlFor="lp-filter">{t("panels.filter")}</label>
              <input id="lp-filter" className={`${styles.input} ${lab.target}`} value={filter} placeholder={t("panels.filter")} onChange={(e) => setFilter(e.target.value)} />
              {shown.map((x) => (
                <label key={x.id} className={lab.bar}>
                  <input className={lab.target} type="checkbox" checked={picked.includes(x.id)} onChange={() => toggle(x.id)} />
                  <span>{x.name} <span className={lab.sub} dir="ltr">{x.code} · {money(x.price)}</span></span>
                </label>
              ))}
            </fieldset>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="lp-price">{t("panels.price")}</label>
              <input id="lp-price" className={`${styles.input} ${lab.target}`} value={price} inputMode="decimal" dir="ltr" onChange={(e) => { setError(null); setPrice(e.target.value); }} />
            </div>
            <p role="status">{t("panels.maths", { total: money(maths.total), saving: money(maths.saving) })}</p>
            {error && <div className={`${lab.notice} ${lab.noticeError}`} role="alert">{error}</div>}
            <button type="submit" className={`${styles.btnPrimary} ${lab.target}`} disabled={saving}>{saving ? t("saving") : t("save")}</button>
          </form>
        )}
    </Dialog>
  );
}
