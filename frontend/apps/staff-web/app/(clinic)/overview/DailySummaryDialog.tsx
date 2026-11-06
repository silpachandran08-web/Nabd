"use client";

import { useEffect, useState } from "react";
import type { DailySummaryPreviewResponse, DailySummarySettingsResponse, DailySummaryCandidate } from "@nabd/api-client/generated";
import Dialog from "../../components/ui/Dialog";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { api, reasonText } from "../../lib/api";
import type { Full } from "../../lib/gen";
import styles from "./overview.module.css";
import { SLOT_LABELS, toRequest } from "./dailySummaryModel";

// GET/PUT /v1/reports/daily-summary/settings and GET /v1/reports/daily-summary/preview (DailySummaryController, owner only).
type Settings = Omit<Full<DailySummarySettingsResponse>, "recipients" | "candidates"> & { recipients: { staffId: string }[]; candidates: Full<DailySummaryCandidate>[] };
type Preview = Full<DailySummaryPreviewResponse>;

/** ADM-043: the owner's daily WhatsApp summary: opt in, who gets it, when, one combined message for a chain, and a preview of today's text. */
export default function DailySummaryDialog({ onClose }: { onClose: () => void }) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [combined, setCombined] = useState(false);
  const [recipients, setRecipients] = useState<string[]>([]);
  const [sendAt, setSendAt] = useState("close");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const load = () => {
    setLoadError(null);
    Promise.all([api.get<Settings>("/reports/daily-summary/settings"), api.get<Preview>("/reports/daily-summary/preview")])
      .then(([s, p]) => {
        setSettings(s);
        setPreview(p);
        setEnabled(s.enabled);
        setCombined(s.combined);
        setRecipients(s.recipients.map((r) => r.staffId));
        setSendAt(s.sendAt);
      })
      .catch((e) => setLoadError(reasonText(e, "Couldn't load the daily summary settings.")));
  };
  useEffect(() => {
    void Promise.resolve().then(load);
  }, []);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    setSaveError(null);
    setSaved(false);
    try {
      const s = await api.put<Settings>("/reports/daily-summary/settings", toRequest({ enabled, combined, recipients, sendAt }));
      setSettings(s);
      setSaved(true);
    } catch (err) {
      setSaveError(reasonText(err, "Couldn't save the settings."));
    } finally {
      setSaving(false);
    }
  }

  const toggle = (id: string) => setRecipients((r) => (r.includes(id) ? r.filter((x) => x !== id) : [...r, id]));

  return (
    <Dialog title="Daily WhatsApp summary" onClose={onClose}>
      {loadError ? <ErrorState message={loadError} onRetry={load} />
        : !settings || !preview ? <Skeleton rows={4} label="Loading daily summary settings" />
        : (
          <form onSubmit={save}>
            <p className={styles.dsSub}>A short WhatsApp message at clinic close with visits, collections by mode, no-shows, average wait and anything needing attention. Counts and amounts only: never a patient name.</p>
            <label className={styles.dsRow}>
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> Send me the daily summary
            </label>
            <fieldset className={styles.dsFieldset}>
              <legend className={styles.cardLabel}>Recipients (owners)</legend>
              {settings.candidates.length === 0 ? <p className={styles.empty}>No active owner found.</p>
                : settings.candidates.map((c) => (
                  <label key={c.staffId} className={styles.dsRow}>
                    <input type="checkbox" checked={recipients.includes(c.staffId)} onChange={() => toggle(c.staffId)} />
                    {c.name}{!c.hasPhone && <span className={styles.quickSub}> · no mobile number on file</span>}
                  </label>
                ))}
            </fieldset>
            <fieldset className={styles.dsFieldset}>
              <legend className={styles.cardLabel}>When</legend>
              <label className={styles.dsRow}>
                <input type="radio" name="ds-when" checked={sendAt === "close"} onChange={() => setSendAt("close")} /> At clinic close (last session end + 30 min, or the day close if earlier)
              </label>
              <label className={styles.dsRow}>
                <input type="radio" name="ds-when" checked={sendAt !== "close"} onChange={() => setSendAt("21:00")} /> At a fixed time
                {sendAt !== "close" && <input type="time" aria-label="Send time" className={styles.dsInput} value={sendAt} onChange={(e) => setSendAt(e.target.value)} />}
              </label>
            </fieldset>
            {settings.branches > 1 && (
              <label className={styles.dsRow}>
                <input type="checkbox" checked={combined} onChange={(e) => setCombined(e.target.checked)} /> One combined message for all {settings.branches} branches
              </label>
            )}
            <section className={styles.dsPreview} aria-label="Preview of today's message">
              <div className={styles.cardLabel}>Today&apos;s message{preview.partial ? " (partial: figures still updating)" : ""}</div>
              {preview.params.map((p, i) => <div key={SLOT_LABELS[i] ?? i} className={styles.dsLine}><span className={styles.quickSub}>{SLOT_LABELS[i] ?? ""}</span> {p}</div>)}
            </section>
            {saveError && <div className={styles.dsError} role="alert">{saveError}</div>}
            {saved && <div className={styles.dsOk} role="status">Saved.</div>}
            <div className={styles.dsActions}>
              <button type="button" className={styles.dsBtn} onClick={onClose}>Close</button>
              <button type="submit" className={styles.dsSave} disabled={saving}>{saving ? "Saving…" : "Save"}</button>
            </div>
          </form>
        )}
    </Dialog>
  );
}
