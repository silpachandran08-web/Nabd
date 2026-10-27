"use client";

import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import Link from "next/link";
import { can } from "../../lib/permissions";
import Dialog from "../ui/Dialog";
import StatusPill, { type PillTone } from "../ui/StatusPill";
import { ErrorState, Skeleton } from "../ui/StateViews";
import type { AuthedFetch, Problem } from "./shared";
import s from "./consultParts.module.css";

type Tooth = { id: string; toothNumber: number; status: string; note: string | null; isSupernumerary: boolean };

const FDI_TEETH = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28,
  48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];
const STATUSES = ["healthy", "decayed", "filled", "missing", "crown", "root_canal"] as const;
const TONE: Record<string, PillTone> = { healthy: "ok", decayed: "danger", filled: "info", missing: "neutral", crown: "sync", root_canal: "warn" };

/** Dental chart in a drawer, opened from the Examine step with the active patient's context (DOC-051). */
export default function DentalDrawer({ authedFetch, patientId, patientName, onClose }: { authedFetch: AuthedFetch; patientId: string; patientName: string; onClose: () => void }) {
  const tx = useTranslations("consult.dentalDrawer");
  const [chart, setChart] = useState<Tooth[] | null>(null);
  const [error, setError] = useState(false);
  const [tick, setTick] = useState(0);
  const [busy, setBusy] = useState<number | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const canEdit = can("specialty_dental:edit");

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setError(false);
      setChart(null);
      try {
        const res = await authedFetch(`/specialty/dental/patients/${patientId}/chart`);
        if (cancelled) return;
        if (!res?.ok) { setError(true); return; }
        setChart(await res.json());
      } catch {
        if (!cancelled) setError(true);
      }
    });
    return () => { cancelled = true; };
  }, [authedFetch, patientId, tick]);

  async function setStatus(n: number, status: string) {
    setBusy(n);
    setSaveError(null);
    try {
      const res = await authedFetch(`/specialty/dental/patients/${patientId}/chart/${n}`, { method: "PATCH", body: JSON.stringify({ status }) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: tx("couldnTUpdateTheTooth") }));
        setSaveError(p.detail || tx("couldnTUpdateTheTooth"));
        return;
      }
      const updated: Tooth = await res.json();
      setChart((prev) => [...(prev ?? []).filter((t) => !(t.toothNumber === n && !t.isSupernumerary)), updated]);
    } catch {
      setSaveError(tx("couldnTReachTheServer"));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Dialog title={tx("chartTitle", { patientName })} onClose={onClose} size="drawer">
      {error ? <ErrorState message={tx("couldnTLoadTheDental")} onRetry={() => setTick((t) => t + 1)} /> : chart === null ? <Skeleton rows={4} label={tx("loadingDentalChart")} /> : (
        <div className={s.stack}>
          {!canEdit && <p className={s.muted}>{tx("theChartIsReadOnly")}</p>}
          {saveError && <div className={`${s.notice} ${s.noticeDanger}`} role="alert">{saveError}</div>}
          <div className={s.teeth}>
            {FDI_TEETH.map((n) => {
              const t = chart.find((x) => x.toothNumber === n && !x.isSupernumerary);
              const status = t?.status ?? "healthy";
              return (
                <div key={n} className={s.tooth}>
                  <strong>{n}</strong>
                  <StatusPill tone={TONE[status] ?? "neutral"} label={status.replace(/_/g, " ")} />
                  {canEdit && <select aria-label={tx("toothStatus", { n })} value={status} disabled={busy === n} onChange={(e) => setStatus(n, e.target.value)}>
                    {STATUSES.map((st) => <option key={st} value={st}>{st.replace(/_/g, " ")}</option>)}
                  </select>}
                </div>
              );
            })}
          </div>
          {chart.some((t) => t.isSupernumerary) && (
            <p className={s.muted}>{tx("supernumeraryTeethRecorded")}{" "}{chart.filter((t) => t.isSupernumerary).length}{tx("manageThemFromThePatient")}</p>
          )}
          <Link href="/patients" className={s.linkBtn}>{tx("openFullPatientRecordHistory")}</Link>
        </div>
      )}
    </Dialog>
  );
}
