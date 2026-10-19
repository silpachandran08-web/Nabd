"use client";

import { useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import styles from "../../staff/staff.module.css";

// DOC-N1: edit one staff member's professional registration number (printed on signed prescriptions).
// PATCH /staff/{id} { professionalRegistrationNo } needs staff:edit; the caller only renders this for holders of it.
// Empty input clears the number. The pattern mirrors the server's (letters, digits, space . / -, 1 to 40).
// CLN-005: the same dialog marks the person as a registered clinician (who may sign) and records the licence expiry:
// GET/PUT /staff/{id}/clinical-licence. Marking a clinician needs a registration number (the server refuses otherwise).
const PATTERN = /^[A-Za-z0-9][A-Za-z0-9 ./-]{0,39}$/;

type Licence = { isClinician: boolean; licenceExpiresOn: string | null; licenceOverrideUntil: string | null };
type Fetcher = (path: string, init?: RequestInit) => Promise<Response | null | undefined>;

export default function RegistrationNoDialog({ staffId, staffName, current, onSave, onClose, authedFetch }: {
  staffId: string;
  staffName: string;
  current: string | null;
  onSave: (value: string) => Promise<string | null>; // resolves to an error message, or null when saved
  onClose: () => void;
  authedFetch: Fetcher;
}) {
  const [value, setValue] = useState(current ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [licence, setLicence] = useState<Licence | null | "error">(null);
  const [clinician, setClinician] = useState(false);
  const [expires, setExpires] = useState("");

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const res = await authedFetch(`/staff/${staffId}/clinical-licence`);
        if (!live) return;
        if (!res || !res.ok) { setLicence("error"); return; }
        const l: Licence = await res.json();
        setLicence(l);
        setClinician(l.isClinician);
        setExpires(l.licenceExpiresOn ?? "");
      } catch {
        if (live) setLicence("error");
      }
    })();
    return () => { live = false; };
  }, [authedFetch, staffId]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const v = value.trim();
    if (v !== "" && !PATTERN.test(v)) {
      setError("Use 1 to 40 letters, digits, spaces, dots, slashes or hyphens.");
      return;
    }
    if (clinician && v === "") {
      setError("Add the registration number before marking this person as a registered clinician.");
      return;
    }
    setSaving(true);
    setError(null);
    const lic = licence !== null && licence !== "error" ? licence : null;
    const licenceChanged = lic !== null && (lic.isClinician !== clinician || (lic.licenceExpiresOn ?? "") !== expires);
    // Unmarking first, then the number, then marking: the server never sees "clinician without a number".
    let err = licenceChanged && !clinician ? await saveLicence() : null;
    if (!err && v !== (current ?? "")) err = await onSave(v);
    if (!err && licenceChanged && clinician) err = await saveLicence();
    setSaving(false);
    if (err) setError(err);
    else onClose();
  }

  async function saveLicence(): Promise<string | null> {
    try {
      const res = await authedFetch(`/staff/${staffId}/clinical-licence`, { method: "PUT",
        body: JSON.stringify({ isClinician: clinician, licenceExpiresOn: expires === "" ? null : expires }) });
      if (!res) return null;
      if (!res.ok) {
        const body = await res.json().catch(() => ({ detail: "" }));
        return body.detail || "Couldn't save the clinician status.";
      }
      return null;
    } catch {
      return "Couldn't reach the server. The clinician status was not saved.";
    }
  }

  const lic = licence !== null && licence !== "error" ? licence : null;
  const expired = expires !== "" && expires < new Date().toISOString().slice(0, 10);

  return (
    <Dialog title="Registration number" onClose={onClose}>
      <form className={styles.dialogForm} onSubmit={submit}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="regNo">Medical registration number for {staffName}</label>
          <input id="regNo" className={styles.input} value={value} maxLength={40} autoFocus autoComplete="off"
            onChange={(e) => setValue(e.target.value)} />
        </div>
        <p className={styles.muted}>Printed on this person&apos;s signed prescriptions. Leave empty to remove it; nothing is printed when it is not set.</p>
        {licence === null && <p className={styles.muted} role="status">Loading clinician status…</p>}
        {licence === "error" && <p className={styles.formError} role="alert">Couldn&apos;t load the clinician status. You can still save the number.</p>}
        {lic && (
          <>
            <label style={{ display: "flex", alignItems: "center", gap: "var(--nb-space-8)", minBlockSize: 44 }}>
              <input type="checkbox" checked={clinician} onChange={(e) => setClinician(e.target.checked)} style={{ inlineSize: 20, blockSize: 20 }} />
              <span>Registered clinician (may sign prescriptions and documents)</span>
            </label>
            {clinician && (
              <div className={styles.field}>
                <label className={styles.label} htmlFor="licExp">Registration valid until (optional)</label>
                <input id="licExp" type="date" className={styles.input} value={expires} onChange={(e) => setExpires(e.target.value)} />
              </div>
            )}
            {clinician && expired && <p className={styles.formError} role="status">This registration has expired, so this person cannot sign{lic.licenceOverrideUntil ? ` (owner override until ${new Date(lic.licenceOverrideUntil).toLocaleString()})` : ""}.</p>}
          </>
        )}
        <button className={styles.submit} type="submit" disabled={saving}>{saving ? "Saving…" : "Save"}</button>
        {error && <div className={styles.formError} role="alert">{error}</div>}
      </form>
    </Dialog>
  );
}
