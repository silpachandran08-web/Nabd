"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./dpa.module.css";
import wizard from "../../setup/wizard/wizard.module.css";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { blockerText, otpValid, reacceptBanner, showSigning, type DpaStatus } from "../../lib/dpa";

type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

async function problem(res: Response): Promise<{ type: string; detail: string }> {
  const p = await res.json().catch(() => null);
  return { type: String(p?.type ?? ""), detail: p?.detail || "Request failed" };
}

function useDpa(authedFetch: AuthedFetch) {
  const [status, setStatus] = useState<DpaStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // no setState before the first await, so the effect below never sets state synchronously
  const fetchStatus = useCallback(async () => {
    try {
      const res = await authedFetch("/setup/dpa");
      if (res && res.ok) { setStatus((await res.json()) as DpaStatus); setError(null); }
      else if (res) setError((await problem(res)).detail);
    } catch {
      setError("Could not load the agreement. Check your connection.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load; state is set after the awaited fetch
  useEffect(() => { void fetchStatus(); }, [fetchStatus]);
  const reload = useCallback(() => { setLoading(true); return fetchStatus(); }, [fetchStatus]);
  return { status, error, loading, reload };
}

/** Non-blocking notice for a live clinic when a newer DPA than the accepted one exists. Renders nothing otherwise. */
export function DpaReacceptBanner({ authedFetch }: { authedFetch: AuthedFetch }) {
  const { status } = useDpa(authedFetch);
  const text = reacceptBanner(status);
  return text ? <div className={styles.banner} role="status">{text}</div> : null;
}

/** The DPA step: plain summary, full text on demand, OTP confirmation, signed PDF download. */
export default function DpaPanel({ authedFetch }: { authedFetch: AuthedFetch }) {
  const { status, error, loading, reload } = useDpa(authedFetch);
  const [challenge, setChallenge] = useState<string | null>(null);
  const [otp, setOtp] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [fail, setFail] = useState<string | null>(null);

  if (loading) return <Skeleton rows={3} label="Loading agreement" />;
  if (error || !status) return <ErrorState message={error ?? "Agreement unavailable"} onRetry={reload} />;

  const mayEdit = can("setup:edit");
  const banner = reacceptBanner(status);

  async function sendCode() {
    setBusy(true); setFail(null); setMsg(null);
    const res = await authedFetch("/setup/dpa/otp", { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() }, body: "{}" });
    if (res?.ok) { setChallenge(((await res.json()) as { otpChallengeId: string }).otpChallengeId); setMsg("A code was sent to your registered mobile."); }
    else if (res) setFail((await problem(res)).detail);
    setBusy(false);
  }

  async function sign() {
    if (!challenge || !status) return;
    setBusy(true); setFail(null); setMsg(null);
    const res = await authedFetch("/setup/dpa/accept", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify({ version: status.version, otpChallengeId: challenge, otp }),
    });
    if (res?.ok) { setOtp(""); setChallenge(null); await reload(); setMsg("Agreement signed."); }
    else if (res) setFail((await problem(res)).detail);
    setBusy(false);
  }

  async function download() {
    if (!status?.accepted) return;
    const res = await authedFetch(`/setup/dpa/acceptances/${status.accepted.acceptanceId}/pdf`);
    if (!res || !res.ok) { setFail("Could not download the signed PDF."); return; }
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url; a.download = "nabd-dpa.pdf"; a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className={styles.panel}>
      {banner && <div className={styles.banner} role="status">{banner}</div>}
      {status.dataGate === "live"
        ? <p className={styles.ok}>Data agreement in place: you can register real patients.</p>
        : <>
            <p className={wizard.text}>Until the agreement is signed and a plan is chosen, only the demo clinic works (no real patient records).</p>
            <ul className={styles.blockers}>{status.blockers.map((b) => <li key={b}>{blockerText(b)}</li>)}</ul>
          </>}
      {status.accepted && (
        <p className={wizard.text}>Signed {status.accepted.at.slice(0, 10)}{status.accepted.by ? ` by ${status.accepted.by}` : ""} (version {status.accepted.version}).</p>
      )}
      <details>
        <summary className={styles.summary}>Read the full agreement ({status.marketCode})</summary>
        {/* server-rendered from the fixed template; every dynamic value is HTML-escaped there */}
        <div className={styles.full} dangerouslySetInnerHTML={{ __html: status.html }} />
      </details>
      {showSigning(status, mayEdit) && (
        <div className={styles.row}>
          {!challenge
            ? <button type="button" className={styles.btn} onClick={sendCode} disabled={busy}>Send code to my mobile</button>
            : <>
                <input className={`${wizard.input} ${styles.otp}`} inputMode="numeric" autoComplete="one-time-code" maxLength={6}
                  aria-label="6-digit code" value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))} />
                <button type="button" className={styles.btn} onClick={sign} disabled={busy || !otpValid(otp)}>Sign agreement</button>
              </>}
        </div>
      )}
      {status.accepted && <div className={styles.row}><button type="button" className={styles.btn} onClick={download}>Download signed PDF</button></div>}
      {msg && <p className={styles.ok} role="status">{msg}</p>}
      {fail && <p className={styles.err} role="alert">{fail}</p>}
    </div>
  );
}
