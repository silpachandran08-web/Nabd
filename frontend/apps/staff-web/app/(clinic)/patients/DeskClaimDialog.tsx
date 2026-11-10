"use client";

import { useState } from "react";
import Dialog from "../../components/ui/Dialog";
import styles from "./patients.module.css";
import { api } from "../../lib/api";
import { claimError } from "../../lib/profileClaim";

import type { KeepScope } from "../../lib/profileClaim";

const SCOPES: { value: KeepScope; label: string }[] = [
  { value: "NONE", label: "No access for the former guardian" },
  { value: "BOOK_ONLY", label: "Booking only (no records)" },
  { value: "BOOK_AND_RECORDS", label: "Booking and records" },
];

/** IDC-012: reception records "claimed at desk". The code goes to the patient's own phone (never the guardian's); the patient reads
 * it out and chooses what the former guardian may still do. */
export default function DeskClaimDialog({ patientId, patientName, guardianName, onDone, onClose }: {
  patientId: string; patientName: string; guardianName: string; onDone: () => void; onClose: () => void;
}) {
  const [sent, setSent] = useState(false);
  const [code, setCode] = useState("");
  const [scope, setScope] = useState<KeepScope>("NONE");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function run(step: "send" | "claim") {
    if (busy) return;
    setBusy(true); setErr(null);
    try {
      if (step === "send") { await api.post(`/patients/${patientId}/profile-claim/otp`, {}); setSent(true); setCode(""); }
      else { await api.post(`/patients/${patientId}/profile-claim`, { otp: code, keepGuardianScope: scope }); onDone(); }
    } catch (e) { setErr(claimError(e, step)); } finally { setBusy(false); }
  }

  return (
    <Dialog title="Claimed at desk" onClose={() => { if (!busy) onClose(); }}>
      <form onSubmit={(e) => { e.preventDefault(); void run(sent ? "claim" : "send"); }}>
        <p className={styles.dialogSub}>
          {patientName} takes over their own profile. {guardianName}&apos;s guardian access ends unless {patientName} keeps it below.
          A code is sent to {patientName}&apos;s own phone.
        </p>
        {sent && (
          <>
            <fieldset className={styles.section} style={{ border: 0, padding: 0 }}>
              <legend className={styles.fieldLabel}>What may {guardianName} still do?</legend>
              {SCOPES.map((s) => (
                <label key={s.value} style={{ display: "flex", alignItems: "center", gap: "var(--nb-space-8)", minBlockSize: 44 }}>
                  <input type="radio" name="keep-scope" value={s.value} checked={scope === s.value} onChange={() => setScope(s.value)} /> {s.label}
                </label>
              ))}
            </fieldset>
            <label className={styles.fieldLabel} htmlFor="desk-claim-otp">Code from the patient&apos;s phone</label>
            <input id="desk-claim-otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} dir="ltr"
              value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} autoFocus required />
          </>
        )}
        {err && <div className={styles.errorInline} role="alert">{err}</div>}
        <div className={styles.dialogActions}>
          <button type="button" className={styles.secondaryBtn} disabled={busy} onClick={onClose}>Cancel</button>
          {sent && <button type="button" className={styles.secondaryBtn} disabled={busy} onClick={() => void run("send")}>Send a new code</button>}
          <button type="submit" className={styles.primaryBtn} disabled={busy || (sent && code.length !== 6)}>
            {busy ? "Working…" : sent ? "Confirm claim" : "Send code"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
