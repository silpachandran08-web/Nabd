"use client";

import { useState } from "react";
import Dialog from "../ui/Dialog";
import styles from "../../staff/staff.module.css";

// DOC-N1: edit one staff member's professional registration number (printed on signed prescriptions).
// PATCH /staff/{id} { professionalRegistrationNo } needs staff:edit; the caller only renders this for holders of it.
// Empty input clears the number. The pattern mirrors the server's (letters, digits, space . / -, 1 to 40).
const PATTERN = /^[A-Za-z0-9][A-Za-z0-9 ./-]{0,39}$/;

export default function RegistrationNoDialog({ staffName, current, onSave, onClose }: {
  staffName: string;
  current: string | null;
  onSave: (value: string) => Promise<string | null>; // resolves to an error message, or null when saved
  onClose: () => void;
}) {
  const [value, setValue] = useState(current ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const v = value.trim();
    if (v !== "" && !PATTERN.test(v)) {
      setError("Use 1 to 40 letters, digits, spaces, dots, slashes or hyphens.");
      return;
    }
    setSaving(true);
    setError(null);
    const err = await onSave(v);
    setSaving(false);
    if (err) setError(err);
  }

  return (
    <Dialog title="Registration number" onClose={onClose}>
      <form className={styles.dialogForm} onSubmit={submit}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="regNo">Medical registration number for {staffName}</label>
          <input id="regNo" className={styles.input} value={value} maxLength={40} autoFocus autoComplete="off"
            onChange={(e) => setValue(e.target.value)} />
        </div>
        <p className={styles.muted}>Printed on this person&apos;s signed prescriptions. Leave empty to remove it; nothing is printed when it is not set.</p>
        <button className={styles.submit} type="submit" disabled={saving}>{saving ? "Saving…" : "Save"}</button>
        {error && <div className={styles.formError} role="alert">{error}</div>}
      </form>
    </Dialog>
  );
}
