"use client";
// PLT-054: the DPO's regulatory-watch log (each change with its market, impact and linked tracker task or decision; the
// target is five working days from noticing it), the quarterly summary download for the founder, and the Nabd team's
// training state. All behind audit_compliance:view (super_admin, compliance_dpo): the path sits under the Audit nav item,
// so the console's route gate already keeps other roles out, and the server refuses them (RoleDenied on a 403).
import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import styles from "../audit.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../../../components/ui/StateViews";
import RoleDenied from "../../../../components/platform/RoleDenied";
import { asTrainingRoster } from "../../../../shell/training";
import { isForbidden, platformApi, platformError } from "../../platformApi";
import { PLATFORM_QK, ROLE_LABELS } from "../../surfaces";

type Entry = {
  id: string; source: string; market: string; title: string; impactLevel: string; impact: string; linkedRef: string;
  noticedOn: string; recordedAt: string; recordedBy: string; workingDays: number; withinSla: boolean;
};
type WatchLog = { sources: string[]; markets: string[]; impactLevels: string[]; slaWorkingDays: number; entries: Entry[] };
const BLANK = { source: "", market: "", title: "", impactLevel: "", impact: "", linkedRef: "", noticedOn: "" };
const day = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString("en-GB") : "");
const STATE_TEXT: Record<string, string> = { current: "Completed", due: "Due", overdue: "Overdue" };

