"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import styles from "../../(clinic)/setup/setup.module.css";
import { api } from "../../lib/api";
import { requestStepUpToken } from "../../lib/stepUp";
import { DATASETS, FORMATS, STEP_UP_TARGET, finished, size, startBody, startError, type ExportFormat, type ExportJob } from "../../lib/dataExport";

/**
 * ADM-015 (owner.setup export drawer): choose datasets and a format, confirm with the authenticator, then follow per-dataset
 * progress. The export runs on the server, so the owner can close this and keep working; the link arrives by WhatsApp and email.
 * Rendered only for setup:export (the caller checks can()).
 */
export default function ExportDrawer({ onClose, onStarted }: { onClose: () => void; onStarted: () => void }) {
  const [picked, setPicked] = useState<string[]>(DATASETS.map((d) => d.key));
  const [format, setFormat] = useState<ExportFormat>("xlsx");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [estimate, setEstimate] = useState<number | null>(null);
  const [job, setJob] = useState<ExportJob | null>(null);
  const [pollFailed, setPollFailed] = useState(false);

  const poll = useCallback(async (id: string) => {
    try { setJob(await api.get<ExportJob>(`/setup/export-jobs/${id}`)); setPollFailed(false); } catch { setPollFailed(true); }
  }, []);

  useEffect(() => {
    if (!jobId || finished(job?.status)) return;
    const t = setInterval(() => void poll(jobId), 3000);
    return () => clearInterval(t);
  }, [jobId, job?.status, poll]);

  async function start(e: React.FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true); setErr(null);
    const su = await requestStepUpToken(code, STEP_UP_TARGET);
    if ("error" in su) { setErr(su.error); setBusy(false); return; }
    try {
      const r = await api.post<{ id: string; status: string; estimateMinutes: number }>("/setup/export-jobs", startBody(picked, format), { headers: { "X-Step-Up-Token": su.token } });
      setJobId(r.id); setEstimate(r.estimateMinutes); setCode(""); onStarted();
      void poll(r.id);
    } catch (x) { setErr(startError(x)); } finally { setBusy(false); }
  }

  const toggle = (k: string) => setPicked((p) => (p.includes(k) ? p.filter((x) => x !== k) : [...p, k]));

  return (
    <Dialog title="Export all clinic data" onClose={() => { if (!busy) onClose(); }}>
      {!jobId ? (
        <form onSubmit={(e) => void start(e)}>
          <fieldset className={styles.plainFieldset}>
            <legend className={styles.label}>What to include</legend>
            {DATASETS.map((d) => (
              <label key={d.key} className={styles.checkItem} style={{ minBlockSize: 44 }}>
                <input type="checkbox" checked={picked.includes(d.key)} onChange={() => toggle(d.key)} /> {d.label}
              </label>
            ))}
          </fieldset>
          <fieldset className={styles.plainFieldset}>
            <legend className={styles.label}>Format</legend>
            <div className={styles.row} role="radiogroup">
              {FORMATS.map((f) => (
                <label key={f.value} className={styles.chip} style={{ minBlockSize: 44 }}>
                  <input type="radio" name="export-format" value={f.value} checked={format === f.value} onChange={() => setFormat(f.value)} /> {f.label}
                </label>
              ))}
            </div>
          </fieldset>
          <p className={styles.hint}>The download link is sent to your WhatsApp and email. It needs a code sent to your mobile and works for 72 hours and 3 downloads.</p>
          <label className={styles.label} htmlFor="export-otp">Authenticator code</label>
          <input id="export-otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} dir="ltr" value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} style={{ minBlockSize: 44 }} required />
          {err && <div className={styles.errorState} role="alert">{err}</div>}
          <div className={styles.actions}>
            <button type="button" className={styles.btn} style={{ minBlockSize: 44 }} disabled={busy} onClick={onClose}>Cancel</button>
            <button type="submit" className={styles.btnPrimary} style={{ minBlockSize: 44 }} disabled={busy || code.length !== 6 || picked.length === 0}>
              {busy ? "Starting…" : "Start export"}
            </button>
          </div>
        </form>
      ) : (
        <div>
          {job?.status === "done" ? (
            <div role="status">
              <p>Export ready{job.bytes != null ? ` (${size(job.bytes)})` : ""}. The link was sent to your WhatsApp and email{job.linkExpiresAt ? ` and works until ${new Date(job.linkExpiresAt).toLocaleString()}` : ""}.</p>
              {job.archiveSha256 && <p className={styles.hint}>Archive SHA-256: <code dir="ltr" style={{ overflowWrap: "anywhere" }}>{job.archiveSha256}</code></p>}
            </div>
          ) : job?.status === "failed" ? (
            <div className={styles.errorState} role="alert">The export could not be completed. Start it again.</div>
          ) : (
            <p role="status">Export running{estimate ? `, about ${estimate} min` : ""}. You can close this and keep working.</p>
          )}
          {pollFailed && <div className={styles.errorState} role="alert">Couldn&apos;t refresh the progress. It keeps running on the server.</div>}
          {!job ? <p className={styles.hint}>Loading progress…</p> : (
            <ul className={styles.checklist} aria-label="Progress per dataset">
              {job.datasets.map((d) => (
                <li key={d.name} className={styles.checkItem}>
                  <span className={styles.checkLabel}>{DATASETS.find((x) => x.key === d.name)?.label ?? d.name}</span>{" "}
                  <span className={`${styles.pill} ${d.status === "done" ? styles.pillDone : styles.pillPending}`}>{d.status === "done" ? `${d.rows ?? 0} rows` : "waiting"}</span>
                </li>
              ))}
            </ul>
          )}
          <div className={styles.actions}>
            <button type="button" className={styles.btnPrimary} style={{ minBlockSize: 44 }} onClick={onClose}>Close</button>
          </div>
        </div>
      )}
    </Dialog>
  );
}
