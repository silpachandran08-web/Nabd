"use client";

import { useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import { api, reasonText } from "../../lib/api";

// PHL-032, shared since PHL-056: the "Business details" step of the pharmacy and lab setup wizards (legal name and GSTIN / VAT number,
// written to the clinic profile). Moved here unchanged from the pharmacy wizard page.
export type BusinessProfile = { name: string; taxId: string | null; timezone: string; taxIdType: string | null; whatsappNumber: string | null; specialties: string[] };

export default function BusinessDetailsStep({ profile, canEdit, onSaved, onError }: { profile: BusinessProfile; canEdit: boolean; onSaved: () => Promise<void>; onError: (m: string | null) => void }) {
  const [name, setName] = useState(profile.name);
  const [type, setType] = useState(profile.taxIdType ?? "GSTIN");
  const [taxId, setTaxId] = useState(profile.taxId ?? "");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true); onError(null);
    try { await api.patch("/setup/profile", { name: name.trim(), timezone: profile.timezone, taxIdType: type, taxId: taxId.trim() || null, whatsappNumber: profile.whatsappNumber, specialties: profile.specialties }); await onSaved(); }
    catch (e) { onError(reasonText(e, "Could not save the business details.")); }
    setBusy(false);
  };
  return (
    <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <div className={styles.field}><label className={styles.label} htmlFor="pw-name">Legal name</label><input id="pw-name" className={styles.input} value={name} onChange={(e) => setName(e.target.value)} disabled={!canEdit} required /></div>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="pw-taxtype">Tax number type</label>
        <select id="pw-taxtype" className={styles.input} value={type} onChange={(e) => setType(e.target.value)} disabled={!canEdit}><option value="GSTIN">GSTIN (India)</option><option value="VAT">VAT number</option></select>
      </div>
      <div className={styles.field}><label className={styles.label} htmlFor="pw-taxid">{type === "VAT" ? "VAT number" : "GSTIN"}</label><input id="pw-taxid" className={styles.input} value={taxId} onChange={(e) => setTaxId(e.target.value)} disabled={!canEdit} /></div>
      {canEdit && <button type="submit" className={styles.btnPrimary} disabled={busy || !name.trim()}>{busy ? "Saving" : "Save"}</button>}
    </form>
  );
}