export default function CompliancePage() {
  const now = new Date();
  const log = useQuery({ queryKey: [...PLATFORM_QK, "regulatory-watch"], queryFn: ({ signal }) => platformApi.get<WatchLog>("/platform/regulatory-watch", { signal }) });
  const team = useQuery({
    queryKey: [...PLATFORM_QK, "training", "roster"],
    queryFn: async ({ signal }) => {
      const r = asTrainingRoster(await platformApi.get<unknown>("/platform/training/completions", { signal }));
      if (!r) throw new Error("unexpected training roster");
      return r;
    },
  });
  const [form, setForm] = useState(BLANK);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [year, setYear] = useState(now.getUTCFullYear());
  const [quarter, setQuarter] = useState(Math.floor(now.getUTCMonth() / 3) + 1);
  const [exportError, setExportError] = useState<string | null>(null);
  const set = (k: keyof typeof BLANK) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function save(e: FormEvent) {
    e.preventDefault();
    setSaving(true); setFormError(null);
    try {
      await platformApi.post("/platform/regulatory-watch", { ...form, linkedRef: form.linkedRef.trim().toUpperCase() });
      setForm(BLANK);
      void log.refetch();
    } catch (err) { setFormError(platformError(err, "Couldn't save the entry. Check the fields and try again.")); } finally { setSaving(false); }
  }

  async function download() {
    setExportError(null);
    let text: string;
    try { text = await platformApi.get<string>(`/platform/regulatory-watch/summary?year=${year}&quarter=${quarter}`, { as: "text" }); }
    catch (err) { setExportError(platformError(err, "Couldn't prepare the summary. Try again.")); return; }
    const url = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url; a.download = `regulatory-watch-${year}-Q${quarter}.md`; a.click();
    URL.revokeObjectURL(url);
  }

  if (isForbidden(log.error) || isForbidden(team.error)) {
    return <main className={styles.page}><RoleDenied surface="Regulatory watch" /></main>;
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Regulatory watch &amp; training</h1>
          <p className={styles.subtitle}>
            Monthly review of DPDP Rules, NCNICC, ZATCA, TRAI DLT, ABDM, NABIDH and Meta policy. Log each change within {log.data?.slaWorkingDays ?? 5} working days, with the tracker task or decision that follows it up.
          </p>
        </div>
        {log.data && <span className={styles.count}>{log.data.entries.length} logged</span>}
      </div>

      {log.data && (
        <form className={styles.filterCard} onSubmit={save} aria-label="Log a regulatory change">
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-source">Source</label>
            <select id="rw-source" className={styles.select} required value={form.source} onChange={set("source")}>
              <option value="">Choose</option>
              {log.data.sources.map((s) => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-market">Market</label>
            <select id="rw-market" className={styles.select} required value={form.market} onChange={set("market")}>
              <option value="">Choose</option>
              {log.data.markets.map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-level">Impact level</label>
            <select id="rw-level" className={styles.select} required value={form.impactLevel} onChange={set("impactLevel")}>
              <option value="">Choose</option>
              {log.data.impactLevels.map((l) => <option key={l} value={l}>{l}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-noticed">Noticed on</label>
            <input id="rw-noticed" type="date" className={styles.input} required value={form.noticedOn} onChange={set("noticedOn")} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-ref">Tracker task or decision</label>
            <input id="rw-ref" className={styles.input} required pattern="\s*([Tt]|[Dd][Ee][Cc])-[0-9]{1,6}\s*" placeholder="T-330 or DEC-12" value={form.linkedRef} onChange={set("linkedRef")} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-title">What changed</label>
            <input id="rw-title" className={styles.input} required minLength={3} maxLength={200} value={form.title} onChange={set("title")} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-impact">What it means for Nabd</label>
            <input id="rw-impact" className={styles.input} required minLength={3} maxLength={2000} value={form.impact} onChange={set("impact")} />
          </div>
          <button type="submit" className={styles.submit} disabled={saving}>{saving ? "Saving" : "Log the change"}</button>
          {formError && <p className={styles.filterError} role="alert">{formError}</p>}
        </form>
      )}

      <div className={styles.card}>
        {log.isPending ? <Skeleton rows={5} label="Loading the regulatory watch log" />
          : log.isError ? <ErrorState message={platformError(log.error, "Couldn't load the log. Try again.")} onRetry={() => void log.refetch()} />
          : log.data.entries.length === 0 ? <EmptyState icon="scroll" title="Nothing logged yet" hint="Log the first change from this month's review above." />
          : (
            <div className={styles.tableWrap}>
              <table className={styles.table} aria-label="Regulatory watch log">
                <thead>
                  <tr><th scope="col">Noticed</th><th scope="col">Recorded</th><th scope="col">Source</th><th scope="col">Market</th><th scope="col">Impact</th><th scope="col">Change</th><th scope="col">Linked</th></tr>
                </thead>
                <tbody>
                  {log.data.entries.map((e) => (
                    <tr key={e.id} data-late={!e.withinSla}>
                      <td className={styles.when}>{day(e.noticedOn)}</td>
                      <td>{day(e.recordedAt)} <span className={styles.muted}>({e.workingDays} working day{e.workingDays === 1 ? "" : "s"}{e.withinSla ? "" : ", late"}; {e.recordedBy})</span></td>
                      <td>{e.source.replace("_", " ")}</td>
                      <td>{e.market}</td>
                      <td>{e.impactLevel}</td>
                      <td>{e.title}<br /><span className={styles.muted}>{e.impact}</span></td>
                      <td>{e.linkedRef}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </div>

      {log.data && (
        <div className={styles.filterCard}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-year">Year</label>
            <input id="rw-year" type="number" className={styles.input} min={2020} max={2100} value={year} onChange={(e) => setYear(Number(e.target.value))} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="rw-quarter">Quarter</label>
            <select id="rw-quarter" className={styles.select} value={quarter} onChange={(e) => setQuarter(Number(e.target.value))}>
              {[1, 2, 3, 4].map((n) => <option key={n} value={n}>Q{n}</option>)}
            </select>
          </div>
          <button type="button" className={styles.secondaryBtn} onClick={() => void download()}>Download the quarterly summary</button>
          <p className={styles.exportHint}>For the founder. The download is recorded in the audit log; sending it is the DPO&apos;s step (docs/compliance/regulatory-watch.md).</p>
          {exportError && <p className={styles.filterError} role="alert">{exportError}</p>}
        </div>
      )}

      <div className={styles.header}>
        <div>
          <h2 className={styles.title}>Nabd team: data safety training</h2>
          <p className={styles.subtitle}>Due in an operator&apos;s first week, then every year. Each operator takes it at /platform/training.</p>
        </div>
        {team.data && <span className={styles.count}>{team.data.current} of {team.data.total} completed</span>}
      </div>
      <div className={styles.card}>
        {team.isPending ? <Skeleton rows={4} label="Loading the team's training" />
          : team.isError ? <ErrorState message={platformError(team.error, "Couldn't load the team's training. Try again.")} onRetry={() => void team.refetch()} />
          : team.data.entries.length === 0 ? <EmptyState icon="shield" title="No active operators" />
          : (
            <div className={styles.tableWrap}>
              <table className={styles.table} aria-label="Nabd team training">
                <thead><tr><th scope="col">Operator</th><th scope="col">Role</th><th scope="col">Training</th><th scope="col">Last completed</th><th scope="col">Due by</th></tr></thead>
                <tbody>
                  {team.data.entries.map((o) => (
                    <tr key={o.id} data-state={o.state}>
                      <td>{o.name}</td>
                      <td>{ROLE_LABELS[o.role] ?? o.role}</td>
                      <td>{STATE_TEXT[o.state]}</td>
                      <td>{day(o.completedAt) || <span className={styles.muted}>Never</span>}</td>
                      <td>{day(o.dueAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </div>
    </main>
  );
}
