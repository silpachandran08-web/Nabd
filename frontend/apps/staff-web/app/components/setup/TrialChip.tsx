"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Dialog from "../ui/Dialog";
import styles from "./trialChip.module.css";
import { api, reasonText } from "../../lib/api";
import { can } from "../../lib/permissions";
import { isOwner } from "../../lib/session";
import { CONVERT_DELETES, CONVERT_KEEPS, conversionDone, conversionFailed, conversionRunning, showTrialChip, trialChipText, type TrialStatus } from "../../lib/trial";
import { otpValid } from "../../lib/dpa";

/** ADM-004: "Trial: N days left" in the top bar; the owner can turn the trial into the real clinic (demo patients are deleted, setup is kept). */
export default function TrialChip() {
  const router = useRouter();
  const [status, setStatus] = useState<TrialStatus | null>(null);
  const [open, setOpen] = useState(false);
  const allowed = can("setup:view");

  useEffect(() => {
    if (!allowed) return;
    let live = true;
    api.get<TrialStatus>("/setup/trial").then((s) => { if (live) setStatus(s); }).catch(() => { /* no chip when unknown */ });
    return () => { live = false; };
  }, [allowed]);

  if (!allowed || !showTrialChip(status) || !status) return null;
  const owner = isOwner() && can("setup:edit");
  return (
    <>
      {owner
        ? <button type="button" className={styles.chip} onClick={() => setOpen(true)}>{trialChipText(status)}</button>
        : <span className={styles.chip} role="status">{trialChipText(status)}</span>}
      {open && <StartForRealDialog onClose={() => setOpen(false)} onDone={() => { setOpen(false); setStatus(null); router.push("/setup/wizard"); }} />}
    </>
  );
}

function StartForRealDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [challenge, setChallenge] = useState<string | null>(null);
  const [otp, setOtp] = useState("");
  const [busy, setBusy] = useState(false);
  const [fail, setFail] = useState<string | null>(null);
  const [job, setJob] = useState<TrialStatus | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => () => { if (timer.current) clearInterval(timer.current); }, []);

  async function sendCode() {
    setBusy(true); setFail(null);
    try { setChallenge((await api.post<{ otpChallengeId: string }>("/setup/trial/convert/otp", {})).otpChallengeId); }
    catch (e) { setFail(reasonText(e, "Could not send the code.")); }
    setBusy(false);
  }

  async function convert() {
    if (!challenge) return;
    setBusy(true); setFail(null);
    try {
      await api.post("/setup/trial/convert", { confirm: true, otpChallengeId: challenge, otp });
    } catch (e) { setFail(reasonText(e, "Could not start the clinic.")); setBusy(false); return; }
    // the purge runs in the background: poll until it is done or failed
    timer.current = setInterval(async () => {
      try {
        const s = await api.get<TrialStatus>("/setup/trial");
        setJob(s);
        if (conversionDone(s)) { if (timer.current) clearInterval(timer.current); onDone(); }
        else if (conversionFailed(s)) { if (timer.current) clearInterval(timer.current); setBusy(false); setFail("Clearing the demo data failed. Try again; nothing else was changed."); }
      } catch { /* keep polling; a dropped call is not a failed job */ }
    }, 2000);
  }

  return (
    <Dialog title="Start for real" onClose={onClose}>
      <div className={styles.body}>
        <p>This turns your trial into the real clinic.</p>
        <p className={styles.h}>Kept</p>
        <ul>{CONVERT_KEEPS.map((k) => <li key={k}>{k}</li>)}</ul>
        <p className={styles.h}>Deleted</p>
        <ul>{CONVERT_DELETES.map((k) => <li key={k}>{k}</li>)}</ul>
        <p>You will sign the data agreement next. This cannot be undone.</p>
        {conversionRunning(job) && <p role="status">Clearing demo data…</p>}
        <div className={styles.row}>
          {!challenge
            ? <button type="button" className={styles.btn} onClick={sendCode} disabled={busy}>Send code to my mobile</button>
            : <>
                <input className={styles.otp} inputMode="numeric" autoComplete="one-time-code" maxLength={6} aria-label="6-digit code"
                  value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))} />
                <button type="button" className={styles.danger} onClick={convert} disabled={busy || !otpValid(otp)}>Delete demo data and start</button>
              </>}
        </div>
        {fail && <p className={styles.err} role="alert">{fail}</p>}
      </div>
    </Dialog>
  );
}
