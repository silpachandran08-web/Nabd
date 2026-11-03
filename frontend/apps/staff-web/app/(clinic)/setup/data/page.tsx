"use client";

import styles from "../setup.module.css";
import PatientImport from "../../../components/setup/PatientImport";
import { can } from "../../../lib/permissions";
import { SetupTabBody, useSetupTab } from "../setupTab";

type ImportJob = { id: string; importType: string; fileName: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };
type ExportJob = { id: string; exportType: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };

export default function DataTab() {
  const tab = useSetupTab<{ importJobs: ImportJob[]; exportJobs: ExportJob[] }>({ importJobs: "/setup/import-jobs", exportJobs: "/setup/export-jobs" }, { importJobs: [], exportJobs: [] });
  const { importJobs, exportJobs } = tab.data;
  const createExport = async (exportType: string) => {
    if (await tab.post("/setup/export-jobs", { exportType })) tab.loadAll();
  };
  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Self-serve data import & export</h2>
        {can("setup:edit") && (
          <div className={styles.field}>
            <label className={styles.label}>Import patients</label>
            <PatientImport />
          </div>
        )}
        <div className={styles.row}>
          <div className={styles.field}>
            <label className={styles.label}>Export full tenant</label>
            <button className={styles.btnPrimary} onClick={() => createExport("full_tenant")}>Start export job</button>
          </div>
        </div>
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
  );
}
