"use client";

import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "./ConfirmDialog";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import styles from "./patients.module.css";
import type { GuardianReviewResponse } from "@nabd/api-client/generated";
import { api, ApiError } from "../lib/api";
import type { Full } from "../lib/gen";

// CGV-002: GET /v1/patients/guardian-reviews-due. Only these fields are relied on; extras are ignored.
type Due = Pick<Full<GuardianReviewResponse>, "patientId"> & Partial<Pick<GuardianReviewResponse, "mrn" | "patientName" | "dob" | "guardianId" | "guardianName">>;
type Action = { row: Due; kind: "keep" | "revoke" };

/** Worklist of patients who turned 18 and still have a guardian. Silence is never consent: staff record
 * Keep proxy (POST .../guardian/review {decision:"keep"}) or Revoke (DELETE .../guardian). Without
 * patients:edit (`canAct` false) the rows are shown with no actions. */
export default function GuardianReviews({ canAct, onOpen, onChanged }: {
  canAct: boolean; onOpen: (id: string) => void; onChanged: () => void;
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
      const body = await api.get<unknown>("/patients/guardian-reviews-due");
      setRows(Array.isArray(body) ? (body as Due[]) : []);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) setDenied(true); // no patients:view: the panel is absent
      else setFailed(true);
    }
  }, []);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function confirm() {
    if (!action) return;
    if (busy) return;
    setBusy(true); setErr(null);
    const keep = action.kind === "keep";
    try {
      if (keep) await api.post(`/patients/${action.row.patientId}/guardian/review`, { decision: "keep" });
      else await api.delete(`/patients/${action.row.patientId}/guardian`);
      setAction(null); await load(); onChanged();
    } catch (e) {
      setErr(!(e instanceof ApiError) ? "Couldn't reach the server. Nothing was changed."
        : keep ? "Couldn't record the review. The proxy is unchanged and the review is still due." : "Couldn't revoke guardian access. It is still active.");
    } finally { setBusy(false); }
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
