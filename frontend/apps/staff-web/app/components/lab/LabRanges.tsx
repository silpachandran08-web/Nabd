"use client";

// PHL-042: one test's reference ranges and critical limits by sex and age band (prototype lab.catalog). lab:view reads them as a
// table; the grid and every control that changes them are absent without lab:validate (the pathologist's grant). Saving stores
// the whole set as a new version; the server refuses overlapping bands and the grid says so before the request.
import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import styles from "../../(clinic)/setup/setup.module.css";
import lab from "./lab.module.css";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, ApiError, errorText } from "../../lib/api";
import { can } from "../../lib/permissions";
import { formatClinicDate } from "../../lib/clinicTime";
import type { LabTest } from "../../lib/labCatalogue";
import {
  AGE_UNITS, SEXES, ageParts, bandOf, emptyRow, rangesProblem, rowsOf, serverProblem,
  type RangeBand, type RangeProblem, type RangeRow, type RangeSet,
} from "../../lib/labRanges";

const LIMITS = ["low", "high", "criticalLow", "criticalHigh"] as const;

export default function LabRanges({ test, onClose }: { test: Pick<LabTest, "id" | "name" | "unit">; onClose: () => void }) {
  const t = useTranslations("shell.lab");
  const canEdit = can("lab:validate");
  const [set, setSet] = useState<RangeSet | null>(null);
  const [rows, setRows] = useState<RangeRow[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const s = await api.get<RangeSet>(`/lab/tests/${test.id}/ranges`);
      setSet(s); setRows(rowsOf(s));
    } catch (e) { setLoadError(errorText(e, t("ranges.loadFailed"))); }
  }, [t, test.id]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const say = (p: RangeProblem) => t(`ranges.errors.${p.error}`, { row: p.row, other: p.other ?? 0 });
  const edit = (i: number, k: keyof RangeRow, v: string) => { setError(null); setNotice(null); setRows((rs) => rs.map((r, n) => (n === i ? { ...r, [k]: v } : r))); };
  const ageText = (days: number) => { const p = ageParts(days); return t(`ranges.age.${p.unit}`, { count: p.n }); };
  const ageBand = (b: RangeBand) => (b.ageMaxDays == null ? t("ranges.ageFrom", { from: ageText(b.ageMinDays) }) : t("ranges.ageBand", { from: ageText(b.ageMinDays), to: ageText(b.ageMaxDays) }));

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    const bad = rangesProblem(rows);
    if (bad) { setError(say(bad)); return; }
    setSaving(true); setError(null); setNotice(null);
    try {
      const next = await api.put<RangeSet>(`/lab/tests/${test.id}/ranges`, { ranges: rows.map(bandOf) });
      setSet(next); setRows(rowsOf(next)); setNotice(t("ranges.saved", { version: next.version }));
    } catch (err) {
      const p = err instanceof ApiError ? serverProblem(err.status, err.problem) : null;
      setError(p ? say(p) : errorText(err, t("ranges.saveFailed")));
    } finally { setSaving(false); }
  }

  const ageInput = (i: number, side: "from" | "to") => {
    const n = side === "from" ? "fromN" : "toN", unit = side === "from" ? "fromUnit" : "toUnit";
    return (
      <span className={lab.cell}>
        <label htmlFor={`lr-${side}-${i}`}>{t(`ranges.${side}`)}</label>
        <span className={lab.pair}>
          <input id={`lr-${side}-${i}`} className={`${styles.input} ${lab.target} ${lab.narrow}`} inputMode="numeric" dir="ltr" maxLength={5} value={rows[i][n]} onChange={(e) => edit(i, n, e.target.value)} />
          <select aria-label={t(`ranges.${side}Unit`)} className={`${styles.select} ${lab.target}`} value={rows[i][unit]} onChange={(e) => edit(i, unit, e.target.value)}>
            {AGE_UNITS.map((u) => <option key={u} value={u}>{t(`ranges.ageUnit.${u}`)}</option>)}
          </select>
        </span>
      </span>
    );
  };

  return (
    <Dialog title={t("ranges.title", { name: test.name })} onClose={onClose} size="lg">
      {loadError ? <ErrorState message={loadError} onRetry={load} />
        : !set ? <Skeleton rows={4} label={t("ranges.loading")} />
        : (
          <>
            <p className={lab.sub}>{test.unit ? t("ranges.unit", { unit: test.unit }) : t("ranges.noUnit")}</p>
            {set.version > 0 && set.effectiveFrom && <p className={lab.sub}>{t("ranges.version", { version: set.version, date: formatClinicDate(set.effectiveFrom) })}</p>}
            {!canEdit ? (
              set.ranges.length === 0 ? <EmptyState icon="list" title={t("ranges.empty")} hint={t("ranges.emptyHint")} /> : (
                <div className={styles.tableWrap}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        <th scope="col">{t("ranges.sex")}</th>
                        <th scope="col">{t("ranges.ageCol")}</th>
                        {LIMITS.map((k) => <th key={k} scope="col" className={lab.num}>{t(`ranges.${k}`)}</th>)}
                        <th scope="col">{t("ranges.textCol")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {set.ranges.map((b, i) => (
                        <tr key={i}>
                          <td>{t(`ranges.sexOption.${b.sex}`)}</td>
                          <td>{ageBand(b)}</td>
                          {LIMITS.map((k) => <td key={k} className={lab.num} dir="ltr">{b[k] ?? ""}</td>)}
                          <td>{b.text ?? ""}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )
            ) : (
              <form onSubmit={save} noValidate>
                {set.version === 0 && <p className={lab.sub}>{t("ranges.empty")} {t("ranges.emptyHint")}</p>}
                <p className={lab.sub}>{t("ranges.ageHint")}</p>
                {rows.map((r, i) => (
                  <fieldset key={i} className={lab.band}>
                    <legend className={styles.label}>{t("ranges.band", { row: i + 1 })}</legend>
                    <div className={lab.bar}>
                      <span className={lab.cell}>
                        <label htmlFor={`lr-sex-${i}`}>{t("ranges.sex")}</label>
                        <select id={`lr-sex-${i}`} className={`${styles.select} ${lab.target}`} value={r.sex} onChange={(e) => edit(i, "sex", e.target.value)}>
                          {SEXES.map((s) => <option key={s} value={s}>{t(`ranges.sexOption.${s}`)}</option>)}
                        </select>
                      </span>
                      {ageInput(i, "from")}
                      {ageInput(i, "to")}
                    </div>
                    <div className={lab.bar}>
                      {LIMITS.map((k) => (
                        <span key={k} className={lab.cell}>
                          <label htmlFor={`lr-${k}-${i}`}>{t(`ranges.${k}`)}</label>
                          <input id={`lr-${k}-${i}`} className={`${styles.input} ${lab.target} ${lab.narrow}`} inputMode="decimal" dir="ltr" maxLength={17} value={r[k]} onChange={(e) => edit(i, k, e.target.value)} />
                        </span>
                      ))}
                    </div>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor={`lr-text-${i}`}>{t("ranges.text")}</label>
                      <input id={`lr-text-${i}`} className={`${styles.input} ${lab.target}`} maxLength={200} value={r.text} onChange={(e) => edit(i, "text", e.target.value)} />
                    </div>
                    {rows.length > 1 && <button type="button" className={`${styles.btn} ${lab.target}`} aria-label={t("ranges.removeOf", { row: i + 1 })} onClick={() => { setError(null); setNotice(null); setRows((rs) => rs.filter((_, n) => n !== i)); }}>{t("ranges.remove")}</button>}
                  </fieldset>
                ))}
                <p className={lab.sub}>{t("ranges.versionHint")}</p>
                {notice && <div className={lab.notice} role="status">{notice}</div>}
                {error && <div className={`${lab.notice} ${lab.noticeError}`} role="alert">{error}</div>}
                <div className={lab.bar}>
                  <button type="button" className={`${styles.btn} ${lab.target}`} onClick={() => { setError(null); setNotice(null); setRows((rs) => [...rs, emptyRow()]); }}>{t("ranges.add")}</button>
                  <button type="submit" className={`${styles.btnPrimary} ${lab.target}`} disabled={saving}>{saving ? t("saving") : t("ranges.save")}</button>
                </div>
              </form>
            )}
          </>
        )}
    </Dialog>
  );
}
