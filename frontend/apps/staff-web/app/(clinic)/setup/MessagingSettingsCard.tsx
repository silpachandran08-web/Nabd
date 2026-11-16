"use client";
// COM-024 owner.setup: quiet hours (every clinic) and prayer windows (KSA default on, UAE/Qatar opt in) for non-urgent patient
// messages. The form is absent for anyone without setup:edit (they see the saved values); the draft lives in the setup store so
// it survives tab switches. The server re-checks everything (422 INVALID_WINDOW) and the Comms Gateway applies the result.
import { useState } from "react";
import { useTranslations } from "next-intl";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { api, reasonText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { can } from "../../lib/permissions";
import { useHasMounted } from "../../lib/useHasMounted";
import { useSetupState } from "./setupStore";
import { invalidReason, METHODS, toCoord, type MessagingSettings, type Method } from "./messagingSettings";
import styles from "./messagingSettings.module.css";

type Draft = MessagingSettings & { lat: string; lng: string };

const toDraft = (s: MessagingSettings): Draft => ({ ...s, lat: s.prayerWindows.latitude?.toString() ?? "", lng: s.prayerWindows.longitude?.toString() ?? "" });
const fromDraft = (d: Draft): MessagingSettings => ({
  quietHours: d.quietHours,
  prayerWindows: { ...d.prayerWindows, latitude: toCoord(d.lat), longitude: toCoord(d.lng) },
});

export default function MessagingSettingsCard() {
  const t = useTranslations("common.messagingSettings");
  const qc = useQueryClient();
  const mounted = useHasMounted();
  const canEdit = mounted && can("setup:edit");
  const q = useQuery({ queryKey: key("messaging-settings"), queryFn: getter<MessagingSettings>("/setup/messaging-settings") });
  const [draft, setDraft] = useSetupState<Draft | null>("messagingSettingsDraft", null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  if (q.isPending) return <Skeleton rows={3} label={t("loading")} />;
  if (q.isError) return <ErrorState message={t("loadFailed")} onRetry={() => void q.refetch()} />;
  const d = draft ?? toDraft(q.data);
  const s = fromDraft(d);
  const problem = invalidReason(s);
  const edit = (patch: Partial<Draft>) => { setDraft({ ...d, ...patch }); setSaved(false); };
  const quiet = (patch: Partial<Draft["quietHours"]>) => edit({ quietHours: { ...d.quietHours, ...patch } });
  const prayer = (patch: Partial<Draft["prayerWindows"]>) => edit({ prayerWindows: { ...d.prayerWindows, ...patch } });

  async function save() {
    if (problem || busy) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api.put<MessagingSettings>("/setup/messaging-settings", s);
      qc.setQueryData(key("messaging-settings"), r);
      setDraft(null);
      setSaved(true);
    } catch (e) {
      setError(reasonText(e, t("saveFailed")));
    } finally {
      setBusy(false);
    }
  }

  const p = q.data.prayerWindows;
  return (
    <section className={styles.card} aria-labelledby="messaging-settings-h" data-testid="messaging-settings">
      <h2 id="messaging-settings-h" className={styles.h3}>{t("title")}</h2>
      <p className={styles.muted}>{t("lead")}</p>
      {saved && <div className={styles.notice} role="status">{t("saved")}</div>}
      {!canEdit ? (
        <dl className={styles.dl} data-testid="messaging-settings-view">
          <dt>{t("quiet")}</dt>
          <dd>{q.data.quietHours.enabled ? t("window", { start: q.data.quietHours.start, end: q.data.quietHours.end }) : t("off")}</dd>
          <dt>{t("prayer")}</dt>
          <dd>{p.enabled ? t("prayerOn", { method: t(`method.${p.method}`), buffer: p.bufferMinutes }) : t("off")}</dd>
        </dl>
      ) : (
        <>
          <fieldset className={styles.fieldset}>
            <legend className={styles.legend}>{t("quiet")}</legend>
            <label className={styles.check}>
              <input type="checkbox" checked={d.quietHours.enabled} onChange={(e) => quiet({ enabled: e.target.checked })} /> {t("enabled")}
            </label>
            <label className={styles.field}>
              <span>{t("from")}</span>
              <input className={styles.input} type="time" value={d.quietHours.start} onChange={(e) => quiet({ start: e.target.value })} />
            </label>
            <label className={styles.field}>
              <span>{t("to")}</span>
              <input className={styles.input} type="time" value={d.quietHours.end} onChange={(e) => quiet({ end: e.target.value })} />
            </label>
          </fieldset>
          <fieldset className={styles.fieldset}>
            <legend className={styles.legend}>{t("prayer")}</legend>
            <label className={styles.check}>
              <input type="checkbox" checked={d.prayerWindows.enabled} onChange={(e) => prayer({ enabled: e.target.checked })} /> {t("enabled")}
            </label>
            <label className={styles.field}>
              <span>{t("methodLabel")}</span>
              <select className={styles.input} value={d.prayerWindows.method} onChange={(e) => prayer({ method: e.target.value as Method })}>
                {METHODS.map((m) => <option key={m} value={m}>{t(`method.${m}`)}</option>)}
              </select>
            </label>
            <label className={styles.field}>
              <span>{t("buffer")}</span>
              <input className={styles.input} type="number" min={0} max={60} value={d.prayerWindows.bufferMinutes}
                onChange={(e) => prayer({ bufferMinutes: Number(e.target.value) })} />
            </label>
            <label className={styles.field}>
              <span>{t("fridayFrom")}</span>
              <input className={styles.input} type="time" value={d.prayerWindows.fridayBlock.start}
                onChange={(e) => prayer({ fridayBlock: { ...d.prayerWindows.fridayBlock, start: e.target.value } })} />
            </label>
            <label className={styles.field}>
              <span>{t("fridayTo")}</span>
              <input className={styles.input} type="time" value={d.prayerWindows.fridayBlock.end}
                onChange={(e) => prayer({ fridayBlock: { ...d.prayerWindows.fridayBlock, end: e.target.value } })} />
            </label>
            <label className={styles.field}>
              <span>{t("latitude")}</span>
              <input className={styles.input} inputMode="decimal" value={d.lat} onChange={(e) => edit({ lat: e.target.value })} />
            </label>
            <label className={styles.field}>
              <span>{t("longitude")}</span>
              <input className={styles.input} inputMode="decimal" value={d.lng} onChange={(e) => edit({ lng: e.target.value })} />
            </label>
          </fieldset>
          <p className={styles.muted}>{t("urgentNote")}</p>
          <p className={styles.muted}>{t("localCheck")}</p>
          {problem && <div className={styles.error} role="alert">{t(`invalid.${problem}`)}</div>}
          {error && <div className={styles.error} role="alert">{error}</div>}
          <div className={styles.actions}>
            <button type="button" className={styles.btn} disabled={!!problem || busy} onClick={() => void save()}>{busy ? t("saving") : t("save")}</button>
          </div>
        </>
      )}
    </section>
  );
}
