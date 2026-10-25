"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import s from "./patientImport.module.css";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { apiFetch, uuidv7 } from "../../lib/apiClient";

// ADM-012: upload -> dry-run diff -> mapping -> commit -> progress. Mounted by the setup page only for setup:edit.
// Nothing is written to patients until the owner taps "Import"; the commit runs in the background.
type Diff = { ready?: number; duplicates?: number; errors?: number; total?: number; needsMapping?: boolean; created?: number; merged?: number; skipped?: number };
type Mapping = { headers?: string[]; columns?: { source: string; target: string }[]; dateFormat?: string };
type Job = { id: string; status: string; fileName: string; errorMessage: string | null; rowsTotal: number | null; rowsDone: number | null; diff: Diff | null; mapping: Mapping | null };
type Dup = { row: number; reason: "DUP_FILE" | "DUP_REGISTRY"; name: string; mobileEnding: string; dob: string; decision: string | null };
type Action = "skip" | "merge" | "create";

export const FIELDS: { key: string; label: string; required: boolean }[] = [
  { key: "name", label: "Name", required: true },
  { key: "mobile", label: "Mobile", required: true },
  { key: "dob", label: "Date of birth", required: true },
  { key: "sex", label: "Sex", required: true },
  { key: "oldRecordNo", label: "Old record number", required: false },
  { key: "allergies", label: "Allergies (a doctor confirms them later)", required: false },
];
export const DATE_FORMATS = ["dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd"];
const TEMPLATE = "Name,Mobile,Date of Birth,Sex,Old Record No,Allergies\r\n";
const FAIL_TEXT: Record<string, string> = {
  FILE_UNREADABLE: "The file could not be read. Save it again as .xlsx or .csv.",
  NO_ROWS: "The file has a header but no patient rows.",
  TOO_MANY_ROWS: "The file has too many rows (limit 100,000). Split it and import in parts.",
  FILE_TOO_LARGE: "The file is too large.",
  NO_SHEET: "No worksheet was found in the file.",
  BAD_XLSX: "The Excel file is damaged.",
  CHUNK_FAILED: "The import stopped after repeated failures. Nothing was created twice; contact support to resume.",
};

async function problem(res: Response, fallback: string): Promise<string> {
  const p = await res.json().catch(() => null);
  if (res.status === 403 && /DPA_REQUIRED/.test(p?.type ?? "")) return "Sign the data processing agreement and choose a plan before importing real patients.";
  return p?.detail || fallback;
}

