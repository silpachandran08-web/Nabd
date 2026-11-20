"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import styles from "../../(clinic)/setup/setup.module.css";
import { api } from "../../lib/api";
import { requestStepUpToken } from "../../lib/stepUp";
import { REQUEST_TARGET, canSubmit, cancelError, cancelTarget, coolingOffLeft, isOpen, requestBody, requestError, type OffboardingStatus } from "../../lib/offboarding";

/**
 * ADM-016 (owner.setup, after the export): "Leaving Nabd?". Request deletion (a recent full export or a ticked waiver, a reason
 * and the authenticator), watch the 7-day cooling-off count down and cancel during it. Rendered only for the clinic Owner
 * (the caller checks isOwner() && can("setup:export")); Core checks both again. The certificate is emailed after the erasure,
 * when nobody can sign in to this clinic any more.
 */
export default function LeavingCard() {
  const [state, setState] = useState<OffboardingStatus | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState("");
  const [waive, setWaive] = useState(false);
  const [code, setCode] = useState("");
  const [cancelCode, setCancelCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const load = useCallback(async () => {
    try { setState(await api.get<OffboardingStatus>("/setup/offboarding")); setLoadFailed(false); } catch { setLoadFailed(true); }
  }, []);

  useEffect(() => {
    let live = true; // the first load sets state in the callbacks, not in the effect body
    api.get<OffboardingStatus>("/setup/offboarding").then((r) => { if (live) { setState(r); setLoadFailed(false); } }, () => { if (live) setLoadFailed(true); });
    return () => { live = false; };
  }, []);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(t);
  }, []);

  const request = state?.request ?? null;
  const recentExportId = state?.recentExportId ?? null;

  async function ask(e: React.FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true); setErr(null);
    const su = await requestStepUpToken(code, REQUEST_TARGET);
    if ("error" in su) { setErr(su.error); setBusy(false); return; }
    try {
      await api.post("/setup/offboarding", requestBody(reason, recentExportId, waive), { headers: { "X-Step-Up-Token": su.token } });
      setAsking(false); setCode(""); setReason(""); setWaive(false);
      await load();
    } catch (x) { setErr(requestError(x)); } finally { setBusy(false); }
  }

  async function cancel(e: React.FormEvent) {
    e.preventDefault();
    if (busy || !request) return;
    setBusy(true); setErr(null);
    const su = await requestStepUpToken(cancelCode, cancelTarget(request.requestId));
    if ("error" in su) { setErr(su.error); setBusy(false); return; }
    try {
      await api.post(`/setup/offboarding/${request.requestId}/cancel`, {}, { headers: { "X-Step-Up-Token": su.token } });
      setCancelCode("");
      await load();
    } catch (x) { setErr(cancelError(x)); } finally { setBusy(false); }
  }

  return (
    <section className={styles.card} aria-labelledby="leaving-title">
      <h2 id="leaving-title" className={styles.cardTitle}>Leaving Nabd?</h2>
      {loadFailed && !state ? (
        <div className={styles.errorState} role="alert">
          Couldn&apos;t load the deletion status.{" "}
          <button type="button" className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => void load()}>Try again</button>
        </div>
      ) : !state ? (
        <p className={styles.hint} role="status">Loading…</p>
      ) : request?.status === "cooling_off" ? (
        <form onSubmit={(e) => void cancel(e)}>
          <p role="status">
            Deletion of all clinic data starts in <strong>{coolingOffLeft(request.coolingOffEndsAt, now)}</strong> (on {new Date(request.coolingOffEndsAt).toLocaleString()}).
            Until then the clinic works as usual and you can cancel.
          </p>
          <p className={styles.hint}>After that everyone is signed out, the data is erased and a signed deletion certificate is emailed to you, no later than {new Date(request.certificateDueBy).toLocaleDateString()}.</p>
          <label className={styles.label} htmlFor="leaving-cancel-otp">Authenticator code to cancel</label>
          <input id="leaving-cancel-otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} dir="ltr" value={cancelCode}
            onChange={(e) => setCancelCode(e.target.value.replace(/\D/g, ""))} style={{ minBlockSize: 44 }} required />
          {err && <div className={styles.errorState} role="alert">{err}</div>}
          <div className={styles.actions}>
            <button type="submit" className={styles.btnPrimary} style={{ minBlockSize: 44 }} disabled={busy || cancelCode.length !== 6}>{busy ? "Cancelling…" : "Cancel deletion"}</button>
          </div>
        </form>
      ) : isOpen(request) ? (
        <p role="status">The cooling-off period has ended and the clinic data is being erased. It can no longer be cancelled.</p>
      ) : (
        <>
          <p className={styles.hint}>
            You can ask Nabd to delete all data of this clinic. There are 7 days to change your mind; after that the data is erased, backups expire within 35 days
            and you receive a signed deletion certificate by email. {request?.status === "cancelled" ? "Your last request was cancelled; nothing was deleted." : ""}
          </p>
          <div className={styles.actions}>
            <button type="button" className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => { setErr(null); setAsking(true); }}>Request deletion</button>
          </div>
        </>
      )}
      {asking && (
        <Dialog title="Delete all clinic data" onClose={() => { if (!busy) setAsking(false); }}>
          <form onSubmit={(e) => void ask(e)}>
            {recentExportId ? (
              <p className={styles.hint}>You downloaded a full export in the last 14 days. Keep it safe: Nabd keeps no copy after the deletion.</p>
            ) : (
              <label className={styles.checkItem} style={{ minBlockSize: 44 }}>
                <input type="checkbox" checked={waive} onChange={(e) => setWaive(e.target.checked)} />{" "}
                I have not downloaded a full export in the last 14 days and I do not need one. Nabd keeps no copy after the deletion.
              </label>
            )}
            <label className={styles.label} htmlFor="leaving-reason">Reason for leaving</label>
            <textarea id="leaving-reason" className={styles.input} maxLength={500} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} required />
            <label className={styles.label} htmlFor="leaving-otp">Authenticator code</label>
            <input id="leaving-otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} dir="ltr" value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} style={{ minBlockSize: 44 }} required />
            {err && <div className={styles.errorState} role="alert">{err}</div>}
            <div className={styles.actions}>
              <button type="button" className={styles.btn} style={{ minBlockSize: 44 }} disabled={busy} onClick={() => setAsking(false)}>Keep my data</button>
              <button type="submit" className={styles.btnPrimary} style={{ minBlockSize: 44 }} disabled={busy || !canSubmit(reason, code, recentExportId, waive)}>
                {busy ? "Requesting…" : "Request deletion"}
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </section>
  );
}
