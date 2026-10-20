"use client";

import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "./ConfirmDialog";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import styles from "./patients.module.css";

// CGV-002: GET /v1/patients/guardian-reviews-due. Only these fields are relied on; extras are ignored.
type Due = { patientId: string; mrn?: string | null; patientName?: string | null; dob?: string | null; guardianId?: string | null; guardianName?: string | null };
type Action = { row: Due; kind: "keep" | "revoke" };
type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

/** Worklist of patients who turned 18 and still have a guardian. Silence is never consent: staff record
 * Keep proxy (POST .../guardian/review {decision:"keep"}) or Revoke (DELETE .../guardian). Without
 * patients:edit (`canAct` false) the rows are shown with no actions. */
export default function GuardianReviews({ authedFetch, canAct, onOpen, onChanged }: {
  authedFetch: AuthedFetch; canAct: boolean; onOpen: (id: string) => void; onChanged: () => void;
}) {
  const [rows, setRows] = useState<Due[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [denied, setDenied] = useState(false);
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setFailed(false);
    try {
      const res = await authedFetch("/patients/guardian-reviews-due");
      if (!res) return;
      if (res.status === 403) { setDenied(true); return; } // no patients:view: the panel is absent
      if (!res.ok) { setFailed(true); return; }
      const body: unknown = await res.json();
      setRows(Array.isArray(body) ? (body as Due[]) : []);
    } catch { setFailed(true); }
  }, [authedFetch]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function confirm() {
    if (!action) return;
    setBusy(true); setErr(null);
    const keep = action.kind === "keep";
    try {
      const res = await authedFetch(`/patients/${action.row.patientId}/guardian${keep ? "/review" : ""}`,
        keep ? { method: "POST", body: JSON.stringify({ decision: "keep" }) } : { method: "DELETE" });
      if (res?.ok) { setAction(null); await load(); onChanged(); }
      else if (res) setErr(keep ? "Couldn't record the review. The proxy is unchanged and the review is still due." : "Couldn't revoke guardian access. It is still active.");
    } catch { setErr("Couldn't reach the server. Nothing was changed."); }
    finally { setBusy(false); }
  }

  if (denied) return null;
  const name = action ? (action.row.patientName ?? "this patient") : "";
  const guardian = action?.row.guardianName ?? "The guardian";
  return (
    <section className={styles.card} aria-label="Guardian reviews due" style={{ marginBlockEnd: "var(--nb-space-16)" }}>
      <div className={styles.section}>
        <div className={styles.sectionLabel}>Guardian reviews due (turned 18)</div>
        {failed ? <ErrorState message="Couldn't load guardian reviews." onRetry={() => void load()} />
          : rows === null ? <Skeleton rows={2} label="Loading guardian reviews" />
          : rows.length === 0 ? <EmptyState icon="check" title="No guardian reviews are due." />
          : rows.map((r) => (
            <div key={r.patientId} className={styles.allergyRow}>
              <span>
                <button type="button" className={styles.linkBtn} style={{ minBlockSize: 44 }} onClick={() => onOpen(r.patientId)}>{r.patientName ?? "Patient"}</button>
                {r.mrn ? ` · ${r.mrn}` : ""}{" "}
                <span className={styles.muted}>Guardian: {r.guardianName ?? "name not on file"}</span>
              </span>
              {canAct && (
                <span>
                  <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => { setErr(null); setAction({ row: r, kind: "keep" }); }}>Keep proxy</button>{" "}
                  <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => { setErr(null); setAction({ row: r, kind: "revoke" }); }}>Revoke</button>
                </span>
              )}
            </div>
          ))}
      </div>
      {action && (
        <ConfirmDialog busy={busy} error={err} onConfirm={() => void confirm()} onClose={() => setAction(null)}
          title={action.kind === "keep" ? "Keep guardian as proxy" : "Revoke guardian access"}
          confirmLabel={action.kind === "keep" ? "Keep proxy" : "Revoke access"}
          message={action.kind === "keep"
            ? `${guardian} stays as proxy for ${name}. This review is recorded and audited.`
            : `${guardian} will no longer be able to act for ${name}, and the guardian consent is withdrawn.`} />
      )}
    </section>
  );
}
