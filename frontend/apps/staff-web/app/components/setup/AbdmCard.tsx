"use client";

// IDC-016: the ABDM switch. Off by default (DEC-33); turning it on verifies the facility's HFR id first.
// The controls need setup:edit and are absent otherwise; a fresh authenticator code confirms the change.
import { useCallback, useEffect, useState } from "react";
import styles from "../../setup/setup.module.css";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { apiFetch } from "../../lib/apiClient";
import { requestStepUpToken } from "../../lib/stepUp";

type Abdm = { enabled: boolean; hfrVerified: boolean; hfrId: string | null };

export default function AbdmCard() {
  const [s, setS] = useState<Abdm | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hfr, setHfr] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [fail, setFail] = useState<string | null>(null);
  const mayEdit = can("setup:edit");

  const load = useCallback(async () => {
    try {
      const res = await apiFetch("/settings/integrations/abdm");
      if (!res.ok) { setError("Could not load the ABDM settings."); return; }
      const body = (await res.json()) as Abdm;
      setS(body); setHfr(body.hfrId ?? ""); setError(null);
    } catch { setError("Could not reach the server. Check your connection."); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load; state is set after the awaited fetch
  useEffect(() => { void load(); }, [load]);

  if (error && !s) return <ErrorState message={error} onRetry={() => void load()} />;
  if (!s) return <Skeleton rows={3} label="Loading ABDM settings" />;

  async function save(enabled: boolean) {
    setBusy(true); setFail(null);
    const su = await requestStepUpToken(code);
    if ("error" in su) { setFail(su.error); setBusy(false); return; }
    try {
      const res = await apiFetch("/settings/integrations/abdm", {
        method: "PUT", headers: { "Content-Type": "application/json", "X-Step-Up-Token": su.token },
        body: JSON.stringify(enabled ? { enabled, hfrId: hfr.trim() } : { enabled }),
      });
      if (res.ok) { setCode(""); await load(); }
      else setFail(res.status === 422 ? "This facility id is not in the Health Facility Registry." : res.status === 503 ? "ABDM cannot be reached right now. Try again later." : "Could not save.");
    } catch { setFail("Could not reach the server. Check your connection."); }
    setBusy(false);
  }

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>ABDM (Ayushman Bharat Digital Mission)</h2>
      <p>{s.enabled ? `On. Facility ${s.hfrId} verified.` : "Off. Nothing is shared with ABDM until you turn it on."}</p>
      {mayEdit && (
        <>
          {!s.enabled && (
            <>
              <label className={styles.label} htmlFor="abdm-hfr">Facility id (HFR)</label>
              <input id="abdm-hfr" className={styles.input} value={hfr} onChange={(e) => setHfr(e.target.value)} maxLength={40} style={{ minBlockSize: 44 }} />
            </>
          )}
          <div className={styles.row}>
            <input className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} aria-label="Authenticator code"
              placeholder="6-digit code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} style={{ minBlockSize: 44 }} />
            {s.enabled
              ? <button type="button" className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => void save(false)} disabled={busy || code.length !== 6}>Turn off ABDM</button>
              : <button type="button" className={styles.btnPrimary} style={{ minBlockSize: 44 }} onClick={() => void save(true)} disabled={busy || code.length !== 6 || hfr.trim().length < 3}>Verify and turn on</button>}
          </div>
        </>
      )}
      {fail && <div className={styles.errorState} role="alert">{fail}</div>}
    </div>
  );
}
