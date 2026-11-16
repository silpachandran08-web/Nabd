"use client";

import { useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import styles from "../../(clinic)/staff/staff.module.css";
import { api, reasonText } from "../../lib/api";
import { localized, pickable, type ProfileItem } from "../../(clinic)/specialty/specialtyModel";

// CLN-042: the owner sets a doctor's primary and optional secondary specialty profile. Only profiles the clinic's plan
// unlocks are offered (GET /specialty/profiles, staff:view); PUT /staff/{id}/specialty-profiles needs staff:edit, and the
// caller only renders this for holders of it. The doctor's session picks the change up at the next sign-in.
type Assignment = { primary: string | null; secondary: string | null };

export default function SpecialtyProfileDialog({ staffId, staffName, onClose }: { staffId: string; staffName: string; onClose: () => void }) {
  const [items, setItems] = useState<ProfileItem[] | null | "error">(null);
  const [current, setCurrent] = useState<Assignment>({ primary: null, secondary: null });
  const [primary, setPrimary] = useState("");
  const [secondary, setSecondary] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const [list, a] = await Promise.all([
          api.get<{ items: ProfileItem[] }>("/specialty/profiles"),
          api.get<Assignment>(`/staff/${staffId}/specialty-profiles`),
        ]);
        if (!live) return;
        setItems(list.items);
        setCurrent(a);
        setPrimary(a.primary ?? "");
        setSecondary(a.secondary ?? "");
      } catch {
        if (live) setItems("error");
      }
    })();
    return () => { live = false; };
  }, [staffId]);

  const options = Array.isArray(items) ? pickable(items) : [];
  const name = (code: string | null) => (Array.isArray(items) && localized(items.find((p) => p.code === code)?.name, "en", code ?? "")) || "";
  const lapsed = [current.primary, current.secondary].filter((c): c is string => !!c && !options.some((o) => o.code === c));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (primary === "") { setError("Choose a primary profile."); return; }
    if (primary === secondary) { setError("The secondary profile must differ from the primary one."); return; }
    setSaving(true);
    setError(null);
    try {
      await api.put(`/staff/${staffId}/specialty-profiles`, secondary ? { primary, secondary } : { primary });
      onClose();
    } catch (err) {
      setError(reasonText(err, "Couldn't save the specialty profile. Nothing was changed."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog title="Specialty profile" onClose={onClose}>
      <form className={styles.dialogForm} onSubmit={submit}>
        {items === null && <p className={styles.muted} role="status">Loading specialty profiles…</p>}
        {items === "error" && <p className={styles.formError} role="alert">Couldn&apos;t load the specialty profiles. Close and try again.</p>}
        {Array.isArray(items) && options.length === 0 && <p className={styles.muted}>Your plan does not include any specialty profile.</p>}
        {Array.isArray(items) && options.length > 0 && (
          <>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="specPrimary">Primary profile for {staffName}</label>
              <select id="specPrimary" className={styles.input} value={primary} onChange={(e) => setPrimary(e.target.value)}>
                <option value="">Choose…</option>
                {options.map((p) => <option key={p.code} value={p.code}>{localized(p.name, "en", p.code)}</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="specSecondary">Secondary profile (optional)</label>
              <select id="specSecondary" className={styles.input} value={secondary} onChange={(e) => setSecondary(e.target.value)}>
                <option value="">None</option>
                {options.filter((p) => p.code !== primary).map((p) => <option key={p.code} value={p.code}>{localized(p.name, "en", p.code)}</option>)}
              </select>
            </div>
            {lapsed.length > 0 && (
              <p className={styles.formError} role="status">
                Your plan no longer includes {lapsed.map(name).join(" and ")}. Records stay readable, but new ones can&apos;t be added. Choose an included profile or upgrade the plan.
              </p>
            )}
            <p className={styles.muted}>Only the profiles your plan includes are listed. The change applies at {staffName}&apos;s next sign-in.</p>
            <button className={styles.submit} type="submit" disabled={saving}>{saving ? "Saving…" : "Save"}</button>
          </>
        )}
        {error && <div className={styles.formError} role="alert">{error}</div>}
      </form>
    </Dialog>
  );
}
