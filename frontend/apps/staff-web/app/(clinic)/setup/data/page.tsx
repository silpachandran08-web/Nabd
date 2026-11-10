"use client";

import styles from "../setup.module.css";
import PatientImport from "../../../components/setup/PatientImport";
import { can } from "../../../lib/permissions";
import { SetupTabBody, useSetupTab } from "../setupTab";
import { useState } from "react";
import ExportDrawer from "../../../components/setup/ExportDrawer";

type ImportJob = { id: string; importType: string; fileName: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };
type ExportJob = { id: string; exportType: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };

export default function DataTab() {
  const tab = useSetupTab<{ importJobs: ImportJob[]; exportJobs: ExportJob[] }>({ importJobs: "/setup/import-jobs", exportJobs: "/setup/export-jobs" }, { importJobs: [], exportJobs: [] });
  const { importJobs, exportJobs } = tab.data;
  const [exporting, setExporting] = useState(false); // ADM-015 drawer; the job itself runs on the server
  return (
    <>
      <SetupTabBody tab={tab}>
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Self-serve data import & export</h2>
          {can("setup:edit") && (
            <div className={styles.field}>
              <label className={styles.label}>Import patients</label>
              <PatientImport />
            </div>
          )}
          {can("setup:export") && (
            <div className={styles.row}>
              <div className={styles.field}>
                <label className={styles.label}>Export all clinic data (Excel, CSV, FHIR R4 or PDF per patient)</label>
                <button type="button" className={styles.btnPrimary} onClick={() => setExporting(true)}>Export data</button>
              </div>
            </div>
          )}
          <h3 className={styles.cardTitle}>Recent import jobs</h3>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead><tr><th scope="col">Type</th><th scope="col">File</th><th scope="col">Status</th><th scope="col">Created</th></tr></thead>
              <tbody>
                {importJobs.map((j) => (
                  <tr key={j.id}><td>{j.importType}</td><td>{j.fileName}</td><td><span className={`${styles.pill} ${styles.pillPending}`}>{j.status}</span></td><td>{j.createdAt ? new Date(j.createdAt).toLocaleString() : "—"}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
          <h3 className={styles.cardTitle}>Recent export jobs</h3>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead><tr><th scope="col">Type</th><th scope="col">Status</th><th scope="col">Created</th></tr></thead>
              <tbody>
                {exportJobs.map((j) => (
                  <tr key={j.id}><td>{j.exportType}</td><td><span className={`${styles.pill} ${styles.pillPending}`}>{j.status}</span></td><td>{j.createdAt ? new Date(j.createdAt).toLocaleString() : "—"}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </SetupTabBody>
      {/* Outside SetupTabBody: the refresh after a start shows the skeleton, which must not unmount the drawer and its job state. */}
      {exporting && <ExportDrawer onClose={() => { setExporting(false); tab.loadAll(); }} onStarted={() => tab.loadAll()} />}
    </>
  );
}
