"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import styles from "../../fleet.module.css";
import { problemDetail, useOperatorAccess, usePlatformFetch } from "../../../platformApi";
import { EmptyState, ErrorState, Skeleton } from "../../../../components/ui/StateViews";
import RoleDenied from "../../../../components/platform/RoleDenied";

// PLT-059: per-clinic feature switches (tenant overlay). Matches GET/PUT /v1/platform/tenants/{id}/features[/{code}].
// Server: tenant_detail:edit (super_admin) to change, a reason is always required, every change is audited.
type Override = { code: string; state: "on" | "off" | "kill"; reason: string; setAt: string; expiresAt: string | null };

// Server: PlanEntitlements.FEATURES.
const FEATURES = ["queue_eta", "whatsapp_booking", "whatsapp_bot", "emr_erx", "billing", "pharmacy_external", "pharmacy_inhouse", "lab", "packages", "branded_site", "hr", "insurance", "ipd", "patient_app", "ai_safety"];

export default function TenantFeaturesPage() {
  const { tenantId } = useParams<{ tenantId: string }>();
  const access = useOperatorAccess();
  const fetchAuthed = usePlatformFetch();
  const [rows, setRows] = useState<Override[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [code, setCode] = useState(FEATURES[0]);
  const [state, setState] = useState<"on" | "off" | "kill">("kill");
  const [reason, setReason] = useState("");
  const [expires, setExpires] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetchAuthed(`/platform/tenants/${tenantId}/features`);
      if (!res) return;
      if (res.status === 403) { setForbidden(true); return; }
      if (!res.ok) { setError("Couldn't load the feature switches. Try again."); return; }
      setRows(await res.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [fetchAuthed, tenantId]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!reason.trim()) { setFormError("A reason is required."); return; }
    setSaving(true);
    try {
      const body = { state, reason: reason.trim(), expiresAt: expires ? new Date(expires).toISOString() : undefined };
      const res = await fetchAuthed(`/platform/tenants/${tenantId}/features/${code}`, { method: "PUT", body: JSON.stringify(body) });
      if (!res) return;
      if (!res.ok) { setFormError(await problemDetail(res, "Couldn't save the switch.")); return; }
      setReason(""); setExpires("");
      void load();
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className={styles.page}>
      <h1>Feature switches</h1>
      <p><Link href="/platform/fleet">Back to clinics</Link></p>
      {access.ready && access.isSuper && !forbidden && (
        <form onSubmit={submit} aria-label="Set a feature switch">
          <label htmlFor="feature">Feature</label>
          <select id="feature" value={code} onChange={(e) => setCode(e.target.value)}>
            {FEATURES.map((f) => <option key={f} value={f}>{f.replace(/_/g, " ")}</option>)}
          </select>
          <label htmlFor="state">State</label>
          <select id="state" value={state} onChange={(e) => setState(e.target.value as "on" | "off" | "kill")}>
            <option value="on">On (override the plan)</option>
            <option value="off">Off (not in plan)</option>
            <option value="kill">Kill (503 for the clinic)</option>
          </select>
          <label htmlFor="reason">Reason</label>
          <input id="reason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} required />
          <label htmlFor="expires">Expires (optional)</label>
          <input id="expires" type="datetime-local" value={expires} onChange={(e) => setExpires(e.target.value)} />
          <button type="submit" disabled={saving} style={{ minBlockSize: 44 }}>{saving ? "Saving…" : "Save switch"}</button>
          {formError && <div role="alert">{formError}</div>}
        </form>
      )}
      {loading ? <Skeleton rows={4} label="Loading feature switches" />
        : forbidden ? <RoleDenied surface="Tenant detail" />
        : error ? <ErrorState message={error} onRetry={load} />
        : rows.length === 0 ? <EmptyState icon="shield" title="No overrides" hint="This clinic follows its plan." />
        : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead><tr><th>Feature</th><th>State</th><th>Reason</th><th>Expires</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.code} data-testid={`override-${r.code}`}>
                    <td>{r.code}</td>
                    <td style={r.state === "kill" ? { color: "var(--nb-danger, #ff6b6b)", fontWeight: 600 } : undefined}>{r.state}</td>
                    <td>{r.reason}</td>
                    <td>{r.expiresAt ? new Date(r.expiresAt).toLocaleString() : "never"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </main>
  );
}