export default function PatientImport() {
  const [pane, setPane] = useState<"file" | "software" | "paper">("file");
  const [job, setJob] = useState<Job | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [columns, setColumns] = useState<Record<string, string>>({});
  const [dateFormat, setDateFormat] = useState(DATE_FORMATS[0]);
  const [sample, setSample] = useState<Record<string, string>[]>([]);
  const [drawer, setDrawer] = useState(false);
  const [dups, setDups] = useState<Dup[] | null>(null);
  const [dupError, setDupError] = useState<string | null>(null);
  const [decisions, setDecisions] = useState<Record<number, Action>>({});
  const fileRef = useRef<HTMLInputElement>(null);

  // The mapping form follows the server's mapping whenever a new job state arrives.
  const adopt = useCallback((j: Job) => {
    setJob(j);
    if (!j.mapping) return;
    const m: Record<string, string> = {};
    for (const c of j.mapping.columns ?? []) m[c.target] = c.source;
    setColumns(m);
    if (j.mapping.dateFormat) setDateFormat(j.mapping.dateFormat);
  }, []);

  const status = job?.status;
  // Poll while the server is working (parsing or importing); stops by itself on any other status.
  useEffect(() => {
    if (!job || (status !== "validating" && status !== "importing")) return;
    let live = true;
    const t = setTimeout(async () => {
      try {
        const res = await apiFetch(`/setup/import-jobs/${job.id}`);
        if (!live) return;
        if (res.ok) adopt((await res.json()) as Job);
        else setError(await problem(res, "Could not read the import progress."));
      } catch { if (live) setError("Could not reach the server. Retrying…"); }
    }, 1500);
    return () => { live = false; clearTimeout(t); };
  }, [job, status, adopt]);

  async function start(file: File) {
    setBusy(true); setError(null); setJob(null); setDups(null); setDecisions({}); setSample([]);
    try {
      const format = /\.xlsx$/i.test(file.name) ? "xlsx" : /\.csv$/i.test(file.name) ? "csv" : null;
      if (!format) { setError("Choose an .xlsx or .csv file."); return; }
      const up = await apiFetch(`/setup/import-uploads?fileName=${encodeURIComponent(file.name)}&format=${format}`, { method: "POST", body: file, headers: { "Content-Type": "application/octet-stream" } });
      if (!up.ok) { setError(await problem(up, "Upload failed.")); return; }
      const { uploadId } = await up.json();
      const created = await apiFetch("/setup/import-jobs", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ uploadId, importType: "patients", sourceFormat: format }) });
      if (!created.ok) { setError(await problem(created, "Could not start the import.")); return; }
      adopt((await created.json()) as Job);
    } catch { setError("Could not reach the server."); } finally { setBusy(false); if (fileRef.current) fileRef.current.value = ""; }
  }

  async function saveMapping() {
    if (!job) return;
    setBusy(true); setError(null);
    try {
      const cols = Object.entries(columns).filter(([, src]) => src).map(([target, source]) => ({ source, target }));
      const res = await apiFetch(`/setup/import-jobs/${job.id}/mapping`, { method: "PUT", headers: { "Content-Type": "application/json", "Idempotency-Key": uuidv7() }, body: JSON.stringify({ columns: cols, dateFormat }) });
      if (!res.ok) { setError(await problem(res, "Could not apply the mapping.")); return; }
      const body = await res.json();
      setSample(body.sample ?? []);
      setDups(null); setDecisions({});
      setJob({ ...job, diff: body.diff, mapping: { headers: job.mapping?.headers, columns: cols, dateFormat } });
    } catch { setError("Could not reach the server."); } finally { setBusy(false); }
  }

  const loadDups = useCallback(async (id: string) => {
    setDupError(null);
    try {
      const all: Dup[] = [];
      let after = 0;
      for (;;) {
        const res = await apiFetch(`/setup/import-jobs/${id}/duplicates?afterRow=${after}&limit=100`);
        if (!res.ok) { setDupError(await problem(res, "Could not load the possible duplicates.")); return; }
        const page = await res.json();
        all.push(...page.items);
        if (page.nextAfterRow == null) break;
        after = page.nextAfterRow;
      }
      setDups(all);
    } catch { setDupError("Could not reach the server."); }
  }, []);

  async function openDrawer() {
    if (!job) return;
    setDrawer(true);
    if (!dups) await loadDups(job.id);
  }

  async function downloadErrors() {
    if (!job) return;
    const res = await apiFetch(`/setup/import-jobs/${job.id}/errors.csv`);
    if (!res.ok) { setError(await problem(res, "Could not download the error report.")); return; }
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url; a.download = "import-errors.csv"; a.click();
    URL.revokeObjectURL(url);
  }

  async function commit() {
    if (!job) return;
    setBusy(true); setError(null);
    try {
      const duplicateDecisions = Object.entries(decisions).map(([row, action]) => ({ row: Number(row), action }));
      const res = await apiFetch(`/setup/import-jobs/${job.id}/commit`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ duplicateDecisions }) });
      if (!res.ok) { setError(await problem(res, "Could not start the import.")); return; }
      setJob({ ...job, status: "importing", rowsDone: 0 });
    } catch { setError("Could not reach the server."); } finally { setBusy(false); }
  }

  function downloadTemplate() {
    const url = URL.createObjectURL(new Blob([TEMPLATE], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url; a.download = "patients-template.csv"; a.click();
    URL.revokeObjectURL(url);
  }

  const diff = job?.diff ?? {};
  const importable = (diff.ready ?? 0) + Object.values(decisions).filter((a) => a !== "skip").length;
  const missing = FIELDS.filter((f) => f.required && !columns[f.key]);

  return (
    <section className={s.panel} aria-label="Import patients">
      <div className={s.tabs} role="tablist" aria-label="Where is your patient list">
        {([["file", "Excel / CSV"], ["software", "Old software"], ["paper", "Paper"]] as const).map(([k, l]) => (
          <button key={k} type="button" role="tab" aria-selected={pane === k} className={`${s.tab} ${pane === k ? s.tabOn : ""}`} onClick={() => setPane(k)}>{l}</button>
        ))}
      </div>

      {pane === "software" && <p className={s.note}>Export your patient list from the old software as Excel or CSV, then use the Excel / CSV tab. Columns are matched by their headings.</p>}
      {pane === "paper" && (
        <div className={s.panel}>
          <p className={s.note}>Type the register into the template, one patient per row, then upload it from the Excel / CSV tab.</p>
          <div className={s.row}><button type="button" className={s.btn} onClick={downloadTemplate}>Download template (CSV)</button></div>
        </div>
      )}

      {pane === "file" && (
        <>
          {(!job || status === "failed") && (
            <div className={s.row}>
              <input ref={fileRef} type="file" aria-label="Patient file" accept=".xlsx,.csv" className={s.select} onChange={(e) => { const f = e.target.files?.[0]; if (f) void start(f); }} disabled={busy} />
              <span className={s.note}>Up to 25 MB. Nothing is saved to your patient list until you confirm.</span>
            </div>
          )}
          {busy && !job && <Skeleton rows={2} label="Uploading" />}
          {error && <ErrorState message={error} />}

          {job && status === "validating" && <Skeleton rows={3} label="Checking the file" />}
          {job && status === "failed" && <ErrorState message={FAIL_TEXT[job.errorMessage ?? ""] ?? "The file could not be checked."} />}

          {job && status === "dry_run" && (
            <div className={s.panel}>
              {(job.diff?.total ?? 0) === 0 ? <EmptyState title="No patient rows found" hint="Check that the file has a heading row and at least one patient." /> : (
                <>
                  <div className={s.tiles} aria-label="Dry-run result">
                    <div className={`${s.tile} ${s.ok}`}><div className={s.tileN}>{diff.ready ?? 0}</div><div className={s.tileL}>Ready to create</div></div>
                    <div className={`${s.tile} ${s.warn}`}><div className={s.tileN}>{diff.duplicates ?? 0}</div><div className={s.tileL}>Possible duplicates</div></div>
                    <div className={`${s.tile} ${s.bad}`}><div className={s.tileN}>{diff.errors ?? 0}</div><div className={s.tileL}>Errors</div></div>
                    <div className={s.tile}><div className={s.tileN}>{diff.total ?? 0}</div><div className={s.tileL}>Rows in file</div></div>
                  </div>

                  <div className={s.wide}>
                    <table className={s.mapTable}>
                      <caption className={s.note}>Match each field to a column of your file</caption>
                      <thead><tr><th scope="col">Field</th><th scope="col">Column in your file</th></tr></thead>
                      <tbody>
                        {FIELDS.map((f) => (
                          <tr key={f.key}>
                            <th scope="row">{f.label}{f.required ? " *" : ""}</th>
                            <td>
                              <select className={s.select} aria-label={`Column for ${f.label}`} value={columns[f.key] ?? ""} onChange={(e) => setColumns({ ...columns, [f.key]: e.target.value })}>
                                <option value="">{f.required ? "Choose a column" : "Not in my file"}</option>
                                {(job.mapping?.headers ?? []).map((h) => <option key={h} value={h}>{h}</option>)}
                              </select>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className={s.row}>
                    <label className={s.note} htmlFor="imp-datefmt">Date format</label>
                    <select id="imp-datefmt" className={s.select} value={dateFormat} onChange={(e) => setDateFormat(e.target.value)}>
                      {DATE_FORMATS.map((f) => <option key={f} value={f}>{f}</option>)}
                    </select>
                    <button type="button" className={s.btn} onClick={saveMapping} disabled={busy || missing.length > 0}>Apply and check again</button>
                    {missing.length > 0 && <span className={s.err}>Map: {missing.map((m) => m.label).join(", ")}</span>}
                  </div>
                  {sample.length > 0 && (
                    <div className={s.wide}>
                      <table className={s.mapTable} aria-label="Sample row">
                        <caption className={s.note}>First row as we read it</caption>
                        <thead><tr>{Object.keys(sample[0]).map((k) => <th key={k} scope="col">{FIELDS.find((f) => f.key === k)?.label ?? k}</th>)}</tr></thead>
                        <tbody><tr>{Object.values(sample[0]).map((v, i) => <td key={i}>{v}</td>)}</tr></tbody>
                      </table>
                    </div>
                  )}

                  <div className={s.row}>
                    {(diff.duplicates ?? 0) > 0 && <button type="button" className={s.btn} onClick={openDrawer}>Review {diff.duplicates} possible duplicates</button>}
                    {(diff.errors ?? 0) > 0 && <button type="button" className={s.btn} onClick={downloadErrors}>Download error report (CSV)</button>}
                  </div>
                  <p className={s.note}>Possible duplicates are skipped unless you choose to merge them into the existing patient or create them anyway. Imported allergies are saved as unconfirmed: a doctor confirms them at the next consult.</p>
                  <div className={s.row}>
                    <button type="button" className={s.btnPrimary} onClick={commit} disabled={busy || diff.needsMapping === true || importable === 0}>Import {importable} patients</button>
                    <button type="button" className={s.btn} onClick={() => { setJob(null); setDups(null); setDecisions({}); setSample([]); }}>Cancel</button>
                  </div>
                </>
              )}
            </div>
          )}

          {job && status === "importing" && (
            <div className={s.panel} role="status" aria-live="polite">
              <div className={s.bar} aria-hidden="true"><div className={s.barFill} style={{ inlineSize: `${job.rowsTotal ? Math.min(100, Math.round(((job.rowsDone ?? 0) / job.rowsTotal) * 100)) : 0}%` }} /></div>
              <p className={s.note}>Importing {job.rowsDone ?? 0} of {job.rowsTotal ?? 0} patients. You can keep working; this continues in the background.</p>
            </div>
          )}

          {job && status === "done" && (
            <div className={s.panel}>
              <p className={s.okText}>Import finished.</p>
              <div className={s.tiles}>
                <div className={`${s.tile} ${s.ok}`}><div className={s.tileN}>{diff.created ?? 0}</div><div className={s.tileL}>Created</div></div>
                <div className={s.tile}><div className={s.tileN}>{diff.merged ?? 0}</div><div className={s.tileL}>Merged</div></div>
                <div className={`${s.tile} ${s.warn}`}><div className={s.tileN}>{diff.skipped ?? 0}</div><div className={s.tileL}>Skipped</div></div>
                <div className={`${s.tile} ${s.bad}`}><div className={s.tileN}>{diff.errors ?? 0}</div><div className={s.tileL}>Errors</div></div>
              </div>
              <div className={s.row}>
                {(diff.errors ?? 0) > 0 && <button type="button" className={s.btn} onClick={downloadErrors}>Download error report (CSV)</button>}
                <button type="button" className={s.btn} onClick={() => setJob(null)}>Import another file</button>
              </div>
            </div>
          )}
        </>
      )}

      {drawer && (
        <Dialog title="Possible duplicates" size="drawer" onClose={() => setDrawer(false)}>
          {dupError ? <ErrorState message={dupError} onRetry={() => job && void loadDups(job.id)} /> : !dups ? <Skeleton rows={4} label="Loading possible duplicates" /> : dups.length === 0 ? <EmptyState title="No possible duplicates" /> : (
            <ul className={s.dupList}>
              {dups.map((d) => (
                <li key={d.row} className={s.dup}>
                  <span>Row {d.row}: {d.name}, mobile ending {d.mobileEnding}, born {d.dob}<br /><span className={s.note}>{d.reason === "DUP_REGISTRY" ? "Already a patient" : "Repeated in this file"}</span></span>
                  <select className={s.select} aria-label={`Action for row ${d.row}`} value={decisions[d.row] ?? "skip"} onChange={(e) => setDecisions({ ...decisions, [d.row]: e.target.value as Action })}>
                    <option value="skip">Skip</option>
                    <option value="merge">Merge into existing</option>
                    <option value="create">Create anyway</option>
                  </select>
                </li>
              ))}
            </ul>
          )}
          <div className={s.row}><button type="button" className={s.btnPrimary} onClick={() => setDrawer(false)}>Done</button></div>
        </Dialog>
      )}
    </section>
  );
}
